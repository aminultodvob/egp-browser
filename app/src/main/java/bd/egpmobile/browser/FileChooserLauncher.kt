package bd.egpmobile.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Lifecycle-safe bridge between `onShowFileChooser` and the system document picker.
 *
 * Exactly one WebView callback is pending at a time; a new request cancels the previous one and
 * the pending callback is released when the activity is destroyed, so WebView never waits forever.
 * The picker is opened with the any-type filter on purpose: the portal validates file type and size itself, and
 * MIME filters in the picker hide legitimate files whose provider reports a generic MIME type.
 * No storage permission is needed; the picker grants access to the chosen content:// URIs.
 */
class FileChooserLauncher(
    activity: ComponentActivity,
    private val onError: (messageRes: Int) -> Unit,
) : DefaultLifecycleObserver {

    private var pending: ValueCallback<Array<Uri>>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> deliver(parse(result.resultCode, result.data)) }

    init {
        activity.lifecycle.addObserver(this)
    }

    /** Returns true when the picker was opened and [callback] will be invoked exactly once. */
    fun launch(callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        cancelPending()
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return try {
            pending = callback
            launcher.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            pending = null
            onError(R.string.file_chooser_unavailable)
            false
        }
    }

    fun cancelPending() = deliver(null)

    override fun onDestroy(owner: LifecycleOwner) = cancelPending()

    private fun deliver(uris: Array<Uri>?) {
        val callback = pending ?: return
        pending = null
        callback.onReceiveValue(uris)
    }

    private fun parse(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val clip = data.clipData
        val selected = if (clip != null && clip.itemCount > 0) {
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        } else {
            listOfNotNull(data.data)
        }
        // Only content:// URIs from document providers. file:// URIs are refused so a picker app
        // cannot point an upload at this app's private files.
        val accepted = selected.filter { it.scheme == "content" }
        if (selected.isNotEmpty() && accepted.isEmpty()) onError(R.string.file_chooser_unsupported)
        return accepted.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
