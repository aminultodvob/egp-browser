package bd.egpmobile.browser

import android.content.Context
import android.content.pm.ActivityInfo
import android.webkit.WebSettings
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/** Typed access to the user's browser preferences (keys match res/xml/preferences.xml). */
class BrowserSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, true)
        set(value) = prefs.edit { putBoolean(KEY_DESKTOP_MODE, value) }

    val textZoom: Int
        get() = prefs.getString(KEY_TEXT_ZOOM, "100")?.toIntOrNull()?.coerceIn(50, 200) ?: 100

    val orientation: Int
        get() = when (prefs.getString(KEY_ORIENTATION, ORIENTATION_AUTO)) {
            ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

    val thirdPartyCookies: Boolean
        get() = prefs.getBoolean(KEY_THIRD_PARTY_COOKIES, false)

    /** Official hosts plus hosts the user verified and added (e.g. a payment provider). */
    val trustedHosts: Set<String>
        get() = UrlValidator.DEFAULT_TRUSTED_HOSTS +
            UrlValidator.parseHostList(prefs.getString(KEY_EXTRA_TRUSTED_HOSTS, "").orEmpty())

    /** The user agent the browser currently sends; also used for downloads made outside the WebView. */
    fun userAgent(): String {
        val default = WebSettings.getDefaultUserAgent(appContext)
        return if (desktopMode) UserAgentProfile.desktop(default) else default
    }

    companion object {
        const val KEY_DESKTOP_MODE = "desktop_mode"
        const val KEY_TEXT_ZOOM = "text_zoom"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_THIRD_PARTY_COOKIES = "third_party_cookies"
        const val KEY_EXTRA_TRUSTED_HOSTS = "extra_trusted_hosts"
        const val KEY_CLEAR_DATA = "clear_data"
        const val KEY_VERSION = "version"

        const val ORIENTATION_AUTO = "auto"
        const val ORIENTATION_PORTRAIT = "portrait"
        const val ORIENTATION_LANDSCAPE = "landscape"
    }
}
