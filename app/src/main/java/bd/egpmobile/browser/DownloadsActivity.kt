package bd.egpmobile.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bd.egpmobile.browser.databinding.ActivityDownloadsBinding
import bd.egpmobile.browser.databinding.ItemDownloadBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Lists downloads started by this app, with Open, Retry and Remove-from-list actions. */
class DownloadsActivity : AppCompatActivity() {

    data class DownloadItem(
        val id: Long,
        val title: String,
        val status: Int,
        val reason: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    )

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var manager: DownloadManager
    private lateinit var store: DownloadStore
    private lateinit var handler: DownloadHandler
    private val settings by lazy { BrowserSettings(this) }
    private val adapter = DownloadsAdapter()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        binding.toolbar.setTitle(R.string.downloads_title)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationContentDescription(R.string.action_navigate_up)
        binding.toolbar.setNavigationOnClickListener { finish() }

        manager = getSystemService(DownloadManager::class.java)
        store = DownloadStore(this)
        handler = DownloadHandler(this) { settings.trustedHosts }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        refreshHandler.post(refreshTask)
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshTask)
        super.onStop()
    }

    private fun refresh() {
        val ids = store.ids()
        val items = if (ids.isEmpty()) emptyList() else query(ids)
        // Forget downloads the user removed in the system Downloads app.
        if (items.size != ids.size) store.retainOnly(items.map { it.id })
        adapter.submitList(items)
        binding.empty.isVisible = items.isEmpty()
    }

    private fun query(ids: List<Long>): List<DownloadItem> {
        val items = mutableListOf<DownloadItem>()
        manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val titleCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val statusCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reasonCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val doneCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (cursor.moveToNext()) {
                items += DownloadItem(
                    id = cursor.getLong(idCol),
                    title = cursor.getString(titleCol).orEmpty(),
                    status = cursor.getInt(statusCol),
                    reason = cursor.getInt(reasonCol),
                    downloadedBytes = cursor.getLong(doneCol),
                    totalBytes = cursor.getLong(totalCol),
                )
            }
        }
        return items.sortedByDescending { it.id }
    }

    private fun open(item: DownloadItem) {
        val uri = manager.getUriForDownloadedFile(item.id)
        if (uri == null) {
            Toast.makeText(this, R.string.download_missing, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, manager.getMimeTypeForDownloadedFile(item.id) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.download_open_with)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.download_no_viewer, Toast.LENGTH_LONG).show()
        }
    }

    private fun retry(item: DownloadItem) {
        val message = when (val result = handler.retry(item.id, settings.userAgent())) {
            is DownloadHandler.Result.Started -> getString(R.string.download_started, result.fileName)
            DownloadHandler.Result.NeedsStoragePermission -> getString(R.string.download_retry_from_page)
            is DownloadHandler.Result.Rejected, DownloadHandler.Result.Failed -> getString(R.string.download_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun remove(item: DownloadItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_remove_title)
            .setMessage(R.string.download_remove_message)
            .setPositiveButton(R.string.download_remove) { _, _ ->
                store.remove(item.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun statusText(item: DownloadItem): String = when (item.status) {
        DownloadManager.STATUS_PENDING -> getString(R.string.download_status_pending)
        DownloadManager.STATUS_RUNNING -> if (item.totalBytes > 0) {
            getString(
                R.string.download_status_running,
                percent(item),
                Formatter.formatShortFileSize(this, item.totalBytes),
            )
        } else {
            getString(R.string.download_status_running_unknown, Formatter.formatShortFileSize(this, item.downloadedBytes))
        }
        DownloadManager.STATUS_PAUSED -> getString(R.string.download_status_paused)
        DownloadManager.STATUS_SUCCESSFUL ->
            getString(R.string.download_status_done, Formatter.formatShortFileSize(this, item.totalBytes.coerceAtLeast(0)))
        DownloadManager.STATUS_FAILED -> failureText(item.reason)
        else -> ""
    }

    private fun failureText(reason: Int): String = when (reason) {
        in 400..599 -> getString(R.string.download_failed_http, reason)
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> getString(R.string.download_failed_space)
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> getString(R.string.download_failed_storage)
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> getString(R.string.download_failed_redirects)
        else -> getString(R.string.download_failed_generic)
    }

    private fun percent(item: DownloadItem): Int =
        if (item.totalBytes > 0) ((item.downloadedBytes * 100) / item.totalBytes).toInt().coerceIn(0, 100) else 0

    private inner class DownloadsAdapter : ListAdapter<DownloadItem, DownloadsAdapter.Holder>(Diff) {

        inner class Holder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = with(holder.binding) {
            val item = getItem(position)
            title.text = item.title.ifEmpty { getString(R.string.download_untitled) }
            status.text = statusText(item)
            val running = item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PENDING
            progress.isVisible = running
            progress.isIndeterminate = running && item.totalBytes <= 0
            if (running && item.totalBytes > 0) progress.setProgressCompat(percent(item), false)
            open.isVisible = item.status == DownloadManager.STATUS_SUCCESSFUL
            retry.isVisible = item.status == DownloadManager.STATUS_FAILED
            open.setOnClickListener { open(item) }
            retry.setOnClickListener { retry(item) }
            remove.setOnClickListener { remove(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem) = oldItem == newItem
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 1000L
    }
}
