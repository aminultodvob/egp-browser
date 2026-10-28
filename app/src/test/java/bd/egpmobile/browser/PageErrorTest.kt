package bd.egpmobile.browser

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageErrorTest {

    @Test
    fun mapsWebViewErrors() {
        assertEquals(PageError.HOST_NOT_FOUND, PageError.fromWebViewError(WebViewClient.ERROR_HOST_LOOKUP, offline = false))
        assertEquals(PageError.OFFLINE, PageError.fromWebViewError(WebViewClient.ERROR_HOST_LOOKUP, offline = true))
        assertEquals(PageError.TIMEOUT, PageError.fromWebViewError(WebViewClient.ERROR_TIMEOUT, offline = false))
        assertEquals(PageError.CONNECTION_FAILED, PageError.fromWebViewError(WebViewClient.ERROR_CONNECT, offline = false))
        assertEquals(PageError.SSL, PageError.fromWebViewError(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, offline = false))
        assertEquals(PageError.UNSAFE, PageError.fromWebViewError(WebViewClient.ERROR_UNSAFE_RESOURCE, offline = false))
        assertEquals(PageError.GENERIC, PageError.fromWebViewError(WebViewClient.ERROR_UNKNOWN, offline = false))
    }

    @Test
    fun onlyNetworkErrorsAutoRetry() {
        assertTrue(PageError.OFFLINE.isNetworkRelated)
        assertTrue(PageError.TIMEOUT.isNetworkRelated)
        assertFalse(PageError.SSL.isNetworkRelated)
        assertFalse(PageError.UNSAFE.isNetworkRelated)
    }
}
