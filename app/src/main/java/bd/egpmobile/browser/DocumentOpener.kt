package bd.egpmobile.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens a downloaded file: PDFs in the built-in viewer, everything else in a suitable app. */
object DocumentOpener {

    /** Intent that opens [record]: PDFs always open in the app, so they work without a PDF app. */
    fun openIntent(context: Context, record: DownloadRecord): Intent {
        val uri = record.uri?.let(Uri::parse) ?: return DownloadsActivity.intent(context)
        return if (record.isPdf) PdfViewerActivity.intent(context, uri, record.fileName) else externalIntent(uri, record.mimeType)
    }

    fun open(context: Context, record: DownloadRecord) {
        if (record.uri == null) {
            Toast.makeText(context, R.string.download_missing, Toast.LENGTH_LONG).show()
            return
        }
        try {
            context.startActivity(openIntent(context, record))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.download_no_viewer_for, extensionLabel(record)), Toast.LENGTH_LONG).show()
        }
    }

    /** Lets the user pick another app (also for PDFs, e.g. to sign or annotate). */
    fun openWithOtherApp(context: Context, uri: Uri, mimeType: String, fileName: String) {
        try {
            context.startActivity(Intent.createChooser(externalIntent(uri, mimeType), context.getString(R.string.download_open_with)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.download_no_viewer_for, fileName), Toast.LENGTH_LONG).show()
        }
    }

    fun share(context: Context, uri: Uri, mimeType: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.download_share)))
    }

    fun failureText(context: Context, record: DownloadRecord): String = when (record.failure) {
        DownloadRecord.Failure.PAGE_INSTEAD_OF_FILE -> context.getString(R.string.download_failed_page)
        DownloadRecord.Failure.HTTP_ERROR -> when (record.httpCode) {
            401, 403 -> context.getString(R.string.download_failed_session, record.httpCode)
            404, 410 -> context.getString(R.string.download_failed_missing, record.httpCode)
            else -> context.getString(R.string.download_failed_http, record.httpCode ?: 0)
        }
        DownloadRecord.Failure.NETWORK -> context.getString(R.string.download_failed_network)
        DownloadRecord.Failure.STORAGE -> context.getString(R.string.download_failed_space)
        DownloadRecord.Failure.BLOCKED -> context.getString(R.string.download_failed_blocked)
        DownloadRecord.Failure.TOO_LARGE -> context.getString(R.string.download_failed_too_large)
        DownloadRecord.Failure.INTERRUPTED -> context.getString(R.string.download_failed_interrupted)
        null -> context.getString(R.string.download_failed_generic)
    }

    private fun externalIntent(uri: Uri, mimeType: String) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun extensionLabel(record: DownloadRecord) =
        DownloadPolicy.extensionOf(record.fileName)?.let { ".$it" } ?: record.fileName
}
