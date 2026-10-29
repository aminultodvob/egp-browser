package bd.egpmobile.browser.access

/**
 * Offline access rules (pure, unit tested). The server is authoritative; these rules only decide
 * what to do with the last server-signed entitlement while the app cannot reach the server:
 *  - lifetime access works offline for up to 30 days after the last successful check;
 *  - a trial works until its server-recorded expiry;
 *  - time comes from [TrustedClock], anchored to server time, not the device clock.
 */
object AccessPolicy {

    fun decide(signedInUserId: String?, claims: EntitlementClaims?, trustedNowMs: Long): AccessDecision {
        if (signedInUserId == null) return AccessDecision.Locked(LockReason.SIGN_IN_REQUIRED)
        if (claims == null || claims.userId != signedInUserId) return AccessDecision.Locked(LockReason.VERIFY_ONLINE)
        return when (claims.state) {
            STATE_LIFETIME ->
                if (trustedNowMs < claims.validUntilMs) AccessDecision.Allowed
                else AccessDecision.Locked(LockReason.VERIFY_ONLINE)
            STATE_TRIAL_ACTIVE -> {
                val expiry = claims.trialExpiresAtMs ?: return AccessDecision.Locked(LockReason.VERIFY_ONLINE)
                if (trustedNowMs < expiry) AccessDecision.Allowed else AccessDecision.Locked(LockReason.TRIAL_EXPIRED)
            }
            STATE_TRIAL_NOT_STARTED -> AccessDecision.Locked(LockReason.TRIAL_NOT_STARTED)
            STATE_TRIAL_EXPIRED -> AccessDecision.Locked(LockReason.TRIAL_EXPIRED)
            else -> AccessDecision.Locked(LockReason.VERIFY_ONLINE)
        }
    }

    /** Whole days left in an active trial, rounded up (shown to the user). */
    fun trialDaysLeft(claims: EntitlementClaims, trustedNowMs: Long): Int {
        val remaining = (claims.trialExpiresAtMs ?: return 0) - trustedNowMs
        return if (remaining <= 0) 0 else ((remaining + DAY_MS - 1) / DAY_MS).toInt()
    }

    const val STATE_LIFETIME = "LIFETIME"
    const val STATE_TRIAL_ACTIVE = "TRIAL_ACTIVE"
    const val STATE_TRIAL_NOT_STARTED = "TRIAL_NOT_STARTED"
    const val STATE_TRIAL_EXPIRED = "TRIAL_EXPIRED"
    private const val DAY_MS = 86_400_000L
}

/**
 * Time anchored to the server: server time at the last check plus monotonic time elapsed since.
 * After a reboot the monotonic anchor is lost; then the device clock is used but never earlier
 * than the latest trusted time already observed, so winding the clock back gains nothing.
 */
class TrustedClock(
    private val elapsedRealtime: () -> Long,
    private val wallClock: () -> Long,
    /** Settings.Global.BOOT_COUNT: tells a reboot apart from a long uptime. */
    private val bootCount: () -> Int,
) {
    var serverTimeAtSyncMs: Long = 0
    var elapsedAtSyncMs: Long = 0
    var bootCountAtSync: Int = -1
    var highWaterMarkMs: Long = 0

    fun sync(serverTimeMs: Long) {
        serverTimeAtSyncMs = serverTimeMs
        elapsedAtSyncMs = elapsedRealtime()
        bootCountAtSync = bootCount()
        highWaterMarkMs = maxOf(highWaterMarkMs, serverTimeMs)
    }

    fun now(): Long {
        val elapsed = elapsedRealtime()
        val sameBoot = bootCountAtSync >= 0 && bootCount() == bootCountAtSync
        val estimate = if (serverTimeAtSyncMs > 0 && sameBoot && elapsed >= elapsedAtSyncMs) {
            serverTimeAtSyncMs + (elapsed - elapsedAtSyncMs)
        } else {
            wallClock()
        }
        highWaterMarkMs = maxOf(highWaterMarkMs, estimate)
        return highWaterMarkMs
    }
}
