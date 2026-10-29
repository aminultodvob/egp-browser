package bd.egpmobile.browser

import java.util.Locale

/**
 * Identifies a downloaded file from its first bytes ("magic numbers"), so the saved file gets the
 * right extension and MIME type even when the server labels it wrongly, and so an HTML error page
 * returned instead of a document is never saved as a fake ".pdf". Pure Kotlin, unit tested.
 */
object ContentSniffer {

    enum class Kind(val mimeType: String?, val extension: String?) {
        PDF("application/pdf", "pdf"),
        /** ZIP container: .zip and also .docx / .xlsx / .pptx (Office Open XML). */
        ZIP("application/zip", "zip"),
        /** OLE2 compound file: legacy .doc / .xls / .ppt. */
        OLE("application/msword", "doc"),
        RAR("application/vnd.rar", "rar"),
        SEVEN_ZIP("application/x-7z-compressed", "7z"),
        PNG("image/png", "png"),
        JPEG("image/jpeg", "jpg"),
        GIF("image/gif", "gif"),
        /** A web page (typically an error or "session expired" page). */
        HTML("text/html", "html"),
        /** Windows executable. Never saved. */
        WINDOWS_EXE(null, null),
        UNKNOWN(null, null),
    }

    private const val PEEK = 1024

    /**
     * Characters to skip before checking for HTML: the Unicode byte-order mark (U+FEFF), the UTF-8 BOM
     * bytes as they appear when decoded as ISO-8859-1 (0xEF 0xBB 0xBF), and whitespace.
     */
    private val LEADING_NOISE: Set<Char> = setOf(0xFEFF, 0xEF, 0xBB, 0xBF, 0x20, 0x09, 0x0D, 0x0A).map { it.toChar() }.toSet()

    fun detect(head: ByteArray): Kind {
        fun startsWith(vararg bytes: Int) = head.size >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }
        return when {
            pdfHeaderWithin(head) -> Kind.PDF
            startsWith(0x50, 0x4B, 0x03, 0x04) || startsWith(0x50, 0x4B, 0x05, 0x06) -> Kind.ZIP
            startsWith(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) -> Kind.OLE
            startsWith(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> Kind.RAR
            startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Kind.SEVEN_ZIP
            startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> Kind.PNG
            startsWith(0xFF, 0xD8, 0xFF) -> Kind.JPEG
            startsWith(0x47, 0x49, 0x46, 0x38) -> Kind.GIF
            startsWith(0x4D, 0x5A) -> Kind.WINDOWS_EXE
            looksLikeHtml(head) -> Kind.HTML
            else -> Kind.UNKNOWN
        }
    }

    /** PDF readers accept the "%PDF-" header anywhere in the first 1024 bytes. */
    private fun pdfHeaderWithin(head: ByteArray): Boolean {
        val marker = "%PDF-".toByteArray(Charsets.US_ASCII)
        val limit = minOf(head.size, PEEK) - marker.size
        for (i in 0..limit) {
            if (marker.indices.all { head[i + it] == marker[it] }) return true
        }
        return false
    }

    private fun looksLikeHtml(head: ByteArray): Boolean {
        val text = String(head, 0, minOf(head.size, 512), Charsets.ISO_8859_1)
            .trimStart { it in LEADING_NOISE }
            .lowercase(Locale.ROOT)
        return text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<head") ||
            text.startsWith("<body") || text.startsWith("<script") || text.startsWith("<meta") ||
            (text.startsWith("<?xml") && text.contains("<html"))
    }

    /**
     * Final file name: keeps a matching extension (e.g. "boq.xlsx" for a ZIP container), otherwise
     * replaces or adds the extension the content proves.
     */
    fun fixFileName(name: String, kind: Kind): String {
        val wanted = kind.extension ?: return name
        val current = DownloadPolicy.extensionOf(name)
        val compatible = when (kind) {
            Kind.ZIP -> setOf("zip", "docx", "xlsx", "pptx", "odt", "ods", "odp", "jar", "apk")
            Kind.OLE -> setOf("doc", "xls", "ppt", "msg")
            Kind.JPEG -> setOf("jpg", "jpeg")
            else -> setOf(wanted)
        }
        if (current != null && current in compatible) return name
        val base = if (current != null && current in REPLACEABLE) name.substringBeforeLast('.') else name
        return "$base.$wanted"
    }

    /** MIME type from proven content first, then from a trustworthy extension. */
    fun mimeTypeFor(fileName: String, kind: Kind, declared: String?): String {
        val byExtension = DownloadPolicy.extensionOf(fileName)?.let { EXTENSION_MIME[it] }
        return when (kind) {
            Kind.ZIP, Kind.OLE -> byExtension ?: kind.mimeType!!
            Kind.UNKNOWN, Kind.WINDOWS_EXE -> byExtension
                ?: declared?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
                ?: "application/octet-stream"
            else -> kind.mimeType!!
        }
    }

    /** Extensions that are not a real document type (server script names, unknown binaries). */
    private val REPLACEABLE = setOf("jsp", "do", "action", "php", "asp", "aspx", "cgi", "bin", "dat", "tmp", "htm", "html")

    private val EXTENSION_MIME = mapOf(
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
    )
}
