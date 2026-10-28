package bd.egpmobile.browser

/**
 * Builds the user-agent strings for the two rendering modes.
 *
 * The desktop profile matches what Chrome itself sends from "Desktop site" on Android:
 * a Linux desktop platform with the reduced `Chrome/<major>.0.0.0` version. The major version is
 * taken from the installed Android System WebView, so the string stays current as WebView
 * updates instead of freezing an old hardcoded Chrome release. BPPA lists Chrome 109+ as a
 * tested browser, and every WebView that runs on supported devices reports a Chromium version.
 */
object UserAgentProfile {

    /** Used only if the WebView user agent cannot be parsed; documented in docs/LIMITATIONS.md. */
    const val FALLBACK_CHROME_MAJOR = "120"

    private val CHROME_VERSION = Regex("Chrome/(\\d+)")

    fun chromeMajorVersion(webViewUserAgent: String): String =
        CHROME_VERSION.find(webViewUserAgent)?.groupValues?.get(1) ?: FALLBACK_CHROME_MAJOR

    fun desktop(webViewUserAgent: String): String {
        val major = chromeMajorVersion(webViewUserAgent)
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"
    }
}
