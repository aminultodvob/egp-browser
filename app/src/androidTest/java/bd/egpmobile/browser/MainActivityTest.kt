package bd.egpmobile.browser

import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI and lifecycle checks that do not depend on the portal being reachable.
 * Network-dependent checks live in [PortalSmokeTest].
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Test
    fun launchesWithBrowserControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                listOf(R.id.addressBar, R.id.buttonBack, R.id.buttonForward, R.id.buttonHome, R.id.buttonReload, R.id.buttonMenu)
                    .forEach { id -> assertEquals(View.VISIBLE, activity.findViewById<View>(id).visibility) }
                listOf(R.id.buttonBack, R.id.buttonForward, R.id.buttonHome, R.id.buttonReload, R.id.buttonMenu)
                    .forEach { id -> assertNotNull(activity.findViewById<View>(id).contentDescription) }
            }
        }
    }

    @Test
    fun untrustedAddressIsNotLoadedInApp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val address = activity.findViewById<EditText>(R.id.addressBar)
                address.requestFocus()
                address.setText("https://example.com/")
                address.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(activity.webView.url.orEmpty().contains("example.com"))
            }
        }
    }

    @Test
    fun rotationKeepsTheSameWebView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var before: Any? = null
            scenario.onActivity { activity ->
                before = activity.webView
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                // configChanges keeps the activity, so forms are not reloaded on rotation.
                assertTrue(before === activity.webView)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    @Test
    fun recreationRestoresAndBackKeyDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity -> activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)) }
        }
    }
}
