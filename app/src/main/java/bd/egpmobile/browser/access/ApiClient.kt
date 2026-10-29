package bd.egpmobile.browser.access

import bd.egpmobile.browser.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An error response from the backend ({"error":{"code","message"}}). */
class ApiException(val httpStatus: Int, val code: String, override val message: String) : Exception(message)

/** The backend URL is missing or not HTTPS in a release build. */
class NotConfiguredException : IOException("Service not configured")

/**
 * Minimal JSON client for the e-GP Browser backend. Blocking: call from Dispatchers.IO.
 * Access tokens are attached by [AccessRepository]; nothing here is logged.
 */
class ApiClient(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val http: OkHttpClient = defaultClient(),
) {

    val isConfigured: Boolean
        get() = baseUrl.startsWith("https://") || (BuildConfig.DEBUG && baseUrl.startsWith("http://"))

    fun get(path: String, accessToken: String?): JSONObject = execute("GET", path, null, accessToken)

    fun post(path: String, body: JSONObject?, accessToken: String?): JSONObject = execute("POST", path, body, accessToken)

    fun delete(path: String, accessToken: String?): JSONObject = execute("DELETE", path, null, accessToken)

    private fun execute(method: String, path: String, body: JSONObject?, accessToken: String?): JSONObject {
        if (!isConfigured) throw NotConfiguredException()
        val requestBody = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "POST" -> "{}".toRequestBody(JSON)
            else -> null
        }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .method(method, requestBody)
            .header("Accept", "application/json")
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse {
                throw IOException("Unexpected response from server")
            }
            if (!response.isSuccessful) {
                val error = json.optJSONObject("error")
                throw ApiException(
                    response.code,
                    error?.optString("code").orEmpty().ifEmpty { "HTTP_${response.code}" },
                    error?.optString("message").orEmpty().ifEmpty { "Request failed" },
                )
            }
            return json
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }
}
