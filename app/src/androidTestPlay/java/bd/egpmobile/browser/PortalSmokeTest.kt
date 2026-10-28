package bd.egpmobile.browser

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only checks against the live public portal. They need internet access and only open
 * public pages; they never sign in or submit anything.
 */
@RunWith(AndroidJUnit4::class)
class PortalSmokeTest {

    private fun waitForPage(scenario: ActivityScenario<MainActivity>, predicate: (String) -> Boolean): String {
        val deadline = SystemClock.uptimeMillis() + 60_000
        var url = ""
        while (SystemClock.uptimeMillis() < deadline) {
            var progress = 0
            scenario.onActivity {
                url = it.webView.url.orEmpty()
                progress = it.webView.progress
            }
            if (progress == 100 && predicate(url)) return url
            SystemClock.sleep(500)
        }
        return url
    }

    @Test
    fun homepageLoadsOverHttps() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val url = waitForPage(scenario) { it.startsWith("https://www.eprocure.gov.bd") }
            assertTrue("Homepage did not load over HTTPS: ${UrlValidator.redact(url)}", url.startsWith("https://www.eprocure.gov.bd"))
        }
    }

    @Test
    fun httpLinkIsUpgradedAndPublicTenderPageLoads() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForPage(scenario) { it.startsWith("https://www.eprocure.gov.bd") }
            scenario.onActivity { it.webView.loadUrl("http://www.eprocure.gov.bd/resources/common/AllTenders.jsp?h=t") }
            val url = waitForPage(scenario) { it.contains("AllTenders.jsp") }
            assertTrue(UrlValidator.redact(url), url.startsWith("https://www.eprocure.gov.bd/resources/common/AllTenders.jsp"))
        }
    }

    @Test
    fun backNavigationReturnsToPreviousPage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForPage(scenario) { it.startsWith("https://www.eprocure.gov.bd") }
            scenario.onActivity { it.webView.loadUrl("https://www.eprocure.gov.bd/FAQ.jsp") }
            waitForPage(scenario) { it.contains("FAQ.jsp") }
            scenario.onActivity { assertTrue(it.webView.canGoBack()); it.webView.goBack() }
            val url = waitForPage(scenario) { !it.contains("FAQ.jsp") }
            assertTrue(!url.contains("FAQ.jsp"))
        }
    }
}
