package bd.egpmobile.browser

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the security-relevant WebView settings and the two rendering profiles on a real WebView. */
@RunWith(AndroidJUnit4::class)
class WebViewConfigurationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun <T> onWebView(block: (WebView, BrowserController) -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync {
            val webView = WebView(context)
            val controller = BrowserController(webView, BrowserSettings(context))
            controller.configure(webView)
            result = block(webView, controller)
            controller.destroy()
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun leastPrivilegeSettings() = onWebView { webView, _ ->
        val s = webView.settings
        assertTrue(s.javaScriptEnabled)
        assertTrue(s.domStorageEnabled)
        assertFalse(s.allowFileAccess)
        assertFalse(s.allowContentAccess)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, s.mixedContentMode)
        assertTrue(s.safeBrowsingEnabled)
        assertTrue(s.supportMultipleWindows())
        assertTrue(s.builtInZoomControls)
        assertFalse(s.displayZoomControls)
    }

    @Test
    fun desktopModeUsesDesktopProfile() = onWebView { webView, controller ->
        controller.applyRenderingMode(webView, desktop = true)
        val ua = webView.settings.userAgentString
        assertTrue(ua.contains("X11; Linux x86_64"))
        assertFalse(ua.contains("Mobile"))
        assertFalse(ua.contains("; wv"))
        assertTrue(webView.settings.useWideViewPort)
        assertTrue(webView.settings.loadWithOverviewMode)
    }

    @Test
    fun mobileModeRestoresWebViewDefaults() = onWebView { webView, controller ->
        val default = WebSettings.getDefaultUserAgent(context)
        controller.applyRenderingMode(webView, desktop = true)
        assertNotEquals(default, webView.settings.userAgentString)
        controller.applyRenderingMode(webView, desktop = false)
        assertEquals(default, webView.settings.userAgentString)
        assertFalse(webView.settings.useWideViewPort)
        assertFalse(webView.settings.loadWithOverviewMode)
    }

    @Test
    fun controllerRefusesUntrustedUrls() = onWebView { _, controller ->
        assertTrue(controller.load("https://example.com/") is UrlValidator.Decision.External)
        assertTrue(controller.load("javascript:alert(1)") is UrlValidator.Decision.Block)
        assertTrue(controller.load("file:///sdcard/a.html") is UrlValidator.Decision.Block)
        assertEquals(BrowserController.InputResult.Invalid, controller.navigateFromInput("two words"))
        assertTrue(controller.navigateFromInput("example.com") is BrowserController.InputResult.External)
    }
}
