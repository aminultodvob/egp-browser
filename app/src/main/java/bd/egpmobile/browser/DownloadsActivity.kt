package bd.egpmobile.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import bd.egpmobile.browser.databinding.ActivityDownloadsBinding
import bd.egpmobile.browser.databinding.ItemDownloadBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Lists this app's downloads with Open (PDFs in the built-in viewer), Retry and Remove-from-list. */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private val engine by lazy { DownloadHandler.Engine.get(this) }
    private val settings by lazy { BrowserSettings(this) }
    private val handler by lazy { DownloadHandler(this) { settings.trustedHosts } }
    private val adapter = DownloadsAdapter()

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
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                refresh()
                engine.events.collect { refresh() }
            }
        }
    }

    private fun refresh() {
        val items = engine.records.all()
        adapter.submitList(items)
        binding.empty.isVisible = items.isEmpty()
    }

    private fun retry(record: DownloadRecord) {
        val message = when (val result = handler.retry(record.id, settings.userAgent())) {
            is DownloadHandler.Result.Started -> getString(R.string.download_started, result.fileName)
            DownloadHandler.Result.NeedsStoragePermission -> getString(R.string.download_retry_from_page)
            is DownloadHandler.Result.Rejected -> getString(R.string.download_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun remove(record: DownloadRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_remove_title)
            .setMessage(R.string.download_remove_message)
            .setPositiveButton(R.string.download_remove) { _, _ ->
                engine.records.remove(record.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun statusText(record: DownloadRecord): String = when (record.status) {
        DownloadRecord.Status.RUNNING -> if (record.totalBytes > 0) {
            getString(R.string.download_status_running, percent(record), Formatter.formatShortFileSize(this, record.totalBytes))
        } else {
            getString(R.string.download_status_running_unknown, Formatter.formatShortFileSize(this, record.downloadedBytes))
        }
        DownloadRecord.Status.COMPLETED ->
            getString(R.string.download_status_done, Formatter.formatShortFileSize(this, record.downloadedBytes))
        DownloadRecord.Status.FAILED -> DocumentOpener.failureText(this, record)
    }

    private fun percent(record: DownloadRecord): Int =
        if (record.totalBytes > 0) ((record.downloadedBytes * 100) / record.totalBytes).toInt().coerceIn(0, 100) else 0

    private inner class DownloadsAdapter : ListAdapter<DownloadRecord, DownloadsAdapter.Holder>(Diff) {

        inner class Holder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = with(holder.binding) {
            val record = getItem(position)
            title.text = record.fileName.ifEmpty { getString(R.string.download_untitled) }
            status.text = statusText(record)
            val running = record.status == DownloadRecord.Status.RUNNING
            progress.isVisible = running
            progress.isIndeterminate = running && record.totalBytes <= 0
            if (running && record.totalBytes > 0) progress.setProgressCompat(percent(record), false)
            open.isVisible = record.status == DownloadRecord.Status.COMPLETED
            retry.isVisible = record.status == DownloadRecord.Status.FAILED
            open.setOnClickListener { DocumentOpener.open(this@DownloadsActivity, record) }
            retry.setOnClickListener { retry(record) }
            remove.setOnClickListener { remove(record) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadRecord>() {
        override fun areItemsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadRecord, newItem: DownloadRecord) = oldItem == newItem
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DownloadsActivity::class.java)
    }
}
