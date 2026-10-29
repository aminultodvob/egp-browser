package bd.egpmobile.browser.access

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import bd.egpmobile.browser.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/** Signed in, but the session can no longer be renewed: the user must sign in again. */
class SignedOutException : Exception("Signed out")

data class PaymentStart(val invoice: String, val paymentUrl: String)

data class PaymentProgress(val invoice: String, val status: String, val providerReachable: Boolean)

/**
 * Account session, server-signed entitlement and payments for the direct build.
 * The backend is authoritative; the cached entitlement is only trusted after signature checks.
 */
class AccessRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val store = SecureStore(appContext)
    private val clockPrefs = appContext.getSharedPreferences("access_clock", Context.MODE_PRIVATE)
    private val api = ApiClient()
    private val verifier = EntitlementVerifier(BuildConfig.ENTITLEMENT_PUBLIC_KEY)
    private val refreshLock = Mutex()
    private val clock = TrustedClock(
        elapsedRealtime = SystemClock::elapsedRealtime,
        wallClock = System::currentTimeMillis,
        bootCount = { Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1) },
    ).apply {
        serverTimeAtSyncMs = clockPrefs.getLong(KEY_SERVER_TIME, 0)
        elapsedAtSyncMs = clockPrefs.getLong(KEY_ELAPSED, 0)
        bootCountAtSync = clockPrefs.getInt(KEY_BOOT, -1)
        highWaterMarkMs = clockPrefs.getLong(KEY_HIGH_WATER, 0)
    }

    /** Bumped whenever session or entitlement changes, so screens can re-render. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    val isConfigured: Boolean
        get() = api.isConfigured && verifier.isConfigured && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    val signedInUserId: String? get() = store.getString(KEY_USER_ID)
    val email: String? get() = store.getString(KEY_EMAIL)
    val displayName: String? get() = store.getString(KEY_NAME)
    val pendingInvoice: String? get() = store.getString(KEY_PENDING_INVOICE)

    fun claims(): EntitlementClaims? = store.getString(KEY_ENTITLEMENT)?.let(verifier::verify)

    fun trustedNow(): Long = clock.now().also { clockPrefs.edit { putLong(KEY_HIGH_WATER, clock.highWaterMarkMs) } }

    fun decision(): AccessDecision = AccessPolicy.decide(signedInUserId, claims(), trustedNow())

    // ---- Session ------------------------------------------------------------------------------

    suspend fun signInWithGoogle(idToken: String) = io {
        val response = api.post("/api/auth/google", JSONObject().put("idToken", idToken), null)
        val user = response.getJSONObject("user")
        storeTokens(response)
        store.putString(KEY_USER_ID, user.getString("id"))
        store.putString(KEY_EMAIL, user.optString("email"))
        store.putString(KEY_NAME, user.optString("name").takeIf { it.isNotBlank() && it != "null" })
        storeEntitlement(response.getJSONObject("entitlement"))
    }

    suspend fun signOut() = io {
        store.getString(KEY_REFRESH)?.let { token ->
            runCatching { api.post("/api/auth/logout", JSONObject().put("refreshToken", token), null) }
        }
        clearSession()
    }

    suspend fun deleteAccount() = io {
        authorized { token -> api.delete("/api/account", token) }
        clearSession()
    }

    // ---- Entitlement --------------------------------------------------------------------------

    suspend fun refreshEntitlement() = io {
        if (signedInUserId == null) return@io
        storeEntitlement(authorized { token -> api.get("/api/entitlement", token) })
    }

    @SuppressLint("HardwareIds") // App-scoped ANDROID_ID; sent only for trial abuse prevention, stored hashed.
    suspend fun startTrial() = io {
        val deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        val body = JSONObject().apply { if (!deviceId.isNullOrBlank()) put("deviceId", deviceId) }
        storeEntitlement(authorized { token -> api.post("/api/trial/activate", body, token) })
    }

    // ---- Payments -----------------------------------------------------------------------------

    suspend fun createPayment(customerName: String, customerPhone: String): PaymentStart = io {
        val body = JSONObject().put("customerName", customerName).put("customerPhone", customerPhone)
        val response = authorized { token -> api.post("/api/payment/create", body, token) }
        val start = PaymentStart(response.getString("invoice"), response.getString("paymentUrl"))
        store.putString(KEY_PENDING_INVOICE, start.invoice)
        bump()
        start
    }

    suspend fun paymentStatus(invoice: String): PaymentProgress = io {
        val response = authorized { token -> api.get("/api/payment/status/$invoice", token) }
        storeEntitlement(response.getJSONObject("entitlement"))
        val status = response.getString("status")
        if (status !in OPEN_STATUSES) store.putString(KEY_PENDING_INVOICE, null)
        PaymentProgress(invoice, status, response.optBoolean("providerReachable", true))
    }

    // ---- Internals ----------------------------------------------------------------------------

    /** Runs [call] with a valid access token, renewing it once if the server rejects it. */
    private suspend fun <T> authorized(call: (String) -> T): T {
        val token = validAccessToken()
        return try {
            call(token)
        } catch (e: ApiException) {
            if (e.httpStatus != 401) throw e
            call(renewAccessToken())
        }
    }

    private suspend fun validAccessToken(): String {
        val token = store.getString(KEY_ACCESS)
        val expiresAt = store.getString(KEY_ACCESS_EXPIRES)?.toLongOrNull() ?: 0
        return if (token != null && System.currentTimeMillis() < expiresAt) token else renewAccessToken()
    }

    private suspend fun renewAccessToken(): String = refreshLock.withLock {
        val refresh = store.getString(KEY_REFRESH) ?: throw SignedOutException()
        try {
            val response = api.post("/api/auth/refresh", JSONObject().put("refreshToken", refresh), null)
            storeTokens(response)
            response.getString("accessToken")
        } catch (e: ApiException) {
            if (e.httpStatus == 401) {
                clearSession()
                throw SignedOutException()
            }
            throw e
        }
    }

    private fun storeTokens(response: JSONObject) {
        store.putString(KEY_ACCESS, response.getString("accessToken"))
        // Renew a minute early; the device clock is fine for this (the server re-checks expiry).
        val expiresIn = response.optLong("accessTokenExpiresIn", 900)
        store.putString(KEY_ACCESS_EXPIRES, (System.currentTimeMillis() + (expiresIn - 60) * 1000).toString())
        store.putString(KEY_REFRESH, response.getString("refreshToken"))
    }

    private fun storeEntitlement(entitlement: JSONObject) {
        val token = entitlement.getString("token")
        // Only accept what the server actually signed for this account.
        val claims = verifier.verify(token) ?: throw IllegalStateException("Unverifiable entitlement")
        if (claims.userId != signedInUserId && signedInUserId != null) throw IllegalStateException("Entitlement for another account")
        store.putString(KEY_ENTITLEMENT, token)
        val serverTime = runCatching { Instant.parse(entitlement.getString("serverTime")).toEpochMilli() }.getOrNull()
        if (serverTime != null) {
            clock.sync(serverTime)
            clockPrefs.edit {
                putLong(KEY_SERVER_TIME, clock.serverTimeAtSyncMs)
                putLong(KEY_ELAPSED, clock.elapsedAtSyncMs)
                putInt(KEY_BOOT, clock.bootCountAtSync)
                putLong(KEY_HIGH_WATER, clock.highWaterMarkMs)
            }
        }
        entitlement.optJSONObject("pendingPayment")?.optString("invoice")?.takeIf { it.isNotBlank() }?.let {
            store.putString(KEY_PENDING_INVOICE, it)
        }
        bump()
    }

    private fun clearSession() {
        store.clear()
        bump()
    }

    private fun bump() = _version.update { it + 1 }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private val OPEN_STATUSES = setOf("PENDING", "PROCESSING")
        private const val KEY_ACCESS = "access_token"
        private const val KEY_ACCESS_EXPIRES = "access_expires"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_NAME = "name"
        private const val KEY_ENTITLEMENT = "entitlement"
        private const val KEY_PENDING_INVOICE = "pending_invoice"
        private const val KEY_SERVER_TIME = "server_time"
        private const val KEY_ELAPSED = "elapsed"
        private const val KEY_BOOT = "boot"
        private const val KEY_HIGH_WATER = "high_water"

        @Volatile
        private var instance: AccessRepository? = null

        fun get(context: Context): AccessRepository =
            instance ?: synchronized(this) { instance ?: AccessRepository(context).also { instance = it } }
    }
}
