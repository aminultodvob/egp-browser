package bd.egpmobile.browser.access

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Interop test: the fixture tokens were signed by the backend's real EntitlementSigner (jose, ES256),
 * generated with backend/scripts/make-fixture.ts.
 */
class EntitlementVerifierTest {

    private val fixture = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("entitlement-fixture.json")!!.bufferedReader().use { it.readText() }
    )
    private val verifier = EntitlementVerifier(fixture.getString("publicKey"))

    @Test
    fun acceptsBackendSignedLifetimeToken() {
        val claims = verifier.verify(fixture.getString("lifetime"))
        assertNotNull(claims)
        assertEquals(AccessPolicy.STATE_LIFETIME, claims!!.state)
        assertEquals(fixture.getString("userId"), claims.userId)
        assertEquals(fixture.getLong("serverTimeMs"), claims.issuedAtMs)
        // Lifetime entitlements may be used offline for 30 days.
        assertEquals(30L * 86_400_000, claims.validUntilMs - claims.issuedAtMs)
        assertNull(claims.trialExpiresAtMs)
    }

    @Test
    fun acceptsBackendSignedTrialToken() {
        val claims = verifier.verify(fixture.getString("trial"))!!
        assertEquals(AccessPolicy.STATE_TRIAL_ACTIVE, claims.state)
        assertEquals(fixture.getLong("serverTimeMs") + 7L * 86_400_000, claims.trialExpiresAtMs)
    }

    @Test
    fun rejectsTamperedPayload() {
        val parts = fixture.getString("trial").split('.')
        val payload = JSONObject(String(Base64.getUrlDecoder().decode(parts[1])))
        payload.put("st", "LIFETIME")
        val forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
        assertNull(verifier.verify("${parts[0]}.$forgedPayload.${parts[2]}"))
    }

    @Test
    fun rejectsTokensFromAnotherKey() {
        assertNull(EntitlementVerifier(fixture.getString("otherPublicKey")).verify(fixture.getString("lifetime")))
    }

    @Test
    fun rejectsAlgNoneAndGarbage() {
        val parts = fixture.getString("lifetime").split('.')
        val noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        assertNull(verifier.verify("$noneHeader.${parts[1]}."))
        assertNull(verifier.verify("$noneHeader.${parts[1]}.${parts[2]}"))
        assertNull(verifier.verify("not-a-token"))
        assertNull(verifier.verify(""))
    }

    @Test
    fun unconfiguredKeyVerifiesNothing() {
        val empty = EntitlementVerifier("")
        assertFalse(empty.isConfigured)
        assertNull(empty.verify(fixture.getString("lifetime")))
        assertTrue(verifier.isConfigured)
    }
}
