package bd.egpmobile.browser

import android.os.Message
import android.os.SystemClock
import android.webkit.ClientCertRequest
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only checks against the live public portal, using the app's real browser components
 * (BrowserController, BrowserWebViewClient, UrlValidator) on a WebView. They need internet access,
 * only open public pages and never sign in or submit anything. The browser screen itself stays locked
 * until sign-in, so it is not used here (AccessLockTest covers the lock).
 */
@RunWith(AndroidJUnit4::class)
class PortalSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView
    private lateinit var controller: BrowserController

    /** Records only what the tests need; everything else is a no-op. */
    private val host = object : BrowserWebViewClient.Host {
        override fun onNavigationStarted(view: WebView, url: String) = Unit
        override fun onNavigationFinished(view: WebView, url: String) = Unit
        override fun onHistoryChanged(view: WebView, url: String) = Unit
        override fun onMainFrameError(view: WebView, errorCode: Int) = Unit
        override fun onMainFrameHttpError(view: WebView, statusCode: Int) = Unit
        override fun onSslError(view: WebView, url: String?) = Unit
        override fun onExternalNavigation(view: WebView, url: String) = Unit
        override fun onNavigationBlocked(view: WebView, reason: UrlValidator.BlockReason) = Unit
        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) { dontResend.sendToTarget() }
        override fun onClientCertRequest(view: WebView, request: ClientCertRequest) = request.cancel()
        override fun onRenderProcessGone(view: WebView): Boolean = true
    }

    @Before
    fun setUp() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        webView = WebView(context)
        controller = BrowserController(webView, BrowserSettings(context))
        controller.configure(webView)
        webView.webViewClient = BrowserWebViewClient(host) { controller.trustedHosts }
    }

    @After
    fun tearDown() = instrumentation.runOnMainSync { controller.destroy() }

    private fun waitForPage(predicate: (String) -> Boolean): String {
        val deadline = SystemClock.uptimeMillis() + 60_000
        var url = ""
        while (SystemClock.uptimeMillis() < deadline) {
            var progress = 0
            instrumentation.runOnMainSync {
                url = webView.url.orEmpty()
                progress = webView.progress
            }
            if (progress == 100 && predicate(url)) return url
            SystemClock.sleep(500)
        }
        return url
    }

    @Test
    fun homepageLoadsOverHttps() {
        instrumentation.runOnMainSync { controller.goHome() }
        val url = waitForPage { it.startsWith("https://www.eprocure.gov.bd") }
        assertTrue("Homepage did not load over HTTPS: ${UrlValidator.redact(url)}", url.startsWith("https://www.eprocure.gov.bd"))
    }

    @Test
    fun httpLinkIsUpgradedAndPublicTenderPageLoads() {
        instrumentation.runOnMainSync { controller.load("http://www.eprocure.gov.bd/resources/common/AllTenders.jsp?h=t") }
        val url = waitForPage { it.contains("AllTenders.jsp") }
        assertTrue(UrlValidator.redact(url), url.startsWith("https://www.eprocure.gov.bd/resources/common/AllTenders.jsp"))
    }

    @Test
    fun backNavigationReturnsToPreviousPage() {
        instrumentation.runOnMainSync { controller.goHome() }
        waitForPage { it.startsWith("https://www.eprocure.gov.bd") }
        instrumentation.runOnMainSync { controller.load("https://www.eprocure.gov.bd/FAQ.jsp") }
        waitForPage { it.contains("FAQ.jsp") }
        instrumentation.runOnMainSync {
            assertTrue(webView.canGoBack())
            controller.goBack()
        }
        val url = waitForPage { !it.contains("FAQ.jsp") }
        assertTrue(!url.contains("FAQ.jsp"))
    }
}
