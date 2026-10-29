package bd.egpmobile.browser

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static check for requirement "no sensitive information in application logs": production code
 * must log only through SafeLog, must never print to stdout, and SafeLog calls must not
 * interpolate raw URLs, cookies or headers.
 */
class LoggingPolicyTest {

    private val sources: List<File> = listOf("src/main/java")
        .flatMap { File(it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    @Test
    fun sourcesAreFound() {
        assertTrue("Run from the app module directory", sources.size >= 10)
    }

    @Test
    fun onlySafeLogUsesAndroidLog() {
        val offenders = sources.filter { it.name != "SafeLog.kt" }.filter { file ->
            val text = file.readText()
            text.contains("import android.util.Log") || Regex("""\bLog\.[vdiwe]\(""").containsMatchIn(text)
        }
        assertTrue("Direct android.util.Log use in: ${offenders.map { it.name }}", offenders.isEmpty())
    }

    @Test
    fun noPrintStatements() {
        val offenders = sources.filter { Regex("""\b(println|print|printStackTrace)\(""").containsMatchIn(it.readText()) }
        assertTrue("Console output in: ${offenders.map { it.name }}", offenders.isEmpty())
    }

    @Test
    fun safeLogCallsDoNotIncludeSensitiveValues() {
        val call = Regex("""SafeLog\.[dw]\([^\n]*""")
        val sensitive = Regex("""\$\{?(url|cookie|cookies|header|headers|password|token|idToken|refreshToken|accessToken|phone)\b""", RegexOption.IGNORE_CASE)
        val offenders = sources.flatMap { file ->
            call.findAll(file.readText()).map { it.value }.filter { sensitive.containsMatchIn(it) }.map { "${file.name}: $it" }
        }
        assertTrue("Possibly sensitive log statements: $offenders", offenders.isEmpty())
    }
}
