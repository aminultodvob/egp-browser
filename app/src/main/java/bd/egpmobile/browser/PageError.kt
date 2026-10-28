package bd.egpmobile.browser

import android.webkit.WebViewClient

/** Main-frame failures shown on the error screen. */
enum class PageError {
    OFFLINE,
    TIMEOUT,
    HOST_NOT_FOUND,
    CONNECTION_FAILED,
    SSL,
    UNSAFE,
    GENERIC;

    val isNetworkRelated: Boolean
        get() = this == OFFLINE || this == TIMEOUT || this == HOST_NOT_FOUND || this == CONNECTION_FAILED

    companion object {
        fun fromWebViewError(errorCode: Int, offline: Boolean): PageError = when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> if (offline) OFFLINE else HOST_NOT_FOUND
            WebViewClient.ERROR_TIMEOUT -> TIMEOUT
            WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO -> if (offline) OFFLINE else CONNECTION_FAILED
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> SSL
            WebViewClient.ERROR_UNSAFE_RESOURCE -> UNSAFE
            else -> if (offline) OFFLINE else GENERIC
        }
    }
}
