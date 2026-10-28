package bd.egpmobile.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Owns WebView configuration and navigation for the main window (and configures pop-up windows
 * the same way). Every navigation it starts goes through [UrlValidator].
 */
class BrowserController(
    val webView: WebView,
    private val settings: BrowserSettings,
) {

    sealed class InputResult {
        data object Loaded : InputResult()
        data class External(val url: String) : InputResult()
        data object Invalid : InputResult()
        data class Blocked(val reason: UrlValidator.BlockReason) : InputResult()
    }

    private val defaultUserAgent: String = WebSettings.getDefaultUserAgent(webView.context)

    /** Read on WebView's IO thread by shouldInterceptRequest, so kept as an immutable snapshot. */
    @Volatile
    var trustedHosts: Set<String> = settings.trustedHosts
        private set

    private var destroyed = false

    /**
     * Access gate check (direct build). Every navigation the app starts passes through this
     * controller, so while access is locked nothing can be loaded, whichever control is used.
     */
    var navigationAllowed: () -> Boolean = { true }

    fun refreshTrustedHosts() {
        trustedHosts = settings.trustedHosts
    }

    @SuppressLint("SetJavaScriptEnabled") // The portal requires JavaScript; no JS bridge is added.
    fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            // Least privilege: pages cannot read local files or other apps' content:// URIs.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            safeBrowsingEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            defaultTextEncodingName = "UTF-8"
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = settings.textZoom
        }
        view.isHorizontalScrollBarEnabled = true
        view.isVerticalScrollBarEnabled = true
        applyRenderingMode(view, settings.desktopMode)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, settings.thirdPartyCookies)
        }
    }

    /**
     * Desktop: Chrome desktop user agent, a wide (980 px) layout viewport zoomed out to fit, like
     * Chrome's "Desktop site". Mobile: the WebView's own user agent and a device-width layout,
     * which re-flows the portal's fixed-width pages into a narrow column.
     */
    fun applyRenderingMode(view: WebView, desktop: Boolean) {
        view.settings.userAgentString = if (desktop) UserAgentProfile.desktop(defaultUserAgent) else null
        view.settings.useWideViewPort = desktop
        view.settings.loadWithOverviewMode = desktop
    }

    fun applyPreferences(view: WebView) {
        view.settings.textZoom = settings.textZoom
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, settings.thirdPartyCookies)
    }

    val currentUserAgent: String
        get() = webView.settings.userAgentString ?: defaultUserAgent

    /** Loads [url] only if policy allows it in the browser; returns the decision taken. */
    fun load(url: String): UrlValidator.Decision {
        val decision = UrlValidator.decide(url, trustedHosts)
        if (!navigationAllowed()) return decision
        when (decision) {
            is UrlValidator.Decision.Allow -> webView.loadUrl(decision.url)
            is UrlValidator.Decision.Upgrade -> webView.loadUrl(decision.httpsUrl)
            else -> Unit
        }
        return decision
    }

    fun navigateFromInput(input: String): InputResult {
        val url = UrlValidator.normalizeInput(input) ?: return InputResult.Invalid
        return when (val decision = load(url)) {
            is UrlValidator.Decision.Allow, is UrlValidator.Decision.Upgrade -> InputResult.Loaded
            is UrlValidator.Decision.External -> InputResult.External(decision.url)
            is UrlValidator.Decision.Block -> InputResult.Blocked(decision.reason)
        }
    }

    fun goHome() {
        load(UrlValidator.HOME_URL)
    }

    fun goBack() {
        if (navigationAllowed() && webView.canGoBack()) webView.goBack()
    }

    fun goForward() {
        if (navigationAllowed() && webView.canGoForward()) webView.goForward()
    }

    fun reload() {
        if (navigationAllowed()) webView.reload()
    }

    fun stop() = webView.stopLoading()

    fun zoomIn() = webView.zoomIn()

    fun zoomOut() = webView.zoomOut()

    fun saveState(outState: Bundle) {
        if (destroyed) return
        val state = Bundle()
        webView.saveState(state)
        outState.putBundle(KEY_WEBVIEW_STATE, state)
    }

    /** Restores history and the current page after recreation; false if there was nothing to restore. */
    fun restoreState(savedInstanceState: Bundle?): Boolean {
        val state = savedInstanceState?.getBundle(KEY_WEBVIEW_STATE) ?: return false
        return webView.restoreState(state) != null
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private companion object {
        const val KEY_WEBVIEW_STATE = "webview_state"
    }
}
