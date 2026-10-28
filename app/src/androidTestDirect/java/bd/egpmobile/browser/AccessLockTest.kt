package bd.egpmobile.browser

import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Direct build without access: the portal must not load, whichever control is used. */
@RunWith(AndroidJUnit4::class)
class AccessLockTest {

    @Before
    fun signedOut() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("secure_session", 0).edit().clear().commit()
    }

    @Test
    fun lockedBrowserLoadsNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(3_000)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.lockedView).visibility)
                activity.findViewById<View>(R.id.buttonHome).performClick()
                activity.findViewById<View>(R.id.buttonReload).performClick()
                val address = activity.findViewById<EditText>(R.id.addressBar)
                address.setText("https://www.eprocure.gov.bd/FAQ.jsp")
                address.onEditorAction(EditorInfo.IME_ACTION_GO)
            }
            SystemClock.sleep(5_000)
            scenario.onActivity { activity ->
                val url = activity.webView.url
                assertTrue("Portal loaded while locked: $url", url == null || url == "about:blank")
            }
        }
    }
}
