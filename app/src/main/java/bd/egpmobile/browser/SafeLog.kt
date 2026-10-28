package bd.egpmobile.browser

import android.util.Log

/**
 * The only logging entry point in the app. Logs are written in debug builds only, and callers
 * must pass URLs through [UrlValidator.redact]. Cookies, headers, form values and full URLs are
 * never logged. Release builds additionally strip Log.v/d/i calls with R8 (proguard-rules.pro).
 */
object SafeLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        // Only the exception type is logged: exception messages can contain URLs.
        if (BuildConfig.DEBUG) Log.w(tag, if (error == null) message else "$message (${error.javaClass.simpleName})")
    }
}
