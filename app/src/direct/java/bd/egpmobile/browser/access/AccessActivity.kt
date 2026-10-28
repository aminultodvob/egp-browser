package bd.egpmobile.browser.access

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import bd.egpmobile.browser.BuildConfig
import bd.egpmobile.browser.LegalActivity
import bd.egpmobile.browser.R
import bd.egpmobile.browser.databinding.ActivityAccessBinding
import bd.egpmobile.browser.databinding.SheetPurchaseBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** Account, trial and lifetime purchase screen (direct build only). */
class AccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccessBinding
    private val repository by lazy { AccessRepository.get(this) }
    private var pollJob: Job? = null
    private var working = false
    private var lastPayment: PaymentProgress? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAccessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Light status-bar icons over the brand-gradient header.
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.header.setPadding(bars.left + dp(24), bars.top, bars.right + dp(24), dp(32))
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        binding.close.setOnClickListener { finish() }
        binding.paymentCheck.setOnClickListener { repository.pendingInvoice?.let(::pollPayment) }
        binding.signOut.setOnClickListener { confirmSignOut() }
        binding.deleteAccount.setOnClickListener { confirmDelete() }
        binding.linkPrivacy.setOnClickListener { startActivity(LegalActivity.intent(this, LegalActivity.DOC_PRIVACY)) }
        binding.linkTerms.setOnClickListener { startActivity(LegalActivity.intent(this, LegalActivity.DOC_TERMS)) }
        binding.linkRefund.setOnClickListener { startActivity(LegalActivity.intent(this, LegalActivity.DOC_REFUND)) }
        binding.linkSupport.setOnClickListener { contactSupport() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { repository.version.collect { render() } }
        }
        handlePaymentReturn(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePaymentReturn(intent)
    }

    override fun onResume() {
        super.onResume()
        // Returning from the PayStation page (or any time the screen opens): ask the server.
        repository.pendingInvoice?.let(::pollPayment)
        if (repository.signedInUserId != null) {
            lifecycleScope.launch { runCatching { repository.refreshEntitlement() } }
        }
    }

    /** egpbrowser://payment-result?invoice=… only triggers an authenticated status check. */
    private fun handlePaymentReturn(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "egpbrowser" || data.host != "payment-result") return
        val invoice = data.getQueryParameter("invoice")?.takeIf { INVOICE.matches(it) } ?: repository.pendingInvoice
        invoice?.let(::pollPayment)
    }

    // region Rendering

    private fun render() = with(binding) {
        val signedIn = repository.signedInUserId != null
        val claims = repository.claims()
        accountSection.isVisible = signedIn
        accountEmail.text = getString(R.string.access_signed_in_as, repository.email.orEmpty())
        trialProgress.isVisible = false
        secondaryAction.isVisible = false
        busyIndicator(working)

        when {
            !repository.isConfigured -> show(
                R.drawable.ic_info, R.string.access_title_not_configured, getString(R.string.access_message_not_configured),
                R.string.access_action_check,
            ) { lifecycleScope.launch { runCatching { repository.refreshEntitlement() } } }
            !signedIn -> show(
                R.drawable.ic_account, R.string.access_title_signed_out, getString(R.string.access_message_signed_out),
                R.string.access_action_google,
            ) { signIn() }
            claims == null -> show(
                R.drawable.ic_timer, R.string.access_title_checking, getString(R.string.access_message_checking),
                R.string.access_action_check,
            ) { refresh() }
            claims.state == AccessPolicy.STATE_LIFETIME -> show(
                R.drawable.ic_verified, R.string.access_title_lifetime, getString(R.string.access_message_lifetime),
                R.string.access_action_browse,
            ) { finish() }
            claims.state == AccessPolicy.STATE_TRIAL_ACTIVE -> {
                val now = repository.trustedNow()
                val days = AccessPolicy.trialDaysLeft(claims, now)
                val ends = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(claims.trialExpiresAtMs ?: now))
                show(R.drawable.ic_timer, 0, getString(R.string.access_message_trial, ends), R.string.access_action_browse) { finish() }
                statusTitle.text = resources.getQuantityString(R.plurals.access_trial_days_left, days, days)
                trialProgress.isVisible = true
                trialProgress.setProgressCompat(TRIAL_DAYS - days, true)
                secondary(R.string.access_action_buy) { showPurchaseSheet() }
            }
            claims.state == AccessPolicy.STATE_TRIAL_NOT_STARTED -> {
                show(
                    R.drawable.ic_timer, R.string.access_title_not_started, getString(R.string.access_message_not_started),
                    R.string.access_action_start_trial,
                ) { startTrial() }
                secondary(R.string.access_action_buy) { showPurchaseSheet() }
            }
            else -> show(
                R.drawable.ic_payment, R.string.access_title_expired, getString(R.string.access_message_expired),
                R.string.access_action_buy,
            ) { showPurchaseSheet() }
        }
        renderPayment()
    }

    private fun show(icon: Int, title: Int, message: String, action: Int, onAction: () -> Unit) = with(binding) {
        statusIcon.setImageResource(icon)
        if (title != 0) statusTitle.setText(title)
        statusMessage.text = message
        primaryAction.setText(action)
        primaryAction.isEnabled = !working
        primaryAction.setOnClickListener { onAction() }
    }

    private fun secondary(label: Int, onClick: () -> Unit) = with(binding.secondaryAction) {
        isVisible = true
        setText(label)
        isEnabled = !working
        setOnClickListener { onClick() }
    }

    private fun renderPayment() = with(binding) {
        val payment = lastPayment
        val invoice = repository.pendingInvoice ?: payment?.invoice
        paymentCard.isVisible = invoice != null && payment?.status != "PAID"
        if (!paymentCard.isVisible) return@with
        when (payment?.status) {
            "FAILED", "CANCELLED", "EXPIRED" -> {
                paymentTitle.setText(R.string.access_payment_failed_title)
                paymentMessage.setText(R.string.access_payment_failed_message)
            }
            "VERIFICATION_REQUIRED" -> {
                paymentTitle.setText(R.string.access_payment_review_title)
                paymentMessage.text = getString(R.string.access_payment_review_message, payment.invoice)
            }
            "PROCESSING" -> {
                paymentTitle.setText(R.string.access_payment_waiting_title)
                paymentMessage.setText(
                    if (payment.providerReachable) R.string.access_payment_processing_message else R.string.access_payment_offline
                )
            }
            else -> {
                paymentTitle.setText(R.string.access_payment_waiting_title)
                paymentMessage.setText(R.string.access_payment_waiting_message)
            }
        }
    }

    private fun busyIndicator(isBusy: Boolean) {
        binding.busy.isVisible = isBusy
        binding.primaryAction.isEnabled = !isBusy
        binding.secondaryAction.isEnabled = !isBusy
    }

    // endregion

    // region Actions

    private fun signIn() = runBusy {
        when (val result = GoogleSignIn.signIn(this)) {
            is GoogleSignInResult.Success -> {
                repository.signInWithGoogle(result.idToken)
                // First sign-in: start the free trial straight away when it is available.
                if (repository.claims()?.state == AccessPolicy.STATE_TRIAL_NOT_STARTED) startTrialNow()
            }
            GoogleSignInResult.Cancelled -> Unit
            GoogleSignInResult.NoAccount -> snack(getString(R.string.access_error_no_google_account))
            is GoogleSignInResult.Failed -> snack(getString(R.string.access_error_google))
        }
    }

    private fun startTrial() = runBusy { startTrialNow() }

    private suspend fun startTrialNow() {
        try {
            repository.startTrial()
        } catch (e: ApiException) {
            if (e.httpStatus == 409) {
                snack(getString(R.string.access_message_trial_used))
                runCatching { repository.refreshEntitlement() }
            } else {
                throw e
            }
        }
    }

    private fun refresh() = runBusy { repository.refreshEntitlement() }

    private fun showPurchaseSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = SheetPurchaseBinding.inflate(layoutInflater)
        sheet.nameInput.setText(repository.displayName.orEmpty())
        val validate = {
            sheet.pay.isEnabled = sheet.agree.isChecked
        }
        sheet.agree.setOnCheckedChangeListener { _, _ -> validate() }
        sheet.phoneInput.doAfterTextChanged { sheet.phoneLayout.error = null }
        sheet.nameInput.doAfterTextChanged { sheet.nameLayout.error = null }
        sheet.pay.setOnClickListener {
            val name = sheet.nameInput.text?.toString()?.trim().orEmpty()
            val phone = sheet.phoneInput.text?.toString()?.trim().orEmpty()
            var valid = true
            if (name.length < 2) {
                sheet.nameLayout.error = getString(R.string.purchase_name_invalid)
                valid = false
            }
            if (!BD_MOBILE.matches(phone)) {
                sheet.phoneLayout.error = getString(R.string.purchase_phone_invalid)
                valid = false
            }
            if (!valid) return@setOnClickListener
            dialog.dismiss()
            startPayment(name, phone)
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun startPayment(name: String, phone: String) = runBusy {
        val start = repository.createPayment(name, phone)
        val uri = Uri.parse(start.paymentUrl)
        // Open only PayStation's own HTTPS checkout, never an arbitrary URL.
        if (uri.scheme != "https" || uri.host?.lowercase() !in CHECKOUT_HOSTS) {
            snack(getString(R.string.access_error_checkout))
            return@runBusy
        }
        lastPayment = PaymentProgress(start.invoice, "PENDING", true)
        renderPayment()
        val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(getColor(R.color.brand_navy)).build()
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setDefaultColorSchemeParams(colors)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(this, uri)
        } catch (e: ActivityNotFoundException) {
            snack(getString(R.string.access_error_checkout))
        }
    }

    /** Polls the server (which asks PayStation) until the payment settles or about two minutes pass. */
    private fun pollPayment(invoice: String) {
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            repeat(POLL_ATTEMPTS) {
                val progress = runCatching { repository.paymentStatus(invoice) }.getOrNull()
                if (progress != null) {
                    lastPayment = progress
                    renderPayment()
                    if (progress.status == "PAID") {
                        render()
                        MaterialAlertDialogBuilder(this@AccessActivity)
                            .setIcon(R.drawable.brand_logo)
                            .setTitle(R.string.access_payment_paid_title)
                            .setMessage(R.string.access_payment_success_dialog)
                            .setPositiveButton(R.string.access_action_browse) { _, _ -> finish() }
                            .show()
                        return@launch
                    }
                    if (progress.status !in setOf("PENDING", "PROCESSING")) return@launch
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.access_sign_out)
            .setMessage(R.string.access_sign_out_message)
            .setPositiveButton(R.string.access_sign_out) { _, _ -> runBusy { repository.signOut() } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.access_delete_title)
            .setMessage(R.string.access_delete_message)
            .setPositiveButton(R.string.access_delete_confirm) { _, _ ->
                runBusy {
                    repository.deleteAccount()
                    snack(getString(R.string.access_deleted))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun contactSupport() {
        val email = BuildConfig.SUPPORT_EMAIL
        if (email.isBlank()) {
            snack(getString(R.string.support_not_configured))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
        } catch (e: ActivityNotFoundException) {
            snack(email)
        }
    }

    // endregion

    private fun runBusy(block: suspend () -> Unit) {
        if (working) return
        working = true
        render()
        lifecycleScope.launch {
            try {
                block()
            } catch (e: SignedOutException) {
                snack(getString(R.string.access_error_signed_out))
            } catch (e: NotConfiguredException) {
                snack(getString(R.string.access_message_not_configured))
            } catch (e: ApiException) {
                snack(e.message)
            } catch (e: IOException) {
                snack(getString(R.string.access_error_network))
            } catch (e: Exception) {
                snack(getString(R.string.access_error_generic))
            } finally {
                working = false
                render()
            }
        }
    }

    private fun snack(message: String) = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val INVOICE = Regex("^[A-Z0-9]{8,40}$")
        val BD_MOBILE = Regex("^01[3-9]\\d{8}$")
        val CHECKOUT_HOSTS = setOf("api.paystation.com.bd", "sandbox.paystation.com.bd")
        const val TRIAL_DAYS = 7
        const val POLL_ATTEMPTS = 40
        const val POLL_INTERVAL_MS = 3_000L
    }
}
