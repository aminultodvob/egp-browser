package bd.egpmobile.browser.access

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import bd.egpmobile.browser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Direct (website) build: 7-day trial, then ৳250 lifetime unlock. */
object FlavorAccess {
    fun create(context: Context): AccessGate = DirectAccessGate(AccessRepository.get(context))
}

private class DirectAccessGate(private val repository: AccessRepository) : AccessGate {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    override fun current(): AccessDecision = repository.decision()

    /** Network failures keep the cached decision: a verified purchase is never revoked by an outage. */
    override fun refresh(onChanged: () -> Unit) {
        if (repository.signedInUserId == null) return
        scope.launch {
            runCatching { repository.refreshEntitlement() }
            main.post(onChanged)
        }
    }

    override fun accountIntent(context: Context): Intent = Intent(context, AccessActivity::class.java)

    override fun statusLine(context: Context): String? {
        val claims = repository.claims() ?: return null
        return when (claims.state) {
            AccessPolicy.STATE_LIFETIME -> context.getString(R.string.access_status_lifetime)
            AccessPolicy.STATE_TRIAL_ACTIVE -> {
                val days = AccessPolicy.trialDaysLeft(claims, repository.trustedNow())
                context.resources.getQuantityString(R.plurals.access_trial_days_left, days, days)
            }
            else -> context.getString(R.string.access_status_expired)
        }
    }
}
