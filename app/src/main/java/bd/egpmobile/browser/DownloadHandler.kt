package bd.egpmobile.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A download requested by a page (from WebView's DownloadListener) or a retry. */
data class PendingDownload(
    val url: String,
    val userAgent: String,
    val contentDisposition: String?,
    val mimeType: String?,
    /** Page that started the download; sent as Referer only to trusted hosts. */
    val referer: String?,
)

/** Download progress and results, for snackbars and notifications. */
sealed class DownloadEvent {
    abstract val record: DownloadRecord
    data class Progress(override val record: DownloadRecord) : DownloadEvent()
    data class Completed(override val record: DownloadRecord) : DownloadEvent()
    data class Failed(override val record: DownloadRecord) : DownloadEvent()
}

/**
 * Downloads documents inside the app, with the browser's own session, instead of handing the URL to
 * Android's DownloadManager (which re-requests it separately, so e-GP often answered with an HTML
 * page that was then saved as a broken ".pdf").
 *
 *  - Session cookies are sent only to trusted e-GP hosts over HTTPS. Redirects are followed manually
 *    (at most 5): HTTPS only, and cookies never leave the trusted hosts.
 *  - The first bytes are checked: a real PDF / Office / ZIP file is saved with the matching extension
 *    and MIME type; a web page returned instead of the document is refused with a clear reason.
 *  - Files are saved to Downloads/e-GP through MediaStore (Android 10+) or the public Downloads
 *    folder (Android 8–9), so other apps can open them. Nothing here is logged.
 */
class DownloadHandler(
    context: Context,
    private val trustedHosts: () -> Set<String>,
) {

    sealed class Result {
        data class Started(val id: String, val fileName: String) : Result()
        data class Rejected(val reason: DownloadPolicy.Rejection, val url: String) : Result()
        /** Android 8–9 only: WRITE_EXTERNAL_STORAGE is needed for the public Downloads folder. */
        data object NeedsStoragePermission : Result()
    }

    private val appContext = context.applicationContext

    fun enqueue(download: PendingDownload): Result {
        val url = when (val validation = DownloadPolicy.validate(download.url, trustedHosts())) {
            is DownloadPolicy.Validation.Accepted -> validation.url
            is DownloadPolicy.Validation.Rejected -> return Result.Rejected(validation.reason, download.url)
        }
        val provisionalName = DownloadPolicy.fileName(url, download.contentDisposition, download.mimeType)
        if (DownloadPolicy.isExecutable(provisionalName, download.mimeType)) {
            return Result.Rejected(DownloadPolicy.Rejection.EXECUTABLE, download.url)
        }
        if (needsStoragePermission(appContext)) return Result.NeedsStoragePermission
        val record = Engine.get(appContext).start(download.copy(url = url), provisionalName, trustedHosts())
        return Result.Started(record.id, record.fileName)
    }

    /** Downloads the same URL again with fresh session cookies. */
    fun retry(id: String, userAgent: String): Result {
        val old = Engine.get(appContext).records.get(id) ?: return Result.Rejected(DownloadPolicy.Rejection.INVALID_URL, "")
        Engine.get(appContext).records.remove(id)
        return enqueue(PendingDownload(old.url, userAgent, null, old.mimeType.takeIf { it != "application/octet-stream" }, old.referer))
    }

    /** App-wide download engine: survives activity changes for as long as the process lives. */
    class Engine private constructor(private val context: Context) {

        val records = DownloadRecords(context)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val active = ConcurrentHashMap.newKeySet<String>()
        private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 32)
        val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()
        private val notifier = DownloadNotifier(context)
        private val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        init {
            records.markInterrupted(active)
        }

        fun start(download: PendingDownload, provisionalName: String, trustedHosts: Set<String>): DownloadRecord {
            val record = DownloadRecord(
                id = UUID.randomUUID().toString(),
                fileName = provisionalName,
                mimeType = download.mimeType?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/octet-stream",
                uri = null,
                downloadedBytes = 0,
                totalBytes = -1,
                status = DownloadRecord.Status.RUNNING,
                createdAt = System.currentTimeMillis(),
                url = download.url,
                referer = download.referer,
            )
            records.upsert(record)
            active += record.id
            scope.launch { run(record, download, trustedHosts) }
            return record
        }

        private suspend fun run(initial: DownloadRecord, download: PendingDownload, trustedHosts: Set<String>) {
            var record = initial
            notifier.progress(record)
            try {
                openFollowingRedirects(download, trustedHosts).use { response ->
                    if (!response.isSuccessful) {
                        record = fail(record, DownloadRecord.Failure.HTTP_ERROR, response.code)
                        return
                    }
                    val body = response.body ?: throw IOException("Empty body")
                    val source = body.source()
                    val head = source.peek().let { peek ->
                        peek.request(PEEK_BYTES.toLong())
                        peek.readByteArray(minOf(PEEK_BYTES.toLong(), peek.buffer.size))
                    }
                    val kind = ContentSniffer.detect(head)
                    if (kind == ContentSniffer.Kind.HTML) {
                        record = fail(record, DownloadRecord.Failure.PAGE_INSTEAD_OF_FILE)
                        return
                    }
                    val serverName = DownloadPolicy.parseContentDisposition(response.header("Content-Disposition"))
                        ?.let(DownloadPolicy::sanitize)
                    val declaredMime = response.header("Content-Type") ?: download.mimeType
                    val name = ContentSniffer.fixFileName(
                        serverName ?: DownloadPolicy.fileName(download.url, download.contentDisposition, declaredMime),
                        kind,
                    )
                    val mime = ContentSniffer.mimeTypeFor(name, kind, declaredMime)
                    if (kind == ContentSniffer.Kind.WINDOWS_EXE || DownloadPolicy.isExecutable(name, mime)) {
                        record = fail(record, DownloadRecord.Failure.BLOCKED)
                        return
                    }
                    val total = body.contentLength()
                    if (total > MAX_BYTES) {
                        record = fail(record, DownloadRecord.Failure.TOO_LARGE)
                        return
                    }
                    record = record.copy(fileName = name, mimeType = mime, totalBytes = total)
                    records.upsert(record)

                    val saved = save(name, mime) { out ->
                        var done = 0L
                        var lastReport = 0L
                        val buffer = ByteArray(64 * 1024)
                        source.inputStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                done += read
                                if (done > MAX_BYTES) throw TooLargeException()
                                try {
                                    out.write(buffer, 0, read)
                                } catch (e: IOException) {
                                    throw StorageException(e) // e.g. storage full: not a network problem
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastReport > PROGRESS_INTERVAL_MS) {
                                    lastReport = now
                                    record = record.copy(downloadedBytes = done)
                                    records.upsert(record)
                                    notifier.progress(record)
                                    _events.tryEmit(DownloadEvent.Progress(record))
                                }
                            }
                        }
                        done
                    }
                    record = record.copy(
                        fileName = saved.displayName,
                        uri = saved.uri.toString(),
                        downloadedBytes = saved.bytes,
                        totalBytes = saved.bytes,
                        status = DownloadRecord.Status.COMPLETED,
                    )
                    records.upsert(record)
                    notifier.completed(record)
                    _events.tryEmit(DownloadEvent.Completed(record))
                }
            } catch (e: UnsafeRedirectException) {
                record = fail(record, DownloadRecord.Failure.BLOCKED)
            } catch (e: TooLargeException) {
                record = fail(record, DownloadRecord.Failure.TOO_LARGE)
            } catch (e: StorageException) {
                SafeLog.w(TAG, "Saving download failed", e.cause)
                record = fail(record, DownloadRecord.Failure.STORAGE)
            } catch (e: IOException) {
                SafeLog.w(TAG, "Download failed", e)
                record = fail(record, DownloadRecord.Failure.NETWORK)
            } finally {
                active -= record.id
            }
        }

        private fun fail(record: DownloadRecord, failure: DownloadRecord.Failure, httpCode: Int? = null): DownloadRecord {
            val failed = record.copy(status = DownloadRecord.Status.FAILED, failure = failure, httpCode = httpCode)
            records.upsert(failed)
            notifier.failed(failed)
            _events.tryEmit(DownloadEvent.Failed(failed))
            return failed
        }

        /** Follows up to 5 redirects; HTTPS only, cookies and Referer only for trusted hosts. */
        private fun openFollowingRedirects(download: PendingDownload, trustedHosts: Set<String>): Response {
            var url = download.url
            repeat(MAX_REDIRECTS + 1) {
                val trusted = UrlValidator.isTrustedSecureUrl(url, trustedHosts)
                val headers = Headers.Builder().add("User-Agent", download.userAgent).add("Accept", "*/*")
                if (trusted) {
                    CookieManager.getInstance().getCookie(url)?.let { headers.add("Cookie", it) }
                    download.referer?.takeIf { UrlValidator.isTrustedSecureUrl(it, trustedHosts) }?.let { headers.add("Referer", it) }
                }
                val response = client.newCall(Request.Builder().url(url).headers(headers.build()).get().build()).execute()
                if (!response.isRedirect) return response
                val location = response.header("Location")
                response.close()
                val next = location?.let { response.request.url.resolve(it) }?.toString() ?: throw IOException("Redirect without location")
                if (next.toHttpUrlOrNull()?.isHttps != true) throw UnsafeRedirectException()
                url = next
            }
            throw IOException("Too many redirects")
        }

        private data class Saved(val uri: Uri, val displayName: String, val bytes: Long)

        /** Writes into Downloads/e-GP via MediaStore (Android 10+) or the public folder (8–9). */
        private fun save(name: String, mime: String, write: (OutputStream) -> Long): Saved =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveMediaStore(name, mime, write) else saveLegacy(name, mime, write)

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun saveMediaStore(name: String, mime: String, write: (OutputStream) -> Long): Saved {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIRECTORY")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw StorageException(IOException("MediaStore insert failed"))
            try {
                val bytes = (resolver.openOutputStream(uri) ?: throw IOException("No output stream")).use(write)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val displayName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
                return Saved(uri, displayName, bytes)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw if (e is IOException) e else StorageException(e)
            }
        }

        @Suppress("DEPRECATION") // Public Downloads path is the supported location on Android 8–9.
        private fun saveLegacy(name: String, mime: String, write: (OutputStream) -> Long): Saved {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIRECTORY)
            if (!dir.isDirectory && !dir.mkdirs()) throw StorageException(IOException("Cannot create folder"))
            val file = uniqueFile(dir, name)
            try {
                val bytes = file.outputStream().use(write)
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                return Saved(uri, file.name, bytes)
            } catch (e: Exception) {
                file.delete()
                throw if (e is IOException || e is TooLargeException) e else StorageException(e)
            }
        }

        private fun uniqueFile(dir: File, name: String): File {
            var candidate = File(dir, name)
            val base = name.substringBeforeLast('.', name)
            val ext = DownloadPolicy.extensionOf(name)?.let { ".$it" } ?: ""
            var n = 1
            while (candidate.exists()) candidate = File(dir, "$base ($n)$ext").also { n++ }
            return candidate
        }

        companion object {
            // Holds only the application context (never an Activity), so nothing is leaked.
            @SuppressLint("StaticFieldLeak")
            @Volatile
            private var instance: Engine? = null

            fun get(context: Context): Engine =
                instance ?: synchronized(this) { instance ?: Engine(context.applicationContext).also { instance = it } }
        }
    }

    private class UnsafeRedirectException : IOException("Unsafe redirect")
    private class TooLargeException : IOException("Too large")
    private class StorageException(cause: Throwable) : IOException("Storage", cause)

    companion object {
        private const val TAG = "DownloadHandler"
        const val SUBDIRECTORY = "e-GP"
        private const val PEEK_BYTES = 1024
        private const val MAX_REDIRECTS = 5
        private const val PROGRESS_INTERVAL_MS = 500L
        /** e-GP attachments are far smaller; this only stops runaway downloads. */
        const val MAX_BYTES = 500L * 1024 * 1024

        fun needsStoragePermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
    }
}
