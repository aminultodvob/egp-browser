package bd.egpmobile.browser

import java.util.Locale

/**
 * URL normalization and navigation policy.
 *
 * Pure Kotlin (no android.* types) so it can be unit tested on the JVM. Hosts are matched
 * exactly; there is no wildcard or subdomain matching, so look-alike hosts such as
 * `eprocure.gov.bd.example.com` are never trusted.
 */
object UrlValidator {

    const val HOME_URL = "https://www.eprocure.gov.bd/"

    /** Hosts observed serving the official portal (see docs/SECURITY.md). */
    val DEFAULT_TRUSTED_HOSTS: Set<String> = setOf("www.eprocure.gov.bd", "eprocure.gov.bd")

    sealed class Decision {
        /** Load inside the browser. */
        data class Allow(val url: String) : Decision()

        /** Trusted host requested over HTTP: load this HTTPS URL instead. */
        data class Upgrade(val httpsUrl: String) : Decision()

        /** Not a trusted e-GP page: may only be handed to another app after the user confirms. */
        data class External(val url: String) : Decision()

        /** Never load or hand off. */
        data class Block(val reason: BlockReason) : Decision()
    }

    enum class BlockReason { INVALID_URL, UNSUPPORTED_SCHEME, CREDENTIALS_IN_URL }

    data class Parts(
        val scheme: String,
        val userInfo: String?,
        val host: String?,
        val port: Int?,
        /** Path, query and fragment exactly as given. */
        val rest: String,
        val hierarchical: Boolean,
    )

    private val WEB_SCHEMES = setOf("http", "https")
    private val SCHEME_AND_REST =Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val HOSTNAME = Regex(
        "^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$"
    )
    private val EXPLICIT_SCHEMES = Regex(
        "^(mailto|tel|intent|javascript|data|about|file|content|blob|ftp|ws|wss):",
        RegexOption.IGNORE_CASE
    )

    /** Parses [url] leniently. Returns null when it has no scheme or a malformed authority. */
    fun parse(url: String): Parts? {
        val match = SCHEME_AND_REST.matchEntire(url.trim()) ?: return null
        val scheme = match.groupValues[1].lowercase(Locale.ROOT)
        val afterScheme = match.groupValues[2]
        if (!afterScheme.startsWith("//")) {
            return Parts(scheme, null, null, null, afterScheme, hierarchical = false)
        }
        val authorityAndRest = afterScheme.substring(2)
        val authorityEnd = authorityAndRest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) authorityAndRest.length else it }
        val authority = authorityAndRest.substring(0, authorityEnd)
        val rest = authorityAndRest.substring(authorityEnd)

        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else null
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        if (hostPort.startsWith("[")) return null // IPv6 literals are never trusted; treat as invalid.

        val colon = hostPort.lastIndexOf(':')
        val rawHost = if (colon >= 0) hostPort.substring(0, colon) else hostPort
        val port = if (colon >= 0) {
            val portText = hostPort.substring(colon + 1)
            if (portText.isEmpty()) null else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        } else {
            null
        }
        val host = rawHost.lowercase(Locale.ROOT).removeSuffix(".").takeIf { it.isNotEmpty() }
        // Web hosts must be real DNS names; other schemes (intent://, content://) are never trusted anyway.
        if (host != null && scheme in WEB_SCHEMES && !isValidHostname(host)) return null
        return Parts(scheme, userInfo, host, port, rest, hierarchical = true)
    }

    fun isValidHostname(host: String): Boolean = HOSTNAME.matches(host)

    fun decide(url: String, trustedHosts: Set<String>): Decision {
        if (url == "about:blank") return Decision.Allow(url)
        val parts = parse(url) ?: return Decision.Block(BlockReason.INVALID_URL)
        return when (parts.scheme) {
            "https" -> when {
                parts.host == null -> Decision.Block(BlockReason.INVALID_URL)
                parts.userInfo != null -> Decision.Block(BlockReason.CREDENTIALS_IN_URL)
                parts.host in trustedHosts && (parts.port == null || parts.port == 443) -> Decision.Allow(url)
                else -> Decision.External(url)
            }
            "http" -> when {
                parts.host == null -> Decision.Block(BlockReason.INVALID_URL)
                parts.userInfo != null -> Decision.Block(BlockReason.CREDENTIALS_IN_URL)
                parts.host in trustedHosts && (parts.port == null || parts.port == 80) ->
                    Decision.Upgrade("https://${parts.host}${parts.rest.ifEmpty { "/" }}")
                else -> Decision.External(url)
            }
            "mailto", "tel", "intent" -> Decision.External(url)
            else -> Decision.Block(BlockReason.UNSUPPORTED_SCHEME)
        }
    }

    /** True only for HTTPS URLs on a trusted host and the default port. */
    fun isTrustedSecureUrl(url: String?, trustedHosts: Set<String>): Boolean {
        if (url == null) return false
        return decide(url, trustedHosts) is Decision.Allow && url.startsWith("https:", ignoreCase = true)
    }

    /**
     * Turns address-bar input into a URL. Bare hosts get `https://`; a leading `/` is a path on
     * the portal. Returns null for input that can never be a URL (empty or containing spaces).
     */
    fun normalizeInput(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val candidate = when {
            text.startsWith("/") && !text.startsWith("//") -> HOME_URL.trimEnd('/') + text
            text.contains("://") || EXPLICIT_SCHEMES.containsMatchIn(text) -> text
            else -> "https://$text"
        }
        return candidate.takeIf { parse(it) != null }
    }

    /**
     * Form safe for logs and UI messages: scheme, host and path only. Query strings, fragments,
     * credentials and `;jsessionid=` path parameters are removed.
     */
    fun redact(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val parts = parse(url) ?: return "<invalid>"
        if (!parts.hierarchical) return "${parts.scheme}:"
        val path = parts.rest.substringBefore('?').substringBefore('#').substringBefore(';')
        return "${parts.scheme}://${parts.host.orEmpty()}$path"
    }

    /** Host for display, e.g. in the external-link dialog. */
    fun displayHost(url: String): String = parse(url)?.host ?: url.substringBefore(':')

    /** Parses the user-maintained extra host list (comma, space or newline separated). */
    fun parseHostList(text: String): Set<String> = splitHostList(text).filter(::isValidHostname).toSet()

    /** Entries of a host list that are not valid hostnames. */
    fun invalidHosts(text: String): List<String> = splitHostList(text).filterNot(::isValidHostname)

    private fun splitHostList(text: String): List<String> =
        text.split(',', ' ', '\n', '\r', '\t').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
}
