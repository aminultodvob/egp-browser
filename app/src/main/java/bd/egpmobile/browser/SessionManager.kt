package bd.egpmobile.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase

/** Cookies, cache, history and site-data clearing. Every clear is confirmed by the UI first. */
class SessionManager {

    data class ClearOptions(
        val cache: Boolean = false,
        /** Cookies end the e-GP session on this device. */
        val cookies: Boolean = false,
        val history: Boolean = false,
        /** DOM storage, saved HTTP-auth entries and form autocomplete data. */
        val siteData: Boolean = false,
    ) {
        val signsOut: Boolean get() = cookies || siteData
        val isEmpty: Boolean get() = !cache && !cookies && !history && !siteData
    }

    fun clear(webView: WebView, options: ClearOptions, onComplete: () -> Unit) {
        if (options.cache) webView.clearCache(true)
        if (options.history) webView.clearHistory()
        if (options.siteData) {
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(webView.context).clearHttpAuthUsernamePassword()
            webView.clearFormData()
        }
        if (options.cookies) {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies {
                cookies.flush()
                onComplete()
            }
        } else {
            onComplete()
        }
    }

    /**
     * Ends the session on this device. The portal's own Logout button should be used first so the
     * server invalidates the session too; this app does not call any e-GP endpoint itself.
     */
    fun clearSession(webView: WebView, onComplete: () -> Unit) =
        clear(webView, SIGN_OUT, onComplete)

    /** Writes in-memory cookies to disk so an ordinary process restart keeps the session. */
    fun persist() = CookieManager.getInstance().flush()

    companion object {
        val SIGN_OUT = ClearOptions(cookies = true, history = true, siteData = true)
    }
}
