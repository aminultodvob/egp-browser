package bd.egpmobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentProfileTest {

    private val webViewUa = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP2A.240805.005; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36"

    @Test
    fun desktopProfileUsesInstalledWebViewMajorVersion() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            UserAgentProfile.desktop(webViewUa)
        )
    }

    @Test
    fun desktopProfileHasNoMobileOrWebViewMarkers() {
        val ua = UserAgentProfile.desktop(webViewUa)
        assertFalse(ua.contains("Mobile"))
        assertFalse(ua.contains("; wv"))
        assertFalse(ua.contains("Android"))
        assertFalse(ua.contains("Version/4.0"))
    }

    @Test
    fun fallbackWhenVersionMissing() {
        assertTrue(UserAgentProfile.desktop("Mozilla/5.0").contains("Chrome/${UserAgentProfile.FALLBACK_CHROME_MAJOR}.0.0.0"))
    }

    @Test
    fun chromeVersionMeetsPortalCertifiedMinimum() {
        // BPPA's SystemRequirement page lists Chrome 109+ as tested.
        assertTrue(UserAgentProfile.FALLBACK_CHROME_MAJOR.toInt() >= 109)
        assertTrue(UserAgentProfile.chromeMajorVersion(webViewUa).toInt() >= 109)
    }
}
