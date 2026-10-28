package bd.egpmobile.browser

import bd.egpmobile.browser.UrlValidator.BlockReason
import bd.egpmobile.browser.UrlValidator.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    private val trusted = UrlValidator.DEFAULT_TRUSTED_HOSTS

    private fun decide(url: String) = UrlValidator.decide(url, trusted)

    @Test
    fun homepageIsAllowed() {
        assertEquals(Decision.Allow(UrlValidator.HOME_URL), decide(UrlValidator.HOME_URL))
    }

    @Test
    fun explicitDefaultPortFromPortalRedirectIsAllowed() {
        // The portal's HTTP -> HTTPS redirect points at https://www.eprocure.gov.bd:443/
        assertTrue(decide("https://www.eprocure.gov.bd:443/") is Decision.Allow)
    }

    @Test
    fun portalPagesWithQueriesAreAllowed() {
        assertTrue(decide("https://www.eprocure.gov.bd/resources/common/AllTenders.jsp?h=t") is Decision.Allow)
        assertTrue(decide("https://eprocure.gov.bd/ViewNewsDetails.jsp?newsType=A&nId=41") is Decision.Allow)
        assertTrue(decide("HTTPS://WWW.EPROCURE.GOV.BD/Index.jsp") is Decision.Allow)
    }

    @Test
    fun httpPortalLinksAreUpgradedToHttps() {
        assertEquals(Decision.Upgrade("https://www.eprocure.gov.bd/"), decide("http://www.eprocure.gov.bd"))
        assertEquals(
            Decision.Upgrade("https://www.eprocure.gov.bd/help/manuals/eGP_NewUserRegManual_English.pdf"),
            decide("http://www.eprocure.gov.bd/help/manuals/eGP_NewUserRegManual_English.pdf")
        )
    }

    @Test
    fun nonStandardPortsAreNotTrusted() {
        assertTrue(decide("https://www.eprocure.gov.bd:8443/") is Decision.External)
        assertTrue(decide("http://www.eprocure.gov.bd:8080/") is Decision.External)
    }

    @Test
    fun otherSitesAreExternal() {
        assertTrue(decide("https://www.bppa.gov.bd/") is Decision.External)
        assertTrue(decide("http://www.lged.gov.bd/") is Decision.External)
        assertTrue(decide("https://example.com/") is Decision.External)
    }

    @Test
    fun lookAlikeHostsAreNotTrusted() {
        assertTrue(decide("https://eprocure.gov.bd.example.com/") is Decision.External)
        assertTrue(decide("https://evil-eprocure.gov.bd/") is Decision.External)
        assertTrue(decide("https://www.eprocure.gov.bd.evil.com/login") is Decision.External)
        assertTrue(decide("https://xeprocure.gov.bd/") is Decision.External)
    }

    @Test
    fun credentialsInUrlAreBlocked() {
        assertEquals(Decision.Block(BlockReason.CREDENTIALS_IN_URL), decide("https://www.eprocure.gov.bd@evil.com/"))
        assertEquals(Decision.Block(BlockReason.CREDENTIALS_IN_URL), decide("https://user:pass@www.eprocure.gov.bd/"))
    }

    @Test
    fun backslashTricksAreRejected() {
        assertTrue(decide("https://evil.com\\@www.eprocure.gov.bd/") is Decision.Block)
        assertTrue(decide("https://www.eprocure.gov.bd\\.evil.com/") is Decision.Block)
    }

    @Test
    fun dangerousSchemesAreBlocked() {
        listOf(
            "javascript:alert(1)",
            "file:///data/data/bd.egpmobile.browser/shared_prefs/x.xml",
            "content://com.example.provider/secret",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://www.eprocure.gov.bd/1234",
            "ftp://www.eprocure.gov.bd/",
        ).forEach { url ->
            assertEquals(url, Decision.Block(BlockReason.UNSUPPORTED_SCHEME), decide(url))
        }
    }

    @Test
    fun handOffSchemesAreExternal() {
        assertTrue(decide("mailto:helpdesk@example.com") is Decision.External)
        assertTrue(decide("tel:+880000000") is Decision.External)
        assertTrue(decide("intent://scan/#Intent;scheme=zxing;end") is Decision.External)
    }

    @Test
    fun aboutBlankIsAllowedForPopups() {
        assertTrue(decide("about:blank") is Decision.Allow)
        assertTrue(decide("about:config") is Decision.Block)
    }

    @Test
    fun malformedUrlsAreBlocked() {
        assertEquals(Decision.Block(BlockReason.INVALID_URL), decide("not a url"))
        assertEquals(Decision.Block(BlockReason.INVALID_URL), decide("https://"))
        assertEquals(Decision.Block(BlockReason.INVALID_URL), decide("https://[::1]/"))
        assertEquals(Decision.Block(BlockReason.INVALID_URL), decide("https://www.eprocure.gov.bd:99999/"))
    }

    @Test
    fun userAddedHostsAreTrustedExactly() {
        val hosts = trusted + "pay.example.com.bd"
        assertTrue(UrlValidator.decide("https://pay.example.com.bd/checkout", hosts) is Decision.Allow)
        assertTrue(UrlValidator.decide("https://sub.pay.example.com.bd/", hosts) is Decision.External)
    }

    @Test
    fun trustedSecureUrlRequiresHttps() {
        assertTrue(UrlValidator.isTrustedSecureUrl("https://www.eprocure.gov.bd/Index.jsp", trusted))
        assertFalse(UrlValidator.isTrustedSecureUrl("http://www.eprocure.gov.bd/", trusted))
        assertFalse(UrlValidator.isTrustedSecureUrl("about:blank", trusted))
        assertFalse(UrlValidator.isTrustedSecureUrl("https://example.com/", trusted))
        assertFalse(UrlValidator.isTrustedSecureUrl(null, trusted))
    }

    @Test
    fun addressBarInputIsNormalized() {
        assertEquals("https://www.eprocure.gov.bd", UrlValidator.normalizeInput("www.eprocure.gov.bd"))
        assertEquals("https://eprocure.gov.bd/Index.jsp", UrlValidator.normalizeInput("  eprocure.gov.bd/Index.jsp "))
        assertEquals(
            "https://www.eprocure.gov.bd/resources/common/AllTenders.jsp",
            UrlValidator.normalizeInput("/resources/common/AllTenders.jsp")
        )
        assertEquals("https://www.eprocure.gov.bd:443/", UrlValidator.normalizeInput("www.eprocure.gov.bd:443/"))
        assertEquals("http://www.eprocure.gov.bd/", UrlValidator.normalizeInput("http://www.eprocure.gov.bd/"))
        assertEquals("javascript:alert(1)", UrlValidator.normalizeInput("javascript:alert(1)"))
        assertNull(UrlValidator.normalizeInput(""))
        assertNull(UrlValidator.normalizeInput("tender search"))
        assertNull(UrlValidator.normalizeInput("bad host!"))
    }

    @Test
    fun redactRemovesQueriesFragmentsAndSessionIds() {
        assertEquals(
            "https://www.eprocure.gov.bd/LoginSrBean",
            UrlValidator.redact("https://www.eprocure.gov.bd/LoginSrBean?action=checkLogin&token=secret#top")
        )
        assertEquals(
            "https://www.eprocure.gov.bd/Index.jsp",
            UrlValidator.redact("https://www.eprocure.gov.bd/Index.jsp;jsessionid=ABC123")
        )
        assertEquals("https://www.eprocure.gov.bd/", UrlValidator.redact("https://user:pw@www.eprocure.gov.bd/"))
        assertEquals("mailto:", UrlValidator.redact("mailto:someone@example.com"))
        assertEquals("", UrlValidator.redact(null))
    }

    @Test
    fun hostListParsing() {
        assertEquals(
            setOf("pay.example.com.bd", "sso.example.org"),
            UrlValidator.parseHostList(" Pay.Example.com.bd, sso.example.org\n")
        )
        assertEquals(listOf("*.example.com", "https://x.com"), UrlValidator.invalidHosts("*.example.com, ok.com, https://x.com"))
        assertTrue(UrlValidator.parseHostList("").isEmpty())
    }
}
