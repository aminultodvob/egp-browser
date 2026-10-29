package bd.egpmobile.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Download notifications: progress while running, then "tap to open" or the failure reason.
 * Silently does nothing when the user has not allowed notifications.
 */
class DownloadNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, appContext.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW)
            appContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    fun progress(record: DownloadRecord) {
        val percent = if (record.totalBytes > 0) ((record.downloadedBytes * 100) / record.totalBytes).toInt() else 0
        notify(record, base(record)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText(appContext.getString(R.string.download_notification_running))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, record.totalBytes <= 0)
            .setContentIntent(pending(record, DownloadsActivity.intent(appContext))))
    }

    fun completed(record: DownloadRecord) {
        notify(record, base(record)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentText(appContext.getString(R.string.download_notification_done))
            .setAutoCancel(true)
            .setContentIntent(pending(record, DocumentOpener.openIntent(appContext, record))))
    }

    fun failed(record: DownloadRecord) {
        notify(record, base(record)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentText(DocumentOpener.failureText(appContext, record))
            .setAutoCancel(true)
            .setContentIntent(pending(record, DownloadsActivity.intent(appContext))))
    }

    private fun base(record: DownloadRecord) = NotificationCompat.Builder(appContext, CHANNEL)
        .setContentTitle(record.fileName)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun pending(record: DownloadRecord, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            appContext, record.id.hashCode(), intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    @SuppressLint("MissingPermission") // Checked in allowed().
    private fun notify(record: DownloadRecord, builder: NotificationCompat.Builder) {
        if (allowed()) manager.notify(record.id.hashCode(), builder.build())
    }

    private fun allowed(): Boolean =
        manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private companion object {
        const val CHANNEL = "downloads"
    }
}
