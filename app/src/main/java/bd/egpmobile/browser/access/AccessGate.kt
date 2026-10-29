package bd.egpmobile.browser.access

import android.content.Context
import android.content.Intent

/** Whether the browser may be used right now (signed in, and in trial or lifetime access). */
sealed class AccessDecision {
    data object Allowed : AccessDecision()
    data class Locked(val reason: LockReason) : AccessDecision()
}

enum class LockReason {
    /** No account on this device yet. */
    SIGN_IN_REQUIRED,

    /** Signed in, free trial not started. */
    TRIAL_NOT_STARTED,

    /** Trial over and no lifetime access. */
    TRIAL_EXPIRED,

    /** Cached access could not be confirmed offline for too long; needs one online check. */
    VERIFY_ONLINE,
}

/**
 * Implemented by `AppAccess.create`, which checks the server-signed entitlement.
 */
interface AccessGate {
    /** Fast, offline decision from cached state. */
    fun current(): AccessDecision

    /** Refreshes from the server in the background; [onChanged] runs on the main thread. */
    fun refresh(onChanged: () -> Unit)

    /** Screen for account, trial and purchase. */
    fun accountIntent(context: Context): Intent?

    /** Short status for the menu header (e.g. "Trial: 5 days left"), or null. */
    fun statusLine(context: Context): String?
}
