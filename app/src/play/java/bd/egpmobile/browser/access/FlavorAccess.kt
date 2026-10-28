package bd.egpmobile.browser.access

import android.content.Context

/** Google Play build: free, no account, trial or payment. */
object FlavorAccess {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): AccessGate = FreeAccessGate
}
