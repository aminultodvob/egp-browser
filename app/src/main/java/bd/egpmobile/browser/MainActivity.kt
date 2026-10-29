package bd.egpmobile.browser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Message
import android.security.KeyChain
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ClientCertRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import bd.egpmobile.browser.access.AccessDecision
import bd.egpmobile.browser.access.AppAccess
import bd.egpmobile.browser.access.LockReason
import bd.egpmobile.browser.databinding.ActivityMainBinding
import bd.egpmobile.browser.databinding.ItemMenuTileBinding
import bd.egpmobile.browser.databinding.SheetBrowserMenuBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.security.PrivateKey
import java.security.cert.X509Certificate

class MainActivity : AppCompatActivity(), BrowserWebViewClient.Host, BrowserChromeClient.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: BrowserController
    private lateinit var downloadHandler: DownloadHandler
    private val viewModel: BrowserViewModel by viewModels()
    private val settings by lazy { BrowserSettings(this) }
    private val sessionManager = SessionManager()
    private val touchHover by lazy { TouchHoverSupport(this) }
    private val accessGate by lazy { AppAccess.create(this) }

    private val fileChooser = FileChooserLauncher(this) { message -> showSnackbar(message) }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val download = pendingDownload
        pendingDownload = null
        if (download == null) return@registerForActivityResult
        if (granted) startDownload(download) else showSnackbar(R.string.download_permission_denied)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == SettingsActivity.RESULT_CLEAR_DATA) showClearDataDialog()
    }

    private val popups = mutableListOf<WebView>()
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingDownload: PendingDownload? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var externalDialogShowing = false
    private var clearHistoryOnNextPage = false
    private var webViewGone = false

    /** First page to open once access is confirmed (direct build); null after it has loaded. */
    private var pendingInitialUrl: String? = null

    @VisibleForTesting
    internal val webView: WebView get() = binding.webView

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        controller = BrowserController(binding.webView, settings)
        controller.navigationAllowed = { accessGate.current() is AccessDecision.Allowed }
        downloadHandler = DownloadHandler(this) { controller.trustedHosts }
        setUpWebView(binding.webView)
        setUpInsets()
        setUpControls()
        setUpBackNavigation()
        observeState()

        viewModel.setDesktopMode(settings.desktopMode)
        requestedOrientation = settings.orientation
        if (!controller.restoreState(savedInstanceState)) {
            pendingInitialUrl = trustedUrlFrom(intent) ?: UrlValidator.HOME_URL
        }
        applyAccess()
        showDisclaimerIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = trustedUrlFrom(intent) ?: return
        if (accessGate.current() is AccessDecision.Allowed) controller.load(url) else pendingInitialUrl = url
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        popups.forEach { it.onResume() }
        applyChangedSettings()
        applyAccess()
        accessGate.refresh { applyAccess() }
    }

    override fun onPause() {
        binding.webView.onPause()
        popups.forEach { it.onPause() }
        sessionManager.persist()
        super.onPause()
    }

    override fun onStop() {
        unregisterNetworkCallback()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewGone) controller.saveState(outState)
    }

    override fun onDestroy() {
        fileChooser.cancelPending()
        popups.toList().forEach(::closePopup)
        if (!webViewGone) controller.destroy()
        super.onDestroy()
    }

    // region Setup

    private fun setUpWebView(view: WebView) {
        controller.configure(view)
        touchHover.install(view)
        view.webViewClient = BrowserWebViewClient(this) { controller.trustedHosts }
        view.webChromeClient = BrowserChromeClient(this)
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownloadRequested(view, url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun setUpInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setUpControls() = with(binding) {
        buttonBack.setOnClickListener { controller.goBack() }
        buttonForward.setOnClickListener { controller.goForward() }
        buttonHome.setOnClickListener { controller.goHome() }
        buttonReload.setOnClickListener {
            if (viewModel.state.value.loading) controller.stop() else reloadOrRetry()
        }
        buttonMenu.setOnClickListener { showMenu() }
        modeBadge.setOnClickListener { confirmRenderingModeSwitch() }
        exitFullscreen.setOnClickListener { setFullscreen(false) }
        popupClose.setOnClickListener { popups.lastOrNull()?.let(::closePopup) }
        errorView.errorRetry.setOnClickListener { reloadOrRetry() }
        errorView.errorHome.setOnClickListener {
            viewModel.clearError()
            controller.goHome()
        }

        addressBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                submitAddress()
                true
            } else {
                false
            }
        }
        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) addressBar.setText(viewModel.state.value.url)
        }
    }

    private fun setUpBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val top = popups.lastOrNull()
                when {
                    customView != null -> hideCustomView()
                    viewModel.state.value.fullscreen -> setFullscreen(false)
                    top != null -> if (top.canGoBack()) top.goBack() else closePopup(top)
                    binding.addressBar.hasFocus() -> clearAddressFocus()
                    binding.webView.canGoBack() && accessGate.current() is AccessDecision.Allowed -> binding.webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadHandler.Engine.get(this@MainActivity).events.collect(::onDownloadEvent)
            }
        }
    }

    // endregion

    // region Rendering

    private fun render(state: BrowserUiState) = with(binding) {
        if (!addressBar.hasFocus() && addressBar.text.toString() != state.url) addressBar.setText(state.url)

        val showProgress = state.loading && state.progress < 100
        progress.visibility = if (showProgress) View.VISIBLE else View.INVISIBLE
        progress.setProgressCompat(state.progress, true)

        buttonReload.setImageResource(if (state.loading) R.drawable.ic_close else R.drawable.ic_refresh)
        buttonReload.contentDescription = getString(if (state.loading) R.string.action_stop else R.string.action_reload)
        setEnabled(buttonBack, state.canGoBack)
        setEnabled(buttonForward, state.canGoForward)

        securityIcon.setImageResource(if (state.secure) R.drawable.ic_lock else R.drawable.ic_info)
        securityIcon.imageTintList = ColorStateList.valueOf(
            if (state.secure) getColor(R.color.brand_green_text)
            else MaterialColors.getColor(securityIcon, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        securityIcon.contentDescription = if (state.secure) {
            getString(R.string.security_secure, UrlValidator.displayHost(state.url))
        } else {
            getString(R.string.security_not_verified)
        }

        modeBadge.setText(if (state.desktopMode) R.string.mode_badge_desktop else R.string.mode_badge_mobile)
        modeBadge.contentDescription = getString(
            if (state.desktopMode) R.string.mode_badge_desktop_description else R.string.mode_badge_mobile_description
        )
        offlineBanner.isVisible = state.offline && !state.fullscreen

        renderError(state.error)

        topBar.isVisible = !state.fullscreen
        bottomBar.isVisible = !state.fullscreen
        exitFullscreen.isVisible = state.fullscreen
        updateSystemBars(hidden = state.fullscreen || customView != null)
    }

    private fun renderError(error: PageError?) = with(binding.errorView) {
        fade(root, error != null)
        if (error == null) return@with
        val (title, message) = when (error) {
            PageError.OFFLINE -> R.string.error_offline_title to R.string.error_offline_message
            PageError.TIMEOUT -> R.string.error_timeout_title to R.string.error_timeout_message
            PageError.HOST_NOT_FOUND -> R.string.error_host_title to R.string.error_host_message
            PageError.CONNECTION_FAILED -> R.string.error_connection_title to R.string.error_connection_message
            PageError.SSL -> R.string.error_ssl_title to R.string.error_ssl_message
            PageError.UNSAFE -> R.string.error_unsafe_title to R.string.error_unsafe_message
            PageError.GENERIC -> R.string.error_generic_title to R.string.error_generic_message
        }
        errorTitle.setText(title)
        errorMessage.setText(message)
        errorIcon.setImageResource(if (error == PageError.SSL || error == PageError.UNSAFE) R.drawable.ic_info else R.drawable.ic_cloud_off)
    }

    private fun setEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.38f
    }

    private fun updateSystemBars(hidden: Boolean) {
        val insetsController = WindowCompat.getInsetsController(window, binding.root)
        if (hidden) {
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // endregion

    // region User actions

    private fun submitAddress() {
        val input = binding.addressBar.text.toString()
        when (val result = controller.navigateFromInput(input)) {
            BrowserController.InputResult.Loaded -> clearAddressFocus()
            is BrowserController.InputResult.External -> {
                clearAddressFocus()
                onExternalNavigation(binding.webView, result.url)
            }
            BrowserController.InputResult.Invalid -> binding.addressBar.error = getString(R.string.address_invalid)
            is BrowserController.InputResult.Blocked -> binding.addressBar.error = getString(R.string.address_blocked)
        }
    }

    private fun clearAddressFocus() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
        binding.addressBar.clearFocus()
        binding.webView.requestFocus()
    }

    private fun reloadOrRetry() {
        val state = viewModel.state.value
        viewModel.clearError()
        if (state.error != null && binding.webView.url.isNullOrEmpty()) controller.goHome() else controller.reload()
    }

    private data class MenuAction(val icon: Int, val label: Int, val action: () -> Unit)

    private fun showMenu() {
        val dialog = BottomSheetDialog(this)
        val sheet = SheetBrowserMenuBinding.inflate(layoutInflater)
        val state = viewModel.state.value
        sheet.menuSubtitle.text = accessGate.statusLine(this) ?: UrlValidator.displayHost(state.url)
        sheet.desktopSwitch.isChecked = state.desktopMode
        sheet.desktopSwitch.setOnClickListener {
            sheet.desktopSwitch.isChecked = state.desktopMode
            dialog.dismiss()
            confirmRenderingModeSwitch()
        }
        sheet.desktopRow.setOnClickListener { sheet.desktopSwitch.performClick() }

        val actions = buildList {
            add(MenuAction(R.drawable.ic_zoom_in, R.string.action_zoom_in) { controller.zoomIn() })
            add(MenuAction(R.drawable.ic_zoom_out, R.string.action_zoom_out) { controller.zoomOut() })
            add(MenuAction(R.drawable.ic_fullscreen, R.string.action_fullscreen) { setFullscreen(true) })
            add(MenuAction(R.drawable.ic_download, R.string.action_downloads) {
                startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
            })
            add(MenuAction(R.drawable.ic_open_in_new, R.string.menu_open_external) {
                viewModel.state.value.url.takeIf { it.isNotEmpty() }?.let(::openExternally)
            })
            add(MenuAction(R.drawable.ic_delete_sweep, R.string.menu_clear_data) { showClearDataDialog() })
            add(MenuAction(R.drawable.ic_logout, R.string.menu_sign_out) { confirmSignOut() })
            accessGate.accountIntent(this@MainActivity)?.let { intent ->
                add(MenuAction(R.drawable.ic_account, R.string.action_account) { startActivity(intent) })
            }
            add(MenuAction(R.drawable.ic_settings, R.string.action_settings) {
                settingsLauncher.launch(Intent(this@MainActivity, SettingsActivity::class.java))
            })
        }
        actions.forEach { item ->
            val tile = ItemMenuTileBinding.inflate(layoutInflater, sheet.menuGrid, false)
            tile.tileIcon.setImageResource(item.icon)
            tile.tileLabel.setText(item.label)
            tile.root.setOnClickListener {
                dialog.dismiss()
                item.action()
            }
            sheet.menuGrid.addView(tile.root)
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun confirmRenderingModeSwitch() {
        val toDesktop = !settings.desktopMode
        MaterialAlertDialogBuilder(this)
            .setTitle(if (toDesktop) R.string.mode_switch_desktop_title else R.string.mode_switch_mobile_title)
            .setMessage(R.string.mode_switch_message)
            .setPositiveButton(R.string.mode_switch_confirm) { _, _ -> applyRenderingMode(toDesktop, reload = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyRenderingMode(desktop: Boolean, reload: Boolean) {
        settings.desktopMode = desktop
        viewModel.setDesktopMode(desktop)
        controller.applyRenderingMode(binding.webView, desktop)
        popups.forEach { controller.applyRenderingMode(it, desktop) }
        if (reload) controller.reload()
    }

    /** Applies preferences that may have changed in SettingsActivity. */
    private fun applyChangedSettings() {
        controller.refreshTrustedHosts()
        controller.applyPreferences(binding.webView)
        popups.forEach(controller::applyPreferences)
        requestedOrientation = settings.orientation
        val desktop = settings.desktopMode
        if (desktop != viewModel.state.value.desktopMode) applyRenderingMode(desktop, reload = true)
    }

    private fun setFullscreen(fullscreen: Boolean) {
        if (fullscreen) clearAddressFocus()
        viewModel.setFullscreen(fullscreen)
        if (fullscreen) showSnackbar(R.string.fullscreen_hint)
    }

    private fun showClearDataDialog() {
        val labels = resources.getStringArray(R.array.clear_data_options)
        val checked = booleanArrayOf(true, false, true, false)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.clear_data_confirm) { _, _ ->
                val options = SessionManager.ClearOptions(
                    cache = checked[0], cookies = checked[1], history = checked[2], siteData = checked[3]
                )
                if (options.isEmpty) return@setPositiveButton
                if (options.signsOut) confirmSignOutClear(options) else clearData(options)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmSignOutClear(options: SessionManager.ClearOptions) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_session_warning_title)
            .setMessage(R.string.clear_session_warning_message)
            .setPositiveButton(R.string.clear_data_confirm) { _, _ -> clearData(options) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_out_title)
            .setMessage(R.string.sign_out_message)
            .setPositiveButton(R.string.sign_out_confirm) { _, _ -> clearData(SessionManager.SIGN_OUT) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearData(options: SessionManager.ClearOptions) {
        sessionManager.clear(binding.webView, options) {
            showSnackbar(R.string.clear_data_done)
            if (options.signsOut) {
                clearHistoryOnNextPage = options.history
                popups.toList().forEach(::closePopup)
                controller.goHome()
            }
        }
    }

    private fun openExternally(url: String) {
        val intent = ExternalIntents.build(this, url)
        if (intent == null) {
            showSnackbar(R.string.external_no_app)
            return
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showSnackbar(R.string.external_no_app)
        }
    }

    // endregion

    // region Downloads

    private fun onDownloadRequested(
        source: WebView,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        startDownload(
            PendingDownload(
                url = url,
                userAgent = userAgent ?: controller.currentUserAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                referer = source.url,
            )
        )
        // Pop-ups opened only to deliver a file would otherwise stay blank.
        if (source in popups && !source.canGoBack()) closePopupLater(source)
    }

    private fun startDownload(download: PendingDownload) {
        when (val result = downloadHandler.enqueue(download)) {
            is DownloadHandler.Result.Started -> {
                requestNotificationPermissionOnce()
                snackbar(getString(R.string.download_started, result.fileName))
                    .setAction(R.string.download_view) { startActivity(DownloadsActivity.intent(this)) }
                    .show()
            }
            DownloadHandler.Result.NeedsStoragePermission -> {
                pendingDownload = download
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            is DownloadHandler.Result.Rejected -> when (result.reason) {
                DownloadPolicy.Rejection.UNTRUSTED_SOURCE -> onExternalNavigation(binding.webView, result.url)
                DownloadPolicy.Rejection.EXECUTABLE -> MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.download_blocked_title)
                    .setMessage(R.string.download_blocked_executable)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                DownloadPolicy.Rejection.UNSUPPORTED_SCHEME -> showSnackbar(R.string.download_unsupported)
                DownloadPolicy.Rejection.INVALID_URL -> showSnackbar(R.string.download_invalid)
            }
        }
    }

    /** Android 13+: ask once, at the first download, so progress and "tap to open" notifications can show. */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)) return
        prefs.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Completed downloads offer Open (PDFs open in the built-in viewer); failures say why. */
    private fun onDownloadEvent(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.Completed -> snackbar(getString(R.string.download_completed, event.record.fileName))
                .setAction(R.string.download_open) { DocumentOpener.open(this, event.record) }
                .show()
            is DownloadEvent.Failed -> snackbar(DocumentOpener.failureText(this, event.record))
                .setAction(R.string.download_view) { startActivity(DownloadsActivity.intent(this)) }
                .show()
            is DownloadEvent.Progress -> Unit
        }
    }

    // endregion

    // region BrowserWebViewClient.Host

    override fun onNavigationStarted(view: WebView, url: String) {
        if (view === binding.webView) {
            viewModel.onPageStarted(url, isSecure(url))
        } else {
            binding.popupTitle.text = UrlValidator.displayHost(url)
        }
    }

    override fun onNavigationFinished(view: WebView, url: String) {
        touchHover.onPageFinished(view, url)
        if (view === binding.webView) {
            if (clearHistoryOnNextPage) {
                clearHistoryOnNextPage = false
                view.clearHistory()
            }
            viewModel.onPageFinished(url, view.title.orEmpty(), isSecure(url), view.canGoBack(), view.canGoForward())
        } else if (view === popups.lastOrNull()) {
            binding.popupTitle.text = view.title?.takeIf { it.isNotBlank() } ?: UrlValidator.displayHost(url)
        }
    }

    override fun onHistoryChanged(view: WebView, url: String) {
        if (view === binding.webView) {
            viewModel.onHistoryChanged(url, isSecure(url), view.canGoBack(), view.canGoForward())
        }
    }

    override fun onMainFrameError(view: WebView, errorCode: Int) {
        val error = PageError.fromWebViewError(errorCode, isOffline())
        if (view === binding.webView) viewModel.onError(error) else showSnackbar(R.string.popup_load_failed)
    }

    override fun onMainFrameHttpError(view: WebView, statusCode: Int) {
        if (view !== binding.webView || statusCode < 500) return
        snackbar(getString(R.string.error_server, statusCode))
            .setAction(R.string.action_retry) { controller.reload() }
            .show()
    }

    override fun onSslError(view: WebView, url: String?) {
        val pageHost = (view.url ?: viewModel.state.value.url).let(UrlValidator::displayHost)
        val isMainResource = url != null && UrlValidator.displayHost(url) == pageHost
        if (view === binding.webView && isMainResource) {
            viewModel.onError(PageError.SSL)
        } else {
            showSnackbar(R.string.error_ssl_resource)
        }
    }

    override fun onExternalNavigation(view: WebView, url: String) {
        if (view !== binding.webView && !view.canGoBack()) closePopupLater(view)
        if (externalDialogShowing) return
        externalDialogShowing = true
        val target = when {
            url.startsWith("intent:", ignoreCase = true) -> getString(R.string.external_target_app)
            url.startsWith("mailto:", ignoreCase = true) || url.startsWith("tel:", ignoreCase = true) ->
                url.substringBefore('?')
            else -> UrlValidator.displayHost(url)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.external_title)
            .setMessage(getString(R.string.external_message, target))
            .setPositiveButton(R.string.external_open) { _, _ -> openExternally(url) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { externalDialogShowing = false }
            .show()
    }

    override fun onNavigationBlocked(view: WebView, reason: UrlValidator.BlockReason) {
        showSnackbar(
            when (reason) {
                UrlValidator.BlockReason.CREDENTIALS_IN_URL -> R.string.blocked_credentials
                UrlValidator.BlockReason.UNSUPPORTED_SCHEME -> R.string.blocked_scheme
                UrlValidator.BlockReason.INVALID_URL -> R.string.blocked_invalid
            }
        )
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        var answered = false
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.resubmit_title)
            .setMessage(R.string.resubmit_message)
            .setPositiveButton(R.string.resubmit_confirm) { _, _ ->
                answered = true
                resend.sendToTarget()
            }
            .setNegativeButton(R.string.resubmit_cancel, null)
            .setOnDismissListener { if (!answered) dontResend.sendToTarget() }
            .show()
    }

    /** Lets the user pick an installed client certificate if the portal asks for one. */
    override fun onClientCertRequest(view: WebView, request: ClientCertRequest) {
        if (!UrlValidator.isTrustedSecureUrl("https://${request.host}/", controller.trustedHosts) ||
            (request.port != 443 && request.port != -1)
        ) {
            request.cancel()
            return
        }
        val appContext = applicationContext
        KeyChain.choosePrivateKeyAlias(
            this,
            { alias ->
                if (alias == null) {
                    request.cancel()
                    return@choosePrivateKeyAlias
                }
                Thread {
                    try {
                        val key: PrivateKey? = KeyChain.getPrivateKey(appContext, alias)
                        val chain: Array<X509Certificate>? = KeyChain.getCertificateChain(appContext, alias)
                        if (key != null && chain != null) request.proceed(key, chain) else request.cancel()
                    } catch (e: Exception) {
                        SafeLog.w(TAG, "Client certificate unavailable", e)
                        request.cancel()
                    }
                }.start()
            },
            request.keyTypes, request.principals, request.host, request.port, null
        )
    }

    override fun onRenderProcessGone(view: WebView): Boolean {
        if (view !== binding.webView) {
            closePopup(view)
            showSnackbar(R.string.popup_crashed)
            return true
        }
        // The renderer died: this WebView can no longer be used. Rebuild the activity.
        webViewGone = true
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        Toast.makeText(this, R.string.renderer_crashed, Toast.LENGTH_LONG).show()
        recreate()
        return true
    }

    // endregion

    // region BrowserChromeClient.Host

    override fun onProgress(view: WebView, progress: Int) {
        if (view === binding.webView) viewModel.onProgress(progress)
    }

    override fun onTitle(view: WebView, title: String) {
        if (view === binding.webView) {
            viewModel.onTitle(title)
        } else if (view === popups.lastOrNull() && title.isNotBlank()) {
            binding.popupTitle.text = title
        }
    }

    override fun showFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean = fileChooser.launch(callback, params)

    override fun createPopup(opener: WebView): WebView? {
        if (popups.size >= MAX_POPUPS) {
            showSnackbar(R.string.popup_limit)
            return null
        }
        val popup = WebView(this)
        setUpWebView(popup)
        binding.popupContainer.addView(
            popup, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        popups += popup
        binding.popupTitle.setText(R.string.popup_loading)
        if (!binding.popupLayer.isVisible) {
            binding.popupLayer.translationY = binding.contentFrame.height * 0.15f
            binding.popupLayer.alpha = 0f
            binding.popupLayer.isVisible = true
            binding.popupLayer.animate().translationY(0f).alpha(1f).setDuration(FADE_MS).start()
        }
        return popup
    }

    override fun closePopup(window: WebView) {
        if (!popups.remove(window)) return
        window.stopLoading()
        binding.popupContainer.removeView(window)
        window.destroy()
        val top = popups.lastOrNull()
        binding.popupLayer.isVisible = top != null
        if (top != null) binding.popupTitle.text = top.title ?: UrlValidator.displayHost(top.url.orEmpty())
    }

    /** Never destroy a WebView from inside one of its own callbacks. */
    private fun closePopupLater(window: WebView) {
        binding.root.post { closePopup(window) }
    }

    override fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        binding.customViewContainer.addView(view)
        binding.customViewContainer.isVisible = true
        updateSystemBars(hidden = true)
    }

    override fun hideCustomView() {
        val view = customView ?: return
        binding.customViewContainer.removeView(view)
        binding.customViewContainer.isVisible = false
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        updateSystemBars(hidden = viewModel.state.value.fullscreen)
    }

    // endregion

    // region Access and first run

    /** Locks or unlocks the browser according to the flavor's access gate (always open in the Play build). */
    private fun applyAccess() {
        when (val decision = accessGate.current()) {
            AccessDecision.Allowed -> {
                fade(binding.lockedView.root, false)
                pendingInitialUrl?.let {
                    pendingInitialUrl = null
                    controller.load(it)
                }
            }
            is AccessDecision.Locked -> showLocked(decision.reason)
        }
    }

    private fun showLocked(reason: LockReason) = with(binding.lockedView) {
        val (title, message, action) = when (reason) {
            LockReason.SIGN_IN_REQUIRED ->
                Triple(R.string.locked_welcome_title, R.string.locked_welcome_message, R.string.locked_action_start)
            LockReason.TRIAL_NOT_STARTED ->
                Triple(R.string.locked_trial_title, R.string.locked_trial_message, R.string.locked_action_trial)
            LockReason.TRIAL_EXPIRED ->
                Triple(R.string.locked_expired_title, R.string.locked_expired_message, R.string.locked_action_buy)
            LockReason.VERIFY_ONLINE ->
                Triple(R.string.locked_verify_title, R.string.locked_verify_message, R.string.locked_action_verify)
        }
        lockedTitle.setText(title)
        lockedMessage.setText(message)
        lockedAction.setText(action)
        lockedAction.setOnClickListener {
            accessGate.accountIntent(this@MainActivity)?.let(::startActivity)
        }
        controller.stop()
        fade(root, true)
    }

    /** Independent-app notice, shown once on first launch (both builds). */
    private fun showDisclaimerIfNeeded() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)) return
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.brand_logo)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_message)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ ->
                prefs.edit { putBoolean(KEY_DISCLAIMER_ACCEPTED, true) }
            }
            .show()
    }

    private fun fade(view: View, visible: Boolean) {
        if (view.isVisible == visible && view.alpha == 1f) return
        view.animate().cancel()
        if (visible) {
            view.alpha = 0f
            view.isVisible = true
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        } else if (view.isVisible) {
            view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                view.isVisible = false
                view.alpha = 1f
            }.start()
        }
    }

    // endregion

    // region Helpers

    private fun isSecure(url: String) = UrlValidator.isTrustedSecureUrl(url, controller.trustedHosts)

    /** Accepts only VIEW intents for trusted e-GP URLs; anything else opens the home page. */
    private fun trustedUrlFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val url = intent.dataString ?: return null
        return when (val decision = UrlValidator.decide(url, controller.trustedHosts)) {
            is UrlValidator.Decision.Allow -> decision.url.takeIf { it.startsWith("https:", ignoreCase = true) }
            is UrlValidator.Decision.Upgrade -> decision.httpsUrl
            else -> null
        }
    }

    private fun showSnackbar(@StringRes message: Int) = snackbar(getString(message)).show()

    private fun snackbar(text: CharSequence): Snackbar =
        Snackbar.make(binding.contentFrame, text, Snackbar.LENGTH_LONG).apply {
            if (binding.bottomBar.isVisible) anchorView = binding.bottomBar
        }

    private fun isOffline(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        viewModel.setOffline(isOffline())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = runOnUiThread {
                val wasOffline = viewModel.state.value.offline
                viewModel.setOffline(false)
                // Retry automatically once connectivity returns after a network failure.
                if (wasOffline && viewModel.state.value.error?.isNetworkRelated == true) reloadOrRetry()
            }

            override fun onLost(network: Network) = runOnUiThread { viewModel.setOffline(isOffline()) }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        networkCallback = null
    }

    // endregion

    companion object {
        private const val TAG = "MainActivity"
        private const val MAX_POPUPS = 3
        private const val FADE_MS = 220L
        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted_v1"
        private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked_v1"
    }
}
