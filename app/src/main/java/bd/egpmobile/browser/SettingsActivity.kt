package bd.egpmobile.browser

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import bd.egpmobile.browser.databinding.ActivitySettingsBinding

/** Browser preferences. Clearing data is delegated back to MainActivity, which owns the WebView. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        binding.toolbar.setTitle(R.string.settings_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationContentDescription(R.string.action_navigate_up)
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>(BrowserSettings.KEY_EXTRA_TRUSTED_HOSTS)?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val invalid = UrlValidator.invalidHosts(newValue as String)
                    if (invalid.isNotEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.pref_extra_hosts_invalid, invalid.joinToString(", ")),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    invalid.isEmpty()
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                    val hosts = UrlValidator.parseHostList(preference.text.orEmpty())
                    if (hosts.isEmpty()) getString(R.string.pref_extra_hosts_none) else hosts.joinToString(", ")
                }
            }

            findPreference<Preference>(BrowserSettings.KEY_CLEAR_DATA)?.setOnPreferenceClickListener {
                requireActivity().apply {
                    setResult(RESULT_CLEAR_DATA)
                    finish()
                }
                true
            }

            findPreference<Preference>(KEY_PRIVACY)?.setOnPreferenceClickListener {
                startActivity(LegalActivity.intent(requireContext(), LegalActivity.DOC_PRIVACY))
                true
            }
            findPreference<Preference>(KEY_TERMS)?.setOnPreferenceClickListener {
                startActivity(LegalActivity.intent(requireContext(), LegalActivity.DOC_TERMS))
                true
            }

            findPreference<Preference>(BrowserSettings.KEY_VERSION)?.summary =
                getString(R.string.pref_version_summary, BuildConfig.VERSION_NAME)
        }
    }

    companion object {
        const val RESULT_CLEAR_DATA = RESULT_FIRST_USER + 1
        private const val KEY_PRIVACY = "privacy"
        private const val KEY_TERMS = "terms"
    }
}
