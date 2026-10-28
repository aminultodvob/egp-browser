package bd.egpmobile.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URISyntaxException

/** Builds the intent used to hand a non-e-GP link to another app, after the user confirms. */
object ExternalIntents {

    private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    fun build(context: Context, url: String): Intent? {
        val intent = if (url.startsWith("intent:", ignoreCase = true)) {
            try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } catch (e: URISyntaxException) {
                return null
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        // A web page must not be able to target a specific component, grant URI access,
        // or reach this app's own (non-exported) screens.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.flags = intent.flags and GRANT_FLAGS.inv()
        if (intent.`package` == context.packageName) return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}
