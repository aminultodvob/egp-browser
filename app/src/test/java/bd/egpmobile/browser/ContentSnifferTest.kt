package bd.egpmobile.browser

import bd.egpmobile.browser.ContentSniffer.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentSnifferTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun text(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun detectsRealDocuments() {
        assertEquals(Kind.PDF, ContentSniffer.detect(text("%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n1 0 obj")))
        assertEquals(Kind.ZIP, ContentSniffer.detect(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        assertEquals(Kind.OLE, ContentSniffer.detect(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1, 0x00)))
        assertEquals(Kind.RAR, ContentSniffer.detect(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)))
        assertEquals(Kind.PNG, ContentSniffer.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(Kind.JPEG, ContentSniffer.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
    }

    @Test
    fun pdfHeaderMayFollowJunkLikeReadersAllow() {
        assertEquals(Kind.PDF, ContentSniffer.detect(text("\r\n\r\n   %PDF-1.4 ...")))
    }

    @Test
    fun recognisesHtmlErrorPagesThatUsedToBeSavedAsPdf() {
        assertEquals(Kind.HTML, ContentSniffer.detect(text("\n\n<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0\">")))
        assertEquals(Kind.HTML, ContentSniffer.detect(text("<html><head><title>Session expired</title>")))
        assertEquals(Kind.HTML, ContentSniffer.detect(text("\uFEFF  <HTML>")))
        assertEquals(Kind.HTML, ContentSniffer.detect(text("<script>alert('Please login')</script>")))
    }

    @Test
    fun windowsExecutablesAreFlagged() {
        assertEquals(Kind.WINDOWS_EXE, ContentSniffer.detect(bytes(0x4D, 0x5A, 0x90, 0x00)))
    }

    @Test
    fun unknownContent() {
        assertEquals(Kind.UNKNOWN, ContentSniffer.detect(text("plain notes")))
        assertEquals(Kind.UNKNOWN, ContentSniffer.detect(ByteArray(0)))
    }

    @Test
    fun fileNamesGetTheExtensionTheContentProves() {
        // Servlet name without extension, or with a script extension, served as a PDF.
        assertEquals("FileDownloadServlet.pdf", ContentSniffer.fixFileName("FileDownloadServlet", Kind.PDF))
        assertEquals("ViewDocument.pdf", ContentSniffer.fixFileName("ViewDocument.jsp", Kind.PDF))
        assertEquals("download.pdf", ContentSniffer.fixFileName("download", Kind.PDF))
        // A real PDF called .doc keeps its name but gains the true extension.
        assertEquals("notice.doc.pdf", ContentSniffer.fixFileName("notice.doc", Kind.PDF))
        // Office Open XML files are ZIP containers: keep .docx / .xlsx.
        assertEquals("boq.xlsx", ContentSniffer.fixFileName("boq.xlsx", Kind.ZIP))
        assertEquals("tender.docx", ContentSniffer.fixFileName("tender.docx", Kind.ZIP))
        assertEquals("archive.zip", ContentSniffer.fixFileName("archive", Kind.ZIP))
        assertEquals("old.xls", ContentSniffer.fixFileName("old.xls", Kind.OLE))
        assertEquals("photo.jpeg", ContentSniffer.fixFileName("photo.jpeg", Kind.JPEG))
        assertEquals("same.pdf", ContentSniffer.fixFileName("same.pdf", Kind.PDF))
        assertEquals("unknown.bin", ContentSniffer.fixFileName("unknown.bin", Kind.UNKNOWN))
    }

    @Test
    fun mimeTypesComeFromContentNotFromWrongServerLabels() {
        assertEquals("application/pdf", ContentSniffer.mimeTypeFor("x.pdf", Kind.PDF, "application/octet-stream"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ContentSniffer.mimeTypeFor("boq.xlsx", Kind.ZIP, "application/octet-stream"),
        )
        assertEquals("application/zip", ContentSniffer.mimeTypeFor("a.zip", Kind.ZIP, null))
        assertEquals("application/vnd.ms-excel", ContentSniffer.mimeTypeFor("old.xls", Kind.OLE, null))
        assertEquals("text/csv", ContentSniffer.mimeTypeFor("list.csv", Kind.UNKNOWN, "application/octet-stream"))
        assertEquals("text/plain", ContentSniffer.mimeTypeFor("notes", Kind.UNKNOWN, "text/plain; charset=UTF-8"))
        assertEquals("application/octet-stream", ContentSniffer.mimeTypeFor("blob", Kind.UNKNOWN, null))
    }
}
