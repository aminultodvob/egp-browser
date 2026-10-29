package bd.egpmobile.browser.access

import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Claims of a server-signed entitlement (see backend/src/entitlement.ts). */
data class EntitlementClaims(
    val userId: String,
    val state: String,
    /** Trial expiry, epoch milliseconds, or null. */
    val trialExpiresAtMs: Long?,
    val issuedAtMs: Long,
    /** Until when the app may rely on this entitlement without reaching the server. */
    val validUntilMs: Long,
)

/**
 * Verifies the backend's ES256 compact JWS with the public key embedded at build time, so a cached
 * entitlement cannot be forged or edited on the device. Pure JVM code (unit tested).
 */
class EntitlementVerifier(publicKeyBase64: String) {

    private val publicKey: PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())))
    }.getOrNull()

    val isConfigured: Boolean get() = publicKey != null

    /** Returns the claims only if the signature, algorithm, issuer and audience are valid. */
    fun verify(token: String): EntitlementClaims? {
        val key = publicKey ?: return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        return runCatching {
            val urlDecoder = Base64.getUrlDecoder()
            val header = JSONObject(String(urlDecoder.decode(parts[0]), Charsets.UTF_8))
            if (header.optString("alg") != "ES256") return null
            val signature = urlDecoder.decode(parts[2])
            if (signature.size != 64) return null
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            if (!verifier.verify(rawToDer(signature))) return null

            val claims = JSONObject(String(urlDecoder.decode(parts[1]), Charsets.UTF_8))
            if (claims.optString("iss") != ISSUER || claims.optString("aud") != AUDIENCE) return null
            EntitlementClaims(
                userId = claims.getString("sub"),
                state = claims.getString("st"),
                trialExpiresAtMs = if (claims.isNull("texp")) null else claims.getLong("texp") * 1000,
                issuedAtMs = claims.getLong("iat") * 1000,
                validUntilMs = claims.getLong("exp") * 1000,
            )
        }.getOrNull()
    }

    companion object {
        const val ISSUER = "egp-browser-backend"
        const val AUDIENCE = "egp-browser-app"

        /** JWS ES256 signatures are raw R||S (64 bytes); Java expects ASN.1 DER. */
        fun rawToDer(raw: ByteArray): ByteArray {
            fun integer(bytes: ByteArray): ByteArray {
                var start = 0
                while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
                var value = bytes.copyOfRange(start, bytes.size)
                if (value[0].toInt() and 0x80 != 0) value = byteArrayOf(0) + value
                return byteArrayOf(0x02, value.size.toByte()) + value
            }
            val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
    }
}
