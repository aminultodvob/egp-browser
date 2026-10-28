package bd.egpmobile.browser

import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/** File chooser, progress, title, pop-up windows and full-screen video for one WebView. */
class BrowserChromeClient(private val host: Host) : WebChromeClient() {

    interface Host {
        fun onProgress(view: WebView, progress: Int)
        fun onTitle(view: WebView, title: String)
        fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean
        /** Returns a configured pop-up WebView, or null to refuse the window. */
        fun createPopup(opener: WebView): WebView?
        fun closePopup(window: WebView)
        fun showCustomView(view: View, callback: CustomViewCallback)
        fun hideCustomView()
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) = host.onProgress(view, newProgress)

    override fun onReceivedTitle(view: WebView, title: String?) = host.onTitle(view, title.orEmpty())

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = host.showFileChooser(filePathCallback, fileChooserParams)

    /**
     * `target=_blank` links and `window.open` get a real child window, so pages that rely on
     * `window.opener` keep working. The child uses the same navigation policy as the main window.
     */
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        val popup = host.createPopup(view) ?: return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView) = host.closePopup(window)

    override fun onShowCustomView(view: View, callback: CustomViewCallback) = host.showCustomView(view, callback)

    override fun onHideCustomView() = host.hideCustomView()

    /** The portal needs no camera, microphone or location access. */
    override fun onPermissionRequest(request: PermissionRequest) = request.deny()

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
        callback.invoke(origin, false, false)

    /** Page console output can contain form data or tokens; it is never written to logcat. */
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
}
