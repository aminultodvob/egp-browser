package bd.egpmobile.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BrowserUiState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** HTTPS on a trusted host with a certificate WebView accepted (certificate errors never load). */
    val secure: Boolean = false,
    val error: PageError? = null,
    val offline: Boolean = false,
    val desktopMode: Boolean = true,
    val fullscreen: Boolean = false,
)

/** UI state for the main browser window. The WebView itself stays owned by the activity. */
class BrowserViewModel : ViewModel() {

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    fun onPageStarted(url: String, secure: Boolean) = _state.update {
        it.copy(url = url, secure = secure, loading = true, progress = 0, error = null)
    }

    fun onPageFinished(url: String, title: String, secure: Boolean, canGoBack: Boolean, canGoForward: Boolean) =
        _state.update {
            it.copy(
                url = url, title = title, secure = secure, loading = false, progress = 100,
                canGoBack = canGoBack, canGoForward = canGoForward,
            )
        }

    fun onProgress(progress: Int) = _state.update { it.copy(progress = progress) }

    fun onTitle(title: String) = _state.update { it.copy(title = title) }

    fun onHistoryChanged(url: String, secure: Boolean, canGoBack: Boolean, canGoForward: Boolean) = _state.update {
        it.copy(url = url, secure = secure, canGoBack = canGoBack, canGoForward = canGoForward)
    }

    fun onError(error: PageError) = _state.update { it.copy(error = error, loading = false) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun setOffline(offline: Boolean) = _state.update { it.copy(offline = offline) }

    fun setDesktopMode(desktop: Boolean) = _state.update { it.copy(desktopMode = desktop) }

    fun setFullscreen(fullscreen: Boolean) = _state.update { it.copy(fullscreen = fullscreen) }
}
