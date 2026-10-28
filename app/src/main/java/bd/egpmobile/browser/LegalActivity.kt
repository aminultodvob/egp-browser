package bd.egpmobile.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import bd.egpmobile.browser.databinding.ActivityLegalBinding

/**
 * Shows a bundled policy document (assets/legal/<doc>.html) as formatted text. Publisher details are
 * filled in from build settings; missing values are shown as an explicit placeholder.
 */
class LegalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val binding = ActivityLegalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val doc = intent.getStringExtra(EXTRA_DOC)?.takeIf { it in DOCS } ?: DOC_PRIVACY
        binding.toolbar.setTitle(TITLES.getValue(doc))
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationContentDescription(R.string.action_navigate_up)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val html = runCatching { assets.open("legal/$doc.html").bufferedReader().use { it.readText() } }
            .getOrDefault("<p>${getString(R.string.legal_missing)}</p>")
        binding.content.text = HtmlCompat.fromHtml(fillPlaceholders(html), HtmlCompat.FROM_HTML_MODE_COMPACT)
        binding.content.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun fillPlaceholders(html: String): String {
        val missing = getString(R.string.legal_placeholder)
        return html
            .replace("{{DEVELOPER_NAME}}", BuildConfig.DEVELOPER_NAME.ifBlank { missing })
            .replace("{{SUPPORT_EMAIL}}", BuildConfig.SUPPORT_EMAIL.ifBlank { missing })
            .replace("{{WEBSITE}}", BuildConfig.PUBLISHER_WEBSITE.ifBlank { missing })
            .replace("{{EFFECTIVE_DATE}}", BuildConfig.POLICY_EFFECTIVE_DATE.ifBlank { missing })
            .replace("{{VERSION}}", BuildConfig.VERSION_NAME)
    }

    companion object {
        const val DOC_PRIVACY = "privacy"
        const val DOC_TERMS = "terms"
        const val DOC_REFUND = "refund"
        private const val EXTRA_DOC = "doc"
        private val DOCS = setOf(DOC_PRIVACY, DOC_TERMS, DOC_REFUND)
        private val TITLES = mapOf(
            DOC_PRIVACY to R.string.legal_privacy,
            DOC_TERMS to R.string.legal_terms,
            DOC_REFUND to R.string.legal_refund,
        )

        fun intent(context: Context, doc: String): Intent =
            Intent(context, LegalActivity::class.java).putExtra(EXTRA_DOC, doc)
    }
}
