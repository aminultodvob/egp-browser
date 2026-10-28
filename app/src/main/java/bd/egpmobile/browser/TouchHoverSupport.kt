package bd.egpmobile.browser

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Makes the portal's hover-only menus usable by touch (see assets/touch_hover.js).
 *
 * The script is limited to the official e-GP origins, never to user-added hosts. It is
 * registered as a document-start script where WebView supports it; on older WebViews it is
 * evaluated when each official page finishes loading. It adds no JavaScript interface.
 */
class TouchHoverSupport(context: Context) {

    private val script: String by lazy {
        context.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
    }

    fun install(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, script, ALLOWED_ORIGINS)
        }
    }

    /** Fallback for WebViews without document-start scripts; the script ignores repeat runs. */
    fun onPageFinished(view: WebView, url: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && isOfficialPortal(url)) {
            view.evaluateJavascript(script, null)
        }
    }

    companion object {
        const val ASSET = "touch_hover.js"

        val ALLOWED_ORIGINS: Set<String> = UrlValidator.DEFAULT_TRUSTED_HOSTS.map { "https://$it" }.toSet()

        fun isOfficialPortal(url: String): Boolean =
            UrlValidator.isTrustedSecureUrl(url, UrlValidator.DEFAULT_TRUSTED_HOSTS)
    }
}
