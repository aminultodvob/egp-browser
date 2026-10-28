package bd.egpmobile.browser.access

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessPolicyTest {

    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L

    private fun claims(state: String, trialExpires: Long? = null, validUntil: Long = t0 + 30 * day, user: String = "u1") =
        EntitlementClaims(user, state, trialExpires, t0, validUntil)

    @Test
    fun signedOutIsLocked() {
        assertEquals(AccessDecision.Locked(LockReason.SIGN_IN_REQUIRED), AccessPolicy.decide(null, null, t0))
    }

    @Test
    fun missingOrForeignEntitlementNeedsOnlineCheck() {
        assertEquals(AccessDecision.Locked(LockReason.VERIFY_ONLINE), AccessPolicy.decide("u1", null, t0))
        assertEquals(
            AccessDecision.Locked(LockReason.VERIFY_ONLINE),
            AccessPolicy.decide("u1", claims(AccessPolicy.STATE_LIFETIME, user = "someone-else"), t0),
        )
    }

    @Test
    fun lifetimeWorksOfflineUntilItsValidityThenNeedsOneOnlineCheck() {
        val c = claims(AccessPolicy.STATE_LIFETIME)
        assertEquals(AccessDecision.Allowed, AccessPolicy.decide("u1", c, t0 + 29 * day))
        assertEquals(AccessDecision.Locked(LockReason.VERIFY_ONLINE), AccessPolicy.decide("u1", c, t0 + 31 * day))
    }

    @Test
    fun trialEndsAtServerRecordedExpiry() {
        val c = claims(AccessPolicy.STATE_TRIAL_ACTIVE, trialExpires = t0 + 7 * day)
        assertEquals(AccessDecision.Allowed, AccessPolicy.decide("u1", c, t0 + 7 * day - 1))
        assertEquals(AccessDecision.Locked(LockReason.TRIAL_EXPIRED), AccessPolicy.decide("u1", c, t0 + 7 * day))
    }

    @Test
    fun lockedStates() {
        assertEquals(AccessDecision.Locked(LockReason.TRIAL_NOT_STARTED), AccessPolicy.decide("u1", claims(AccessPolicy.STATE_TRIAL_NOT_STARTED), t0))
        assertEquals(AccessDecision.Locked(LockReason.TRIAL_EXPIRED), AccessPolicy.decide("u1", claims(AccessPolicy.STATE_TRIAL_EXPIRED), t0))
        assertEquals(AccessDecision.Locked(LockReason.VERIFY_ONLINE), AccessPolicy.decide("u1", claims("SOMETHING_NEW"), t0))
    }

    @Test
    fun trialDaysLeftRoundsUp() {
        val c = claims(AccessPolicy.STATE_TRIAL_ACTIVE, trialExpires = t0 + 7 * day)
        assertEquals(7, AccessPolicy.trialDaysLeft(c, t0))
        assertEquals(1, AccessPolicy.trialDaysLeft(c, t0 + 6 * day + 1))
        assertEquals(0, AccessPolicy.trialDaysLeft(c, t0 + 8 * day))
    }
}

class TrustedClockTest {

    private var elapsed = 1_000L
    private var wall = 0L
    private var boot = 5
    private val clock = TrustedClock({ elapsed }, { wall }, { boot })

    @Test
    fun followsServerTimePlusMonotonicElapsed() {
        clock.sync(serverTimeMs = 1_000_000)
        wall = 5_000_000 // device clock is wrong: ignored
        elapsed += 60_000
        assertEquals(1_060_000, clock.now())
    }

    @Test
    fun windingTheDeviceClockBackDoesNotRewindTime() {
        clock.sync(serverTimeMs = 1_000_000)
        elapsed += 10_000
        assertEquals(1_010_000, clock.now())
        // Reboot (new boot count) and set the clock back by a day.
        boot = 6
        elapsed = 500
        wall = 1_000_000 - 86_400_000
        assertEquals(1_010_000, clock.now())
    }

    @Test
    fun rebootWithLongUptimeIsDetectedByBootCount() {
        clock.sync(serverTimeMs = 1_000_000)
        boot = 6
        elapsed = 10_000_000 // larger than at sync, but from a new boot: must not be trusted
        wall = 2_000_000
        assertEquals(2_000_000, clock.now())
    }

    @Test
    fun beforeAnySyncUsesDeviceClock() {
        wall = 42_000
        assertEquals(42_000, clock.now())
    }
}
