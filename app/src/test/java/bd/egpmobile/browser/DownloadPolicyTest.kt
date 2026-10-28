package bd.egpmobile.browser

import bd.egpmobile.browser.DownloadPolicy.Rejection
import bd.egpmobile.browser.DownloadPolicy.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {

    private val trusted = UrlValidator.DEFAULT_TRUSTED_HOSTS

    @Test
    fun trustedHttpsDownloadsAreAccepted() {
        val url = "https://www.eprocure.gov.bd/help/guidelines/eGP_Guidelines.pdf"
        assertEquals(Validation.Accepted(url), DownloadPolicy.validate(url, trusted))
    }

    @Test
    fun trustedHttpDownloadsAreUpgraded() {
        assertEquals(
            Validation.Accepted("https://www.eprocure.gov.bd/help/a.pdf"),
            DownloadPolicy.validate("http://www.eprocure.gov.bd/help/a.pdf", trusted)
        )
    }

    @Test
    fun untrustedSourcesNeverGetSessionCookies() {
        assertEquals(
            Validation.Rejected(Rejection.UNTRUSTED_SOURCE),
            DownloadPolicy.validate("https://get.adobe.com/reader/installer.pdf", trusted)
        )
    }

    @Test
    fun blobAndDataUrlsAreUnsupported() {
        assertEquals(Validation.Rejected(Rejection.UNSUPPORTED_SCHEME), DownloadPolicy.validate("blob:https://www.eprocure.gov.bd/x", trusted))
        assertEquals(Validation.Rejected(Rejection.UNSUPPORTED_SCHEME), DownloadPolicy.validate("data:application/pdf;base64,AAAA", trusted))
        assertEquals(Validation.Rejected(Rejection.UNSUPPORTED_SCHEME), DownloadPolicy.validate("file:///sdcard/x.pdf", trusted))
        assertEquals(Validation.Rejected(Rejection.UNSUPPORTED_SCHEME), DownloadPolicy.validate("about:blank", trusted))
        assertEquals(Validation.Rejected(Rejection.INVALID_URL), DownloadPolicy.validate("https://a@www.eprocure.gov.bd/x", trusted))
    }

    @Test
    fun contentDispositionVariants() {
        assertEquals("Tender Notice.pdf", DownloadPolicy.parseContentDisposition("attachment; filename=\"Tender Notice.pdf\""))
        assertEquals("boq.xlsx", DownloadPolicy.parseContentDisposition("attachment; filename=boq.xlsx"))
        assertEquals("a\"b.pdf", DownloadPolicy.parseContentDisposition("attachment; filename=\"a\\\"b.pdf\""))
        assertEquals(
            "দরপত্র.pdf",
            DownloadPolicy.parseContentDisposition(
                "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E0%A6%A6%E0%A6%B0%E0%A6%AA%E0%A6%A4%E0%A7%8D%E0%A6%B0.pdf"
            )
        )
        assertEquals("1+1.pdf", DownloadPolicy.parseContentDisposition("attachment; filename*=UTF-8''1+1.pdf"))
        assertNull(DownloadPolicy.parseContentDisposition("inline"))
        assertNull(DownloadPolicy.parseContentDisposition(null))
    }

    @Test
    fun fileNameFallsBackToUrlThenMimeType() {
        assertEquals(
            "eGP_Guidelines.pdf",
            DownloadPolicy.fileName("https://www.eprocure.gov.bd/help/guidelines/eGP_Guidelines.pdf", null, "application/pdf")
        )
        assertEquals(
            "Notice 1.pdf",
            DownloadPolicy.fileName("https://www.eprocure.gov.bd/files/Notice%201.pdf?sig=abc", null, null)
        )
        // Servlet URLs without an extension get one from the MIME type.
        assertEquals(
            "FileDownloadServlet.zip",
            DownloadPolicy.fileName("https://www.eprocure.gov.bd/FileDownloadServlet?docId=7", null, "application/zip")
        )
        // A .jsp "file name" is replaced by the real type.
        assertEquals(
            "ViewDocument.pdf",
            DownloadPolicy.fileName("https://www.eprocure.gov.bd/ViewDocument.jsp?id=1", null, "application/pdf; charset=binary")
        )
        assertEquals("download", DownloadPolicy.fileName("https://www.eprocure.gov.bd/", null, "application/octet-stream"))
        assertEquals("download.docx", DownloadPolicy.fileName(
            "https://www.eprocure.gov.bd/",
            null,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ))
    }

    @Test
    fun contentDispositionWinsOverUrl() {
        assertEquals(
            "BOQ.xls",
            DownloadPolicy.fileName("https://www.eprocure.gov.bd/Download.jsp?id=1", "attachment; filename=BOQ.xls", "application/vnd.ms-excel")
        )
    }

    @Test
    fun fileNamesAreSanitized() {
        assertEquals("passwd", DownloadPolicy.sanitize("../../etc/passwd"))
        assertEquals("evil.pdf", DownloadPolicy.sanitize("..\\..\\evil.pdf"))
        assertEquals("a_b_c.pdf", DownloadPolicy.sanitize("a:b*c.pdf"))
        assertEquals("hidden.pdf", DownloadPolicy.sanitize(".hidden.pdf"))
        assertEquals("download", DownloadPolicy.sanitize("..."))
        assertEquals("download", DownloadPolicy.sanitize(""))
        val long = "x".repeat(300) + ".pdf"
        val sanitized = DownloadPolicy.sanitize(long)
        assertEquals(120, sanitized.length)
        assertTrue(sanitized.endsWith(".pdf"))
    }

    @Test
    fun executablesAreBlocked() {
        assertTrue(DownloadPolicy.isExecutable("update.apk", null))
        assertTrue(DownloadPolicy.isExecutable("setup.EXE", "application/octet-stream"))
        assertTrue(DownloadPolicy.isExecutable("tender.pdf", "application/vnd.android.package-archive"))
        assertTrue(DownloadPolicy.isExecutable("run.sh", null))
        assertFalse(DownloadPolicy.isExecutable("tender.pdf", "application/pdf"))
        assertFalse(DownloadPolicy.isExecutable("boq.xlsx", null))
        assertFalse(DownloadPolicy.isExecutable("documents.zip", "application/zip"))
        assertFalse(DownloadPolicy.isExecutable("README", null))
    }
}
