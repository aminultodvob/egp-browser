package bd.egpmobile.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.webkit.ClientCertRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Navigation policy, HTTPS enforcement and error reporting for one WebView (main or pop-up).
 *
 * Two layers enforce the allowlist: [shouldOverrideUrlLoading] handles link clicks and server
 * redirects, and [shouldInterceptRequest] refuses any other main-frame load (history navigation,
 * pop-up initial loads) that is not a trusted HTTPS URL.
 */
class BrowserWebViewClient(
    private val host: Host,
    private val trustedHosts: () -> Set<String>,
) : WebViewClient() {

    interface Host {
        fun onNavigationStarted(view: WebView, url: String)
        fun onNavigationFinished(view: WebView, url: String)
        fun onHistoryChanged(view: WebView, url: String)
        fun onMainFrameError(view: WebView, errorCode: Int)
        fun onMainFrameHttpError(view: WebView, statusCode: Int)
        fun onSslError(view: WebView, url: String?)
        fun onExternalNavigation(view: WebView, url: String)
        fun onNavigationBlocked(view: WebView, reason: UrlValidator.BlockReason)
        fun onFormResubmission(view: WebView, dontResend: Message, resend: Message)
        fun onClientCertRequest(view: WebView, request: ClientCertRequest)
        fun onRenderProcessGone(view: WebView): Boolean
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        return when (val decision = UrlValidator.decide(request.url.toString(), trustedHosts())) {
            is UrlValidator.Decision.Allow -> false
            is UrlValidator.Decision.Upgrade -> {
                view.loadUrl(decision.httpsUrl)
                true
            }
            is UrlValidator.Decision.External -> {
                host.onExternalNavigation(view, decision.url)
                true
            }
            is UrlValidator.Decision.Block -> {
                host.onNavigationBlocked(view, decision.reason)
                true
            }
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!request.isForMainFrame) return null
        val decision = UrlValidator.decide(request.url.toString(), trustedHosts())
        if (decision is UrlValidator.Decision.Allow) return null
        // Called on a WebView worker thread: hand the follow-up to the UI thread.
        view.post {
            when (decision) {
                is UrlValidator.Decision.Upgrade -> view.loadUrl(decision.httpsUrl)
                is UrlValidator.Decision.External -> host.onExternalNavigation(view, decision.url)
                is UrlValidator.Decision.Block -> host.onNavigationBlocked(view, decision.reason)
                is UrlValidator.Decision.Allow -> Unit
            }
        }
        return blockedResponse()
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        host.onNavigationStarted(view, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        host.onNavigationFinished(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        host.onHistoryChanged(view, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val description = error.description?.toString().orEmpty()
        // Cancelled loads (Stop, a new navigation, or our own blocking) are not errors.
        if (description.contains("ERR_ABORTED") || error.errorCode == ERROR_UNSUPPORTED_SCHEME) return
        host.onMainFrameError(view, error.errorCode)
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (!request.isForMainFrame || errorResponse.reasonPhrase == BLOCKED_REASON) return
        host.onMainFrameHttpError(view, errorResponse.statusCode)
    }

    /** Certificate errors are always refused. There is deliberately no way to proceed. */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        host.onSslError(view, error.url)
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        host.onFormResubmission(view, dontResend, resend)
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        host.onClientCertRequest(view, request)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
        host.onRenderProcessGone(view)

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "utf-8", 403, BLOCKED_REASON, emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    companion object {
        const val BLOCKED_REASON = "Blocked by e-GP Browser"
    }
}
