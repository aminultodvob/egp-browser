package bd.egpmobile.browser

import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale

/** Pure download rules (file naming, executable blocking, source validation). JVM unit tested. */
object DownloadPolicy {

    enum class Rejection { UNSUPPORTED_SCHEME, UNTRUSTED_SOURCE, INVALID_URL, EXECUTABLE }

    sealed class Validation {
        /** Download [url] (possibly upgraded from HTTP) with the session cookies for its host. */
        data class Accepted(val url: String) : Validation()
        data class Rejected(val reason: Rejection) : Validation()
    }

    private val BLOCKED_EXTENSIONS = setOf(
        "apk", "apks", "aab", "xapk", "apkm", "dex", "exe", "msi", "msp", "bat", "cmd", "com", "scr",
        "ps1", "vbs", "vbe", "wsf", "jar", "sh", "bin", "elf", "so", "dll", "deb", "rpm", "app",
    )
    private val BLOCKED_MIME_TYPES = setOf(
        "application/vnd.android.package-archive",
        "application/x-msdownload",
        "application/x-msdos-program",
        "application/x-ms-installer",
        "application/x-msi",
        "application/x-executable",
        "application/x-dosexec",
        "application/x-sh",
        "application/java-archive",
    )
    private val MIME_EXTENSIONS = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.rar" to "rar",
        "application/x-rar-compressed" to "rar",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "image/jpeg" to "jpg",
        "image/png" to "png",
    )

    /** Server-side script extensions that are not the real type of a downloaded document. */
    private val SCRIPT_EXTENSIONS = setOf("jsp", "do", "action", "php", "asp", "aspx", "cgi", "htm", "html")

    private val FILENAME_EXTENDED = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    private val FILENAME_QUOTED = Regex("""\bfilename\s*=\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
    private val FILENAME_TOKEN = Regex("""\bfilename\s*=\s*([^;"]+)""", RegexOption.IGNORE_CASE)
    private val ILLEGAL_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]""")

    const val DEFAULT_NAME = "download"
    private const val MAX_NAME_LENGTH = 120

    fun validate(url: String, trustedHosts: Set<String>): Validation {
        val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme == "blob" || scheme == "data") return Validation.Rejected(Rejection.UNSUPPORTED_SCHEME)
        return when (val decision = UrlValidator.decide(url, trustedHosts)) {
            is UrlValidator.Decision.Allow ->
                if (scheme == "https") Validation.Accepted(url) else Validation.Rejected(Rejection.UNSUPPORTED_SCHEME)
            is UrlValidator.Decision.Upgrade -> Validation.Accepted(decision.httpsUrl)
            is UrlValidator.Decision.External -> Validation.Rejected(Rejection.UNTRUSTED_SOURCE)
            is UrlValidator.Decision.Block ->
                if (decision.reason == UrlValidator.BlockReason.UNSUPPORTED_SCHEME) {
                    Validation.Rejected(Rejection.UNSUPPORTED_SCHEME)
                } else {
                    Validation.Rejected(Rejection.INVALID_URL)
                }
        }
    }

    fun isExecutable(fileName: String, mimeType: String?): Boolean {
        val mime = normalizeMime(mimeType)
        if (mime != null && mime in BLOCKED_MIME_TYPES) return true
        return extensionOf(fileName) in BLOCKED_EXTENSIONS
    }

    /** Picks a safe file name from Content-Disposition, then the URL path, then the MIME type. */
    fun fileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val mime = normalizeMime(mimeType)
        val raw = parseContentDisposition(contentDisposition) ?: nameFromUrl(url) ?: DEFAULT_NAME
        var name = sanitize(raw)
        val mimeExtension = mime?.let { MIME_EXTENSIONS[it] }
        val extension = extensionOf(name)
        if (mimeExtension != null && (extension == null || extension in SCRIPT_EXTENSIONS)) {
            name = (if (extension != null) name.substringBeforeLast('.') else name) + "." + mimeExtension
        }
        return name
    }

    fun parseContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        FILENAME_EXTENDED.find(header)?.let { match ->
            val charsetName = match.groupValues[1].ifBlank { "UTF-8" }
            val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
            runCatching { URLDecoder.decode(match.groupValues[2].trim().replace("+", "%2B"), charset.name()) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        FILENAME_QUOTED.find(header)?.let { match ->
            return match.groupValues[1].replace(Regex("""\\(.)"""), "$1").takeIf { it.isNotBlank() }
        }
        return FILENAME_TOKEN.find(header)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun sanitize(name: String): String {
        val lastSegment = name.substringAfterLast('/').substringAfterLast('\\')
        var cleaned = lastSegment.replace(ILLEGAL_CHARS, "_").trim().trimStart('.').trim()
        if (cleaned.isEmpty() || cleaned.all { it == '_' }) cleaned = DEFAULT_NAME
        if (cleaned.length > MAX_NAME_LENGTH) {
            val extension = extensionOf(cleaned)
            cleaned = if (extension != null && extension.length < 10) {
                cleaned.substring(0, MAX_NAME_LENGTH - extension.length - 1) + "." + extension
            } else {
                cleaned.substring(0, MAX_NAME_LENGTH)
            }
        }
        return cleaned
    }

    fun extensionOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return null
        return fileName.substring(dot + 1).lowercase(Locale.ROOT)
    }

    private fun nameFromUrl(url: String): String? {
        val parts = UrlValidator.parse(url) ?: return null
        val path = parts.rest.substringBefore('?').substringBefore('#').substringBefore(';')
        val segment = path.trimEnd('/').substringAfterLast('/')
        if (segment.isEmpty()) return null
        return runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)
    }

    private fun normalizeMime(mimeType: String?): String? =
        mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
}
