package bd.egpmobile.browser

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end download checks against the live public portal (no sign-in needed): a real PDF is saved
 * as a real PDF and opens in the built-in viewer; a web page served in place of a document is refused.
 */
@RunWith(AndroidJUnit4::class)
class DownloadPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = DownloadHandler.Engine.get(context)
    private val trusted = UrlValidator.DEFAULT_TRUSTED_HOSTS
    private val userAgent = "Mozilla/5.0 (Linux; Android) e-GP Browser instrumented test"

    private fun waitForFinish(id: String): DownloadRecord {
        val deadline = SystemClock.uptimeMillis() + 120_000
        while (SystemClock.uptimeMillis() < deadline) {
            val record = engine.records.get(id)!!
            if (record.status != DownloadRecord.Status.RUNNING) return record
            SystemClock.sleep(500)
        }
        error("Download did not finish in time")
    }

    @Test
    fun publicPdfIsSavedAsARealPdfAndOpensInTheBuiltInViewer() {
        val start = engine.start(
            PendingDownload("https://www.eprocure.gov.bd/help/guidelines/eGP_Guidelines.pdf", userAgent, null, "application/pdf", null),
            "eGP_Guidelines.pdf",
            trusted,
        )
        val record = waitForFinish(start.id)
        assertEquals("status (failure=${record.failure}, http=${record.httpCode})", DownloadRecord.Status.COMPLETED, record.status)
        assertEquals("application/pdf", record.mimeType)
        assertTrue(record.fileName, record.fileName.endsWith(".pdf"))
        val uri = Uri.parse(assertNotNullUri(record.uri))
        val head = context.contentResolver.openInputStream(uri)!!.use { it.readNBytesCompat(5) }
        assertEquals("%PDF-", String(head, Charsets.US_ASCII))
        assertTrue(record.downloadedBytes > 100_000)

        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(context, uri, record.fileName)).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 30_000
            var indicator = ""
            while (SystemClock.uptimeMillis() < deadline && !indicator.startsWith("1 /")) {
                scenario.onActivity { activity ->
                    val view = activity.findViewById<TextView>(R.id.pageIndicator)
                    indicator = if (view.visibility == View.VISIBLE) view.text.toString() else ""
                }
                SystemClock.sleep(300)
            }
            assertTrue("viewer did not show pages: '$indicator'", indicator.startsWith("1 /"))
        }
        engine.records.remove(record.id)
        context.contentResolver.delete(uri, null, null)
    }

    @Test
    fun aWebPageServedInsteadOfADocumentIsRefusedNotSavedAsAFakePdf() {
        val start = engine.start(
            PendingDownload("https://www.eprocure.gov.bd/FAQ.jsp", userAgent, null, "application/pdf", null),
            "document.pdf",
            trusted,
        )
        val record = waitForFinish(start.id)
        assertEquals(DownloadRecord.Status.FAILED, record.status)
        assertEquals(DownloadRecord.Failure.PAGE_INSTEAD_OF_FILE, record.failure)
        assertEquals(null, record.uri)
        engine.records.remove(record.id)
    }

    private fun assertNotNullUri(uri: String?): String {
        assertNotNull("saved file has no URI", uri)
        return uri!!
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(out, read, n - read)
            if (r < 0) break
            read += r
        }
        return out.copyOf(read)
    }
}
