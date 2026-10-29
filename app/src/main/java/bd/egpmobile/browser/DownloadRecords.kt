package bd.egpmobile.browser

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** One download, as shown on the Downloads screen. */
data class DownloadRecord(
    val id: String,
    val fileName: String,
    val mimeType: String,
    /** content:// URI of the saved file (MediaStore or FileProvider), once saved. */
    val uri: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: Status,
    val failure: Failure? = null,
    /** HTTP status for [Failure.HTTP_ERROR]. */
    val httpCode: Int? = null,
    val createdAt: Long,
    /** Source URL and referer, kept in app-private storage only so a failed download can be retried. */
    val url: String,
    val referer: String?,
) {
    enum class Status { RUNNING, COMPLETED, FAILED }

    enum class Failure {
        /** The portal returned a web page instead of the file (session expired or form-only document). */
        PAGE_INSTEAD_OF_FILE,
        HTTP_ERROR,
        NETWORK,
        STORAGE,
        /** Redirected somewhere unsafe, or the content is an executable. */
        BLOCKED,
        TOO_LARGE,
        /** The app was closed while downloading. */
        INTERRUPTED,
    }

    val isPdf: Boolean get() = mimeType == "application/pdf"
}

/** Persists download records as JSON in app-private preferences (newest first, at most 100). */
class DownloadRecords(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("downloads_v2", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<DownloadRecord> {
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { runCatching { fromJson(array.getJSONObject(it)) }.getOrNull() }
    }

    @Synchronized
    fun get(id: String): DownloadRecord? = all().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(record: DownloadRecord) {
        val list = all().filterNot { it.id == record.id }.toMutableList()
        list.add(0, record)
        save(list.sortedByDescending { it.createdAt }.take(MAX))
    }

    @Synchronized
    fun remove(id: String) = save(all().filterNot { it.id == id })

    /** Downloads left RUNNING by a previous process can never finish; mark them interrupted. */
    @Synchronized
    fun markInterrupted(activeIds: Set<String>) {
        val list = all()
        if (list.none { it.status == DownloadRecord.Status.RUNNING && it.id !in activeIds }) return
        save(list.map {
            if (it.status == DownloadRecord.Status.RUNNING && it.id !in activeIds) {
                it.copy(status = DownloadRecord.Status.FAILED, failure = DownloadRecord.Failure.INTERRUPTED)
            } else {
                it
            }
        })
    }

    private fun save(list: List<DownloadRecord>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs.edit { putString(KEY, array.toString()) }
    }

    private fun toJson(r: DownloadRecord) = JSONObject()
        .put("id", r.id).put("fileName", r.fileName).put("mimeType", r.mimeType).put("uri", r.uri ?: JSONObject.NULL)
        .put("downloaded", r.downloadedBytes).put("total", r.totalBytes).put("status", r.status.name)
        .put("failure", r.failure?.name ?: JSONObject.NULL).put("httpCode", r.httpCode ?: JSONObject.NULL)
        .put("createdAt", r.createdAt).put("url", r.url).put("referer", r.referer ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject) = DownloadRecord(
        id = o.getString("id"),
        fileName = o.getString("fileName"),
        mimeType = o.getString("mimeType"),
        uri = o.optString("uri").takeIf { !o.isNull("uri") && it.isNotEmpty() },
        downloadedBytes = o.optLong("downloaded"),
        totalBytes = o.optLong("total", -1),
        status = DownloadRecord.Status.valueOf(o.getString("status")),
        failure = if (o.isNull("failure")) null else DownloadRecord.Failure.valueOf(o.getString("failure")),
        httpCode = if (o.isNull("httpCode")) null else o.getInt("httpCode"),
        createdAt = o.getLong("createdAt"),
        url = o.getString("url"),
        referer = if (o.isNull("referer")) null else o.getString("referer"),
    )

    private companion object {
        const val KEY = "records"
        const val MAX = 100
    }
}
