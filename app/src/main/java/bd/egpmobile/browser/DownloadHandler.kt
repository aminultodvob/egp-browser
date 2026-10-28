package bd.egpmobile.browser

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/** A download requested by a page (from WebView's DownloadListener) or a retry. */
data class PendingDownload(
    val url: String,
    val userAgent: String,
    val contentDisposition: String?,
    val mimeType: String?,
    /** Page that started the download; sent as Referer only to trusted hosts. */
    val referer: String?,
    /** Set on retries, where the original Content-Disposition is no longer available. */
    val fileNameOverride: String? = null,
)

/**
 * Validates downloads and hands them to Android's DownloadManager, which provides progress,
 * completion notifications and storage in the public Downloads/e-GP folder.
 *
 * Session cookies are attached only for trusted e-GP hosts, never for other sites. Cookies and
 * URLs are never logged.
 */
class DownloadHandler(
    context: Context,
    private val trustedHosts: () -> Set<String>,
) {

    sealed class Result {
        data class Started(val id: Long, val fileName: String) : Result()
        data class Rejected(val reason: DownloadPolicy.Rejection, val url: String) : Result()
        /** Android 8–9 only: WRITE_EXTERNAL_STORAGE is needed for the public Downloads folder. */
        data object NeedsStoragePermission : Result()
        data object Failed : Result()
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DownloadManager::class.java)
    private val store = DownloadStore(appContext)

    fun enqueue(download: PendingDownload): Result {
        val url = when (val validation = DownloadPolicy.validate(download.url, trustedHosts())) {
            is DownloadPolicy.Validation.Accepted -> validation.url
            is DownloadPolicy.Validation.Rejected -> return Result.Rejected(validation.reason, download.url)
        }
        val fileName = download.fileNameOverride?.let(DownloadPolicy::sanitize)
            ?: DownloadPolicy.fileName(url, download.contentDisposition, download.mimeType)
        if (DownloadPolicy.isExecutable(fileName, download.mimeType)) {
            return Result.Rejected(DownloadPolicy.Rejection.EXECUTABLE, download.url)
        }
        if (needsStoragePermission()) return Result.NeedsStoragePermission
        if (manager == null) return Result.Failed

        return try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription(appContext.getString(R.string.download_notification_description))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$SUBDIRECTORY/$fileName")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            download.mimeType?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
                ?.let(request::setMimeType)
            // Validation above guarantees url is HTTPS on a trusted host.
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            request.addRequestHeader("User-Agent", download.userAgent)
            download.referer
                ?.takeIf { UrlValidator.isTrustedSecureUrl(it, trustedHosts()) }
                ?.let { request.addRequestHeader("Referer", it) }
            val id = manager.enqueue(request)
            store.add(id)
            SafeLog.d(TAG, "Download queued: ${UrlValidator.redact(url)}")
            Result.Started(id, fileName)
        } catch (e: IllegalStateException) {
            SafeLog.w(TAG, "Download could not be queued", e)
            Result.Failed
        } catch (e: SecurityException) {
            SafeLog.w(TAG, "Download could not be queued", e)
            Result.Failed
        }
    }

    /**
     * Re-requests a failed download with fresh cookies. Links that expired on the server must be
     * downloaded again from the page.
     */
    fun retry(id: Long, userAgent: String): Result {
        val manager = manager ?: return Result.Failed
        var url: String? = null
        var title: String? = null
        var mime: String? = null
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                url = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                mime = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE))
            }
        }
        val originalUrl = url ?: return Result.Failed
        val result = enqueue(PendingDownload(originalUrl, userAgent, null, mime, null, title))
        if (result is Result.Started) {
            manager.remove(id)
            store.remove(id)
        }
        return result
    }

    private fun needsStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "DownloadHandler"
        const val SUBDIRECTORY = "e-GP"
    }
}

/** IDs of downloads started by this app, so the downloads screen lists only its own files. */
class DownloadStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun ids(): List<Long> =
        prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.sortedDescending()

    fun add(id: Long) = update { it + id.toString() }

    fun remove(id: Long) = update { it - id.toString() }

    fun retainOnly(ids: Collection<Long>) = update { current -> current.filter { it.toLongOrNull() in ids }.toSet() }

    private fun update(transform: (Set<String>) -> Set<String>) {
        val current = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()
        prefs.edit { putStringSet(KEY_IDS, transform(current)) }
    }

    private companion object {
        const val KEY_IDS = "ids"
    }
}
