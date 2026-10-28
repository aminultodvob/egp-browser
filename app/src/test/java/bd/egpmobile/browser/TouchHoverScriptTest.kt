package bd.egpmobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The injected menu script must stay a UI-only helper limited to the official portal.
 * Behaviour is tested against the portal's real menu script in tools/touch-hover-harness.html.
 */
class TouchHoverScriptTest {

    private val script = File("src/main/assets/${TouchHoverSupport.ASSET}").readText()

    @Test
    fun injectedOnlyIntoOfficialPortalOrigins() {
        assertEquals(setOf("https://www.eprocure.gov.bd", "https://eprocure.gov.bd"), TouchHoverSupport.ALLOWED_ORIGINS)
        assertTrue(TouchHoverSupport.isOfficialPortal("https://www.eprocure.gov.bd/resources/common/Inbox.jsp"))
        assertFalse(TouchHoverSupport.isOfficialPortal("http://www.eprocure.gov.bd/"))
        assertFalse(TouchHoverSupport.isOfficialPortal("https://pay.example.com.bd/"))
    }

    @Test
    fun scriptDoesNotTouchDataOrNetwork() {
        val forbidden = listOf(
            "document.cookie", "localStorage", "sessionStorage", "indexedDB", "XMLHttpRequest", "fetch(",
            "sendBeacon", "WebSocket", ".value", "FormData", "postMessage", "eval(", "new Function",
            "innerHTML", "location.href", "window.open", "submit(",
        )
        val found = forbidden.filter { script.contains(it) }
        assertTrue("touch_hover.js must not use: $found", found.isEmpty())
    }

    @Test
    fun scriptGuardsAgainstDoubleInstall() {
        assertTrue(script.contains("if (window.__egpTouchHover) return;"))
    }
}
