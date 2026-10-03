package app.siphon.ui.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.siphon.SiphonApp
import app.siphon.data.DownloadOptions
import app.siphon.data.Preset
import app.siphon.engine.Engine
import app.siphon.engine.MediaFormat
import app.siphon.engine.MediaInfo
import app.siphon.data.ProgressParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface FetchState {
    data object Idle : FetchState
    data object Loading : FetchState
    data class Loaded(val info: MediaInfo) : FetchState
    data class Error(val message: String) : FetchState
}

data class DownloadUiState(
    val url: String = "",
    val playlist: Boolean = false,
    val fetch: FetchState = FetchState.Idle,
    val options: DownloadOptions = DownloadOptions(),
    val showFormats: Boolean = false,
) {
    val looksLikePlaylist get() = url.contains("list=") || url.contains("/playlist") ||
        (fetch as? FetchState.Loaded)?.info?.isPlaylist == true
}

class DownloadViewModel(app: Application) : AndroidViewModel(app) {
    private val siphon = app as SiphonApp
    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state
    private var fetchJob: Job? = null

    /** One-shot messages for the snackbar. */
    val messages = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { resetOptions() }
    }

    private suspend fun resetOptions() {
        val defaults = siphon.settings.settings.first().toOptions()
        _state.update { it.copy(options = defaults) }
    }

    fun setUrl(url: String) {
        _state.update {
            if (it.url == url) it else it.copy(url = url, fetch = FetchState.Idle, showFormats = false,
                options = it.options.copy(formatId = null, preset = if (it.options.preset == Preset.Custom) Preset.Best else it.options.preset))
        }
    }

    /** Text shared from another app or pasted: pull the first URL out of it and fetch right away. */
    fun acceptText(text: String) {
        val url = URL_RE.find(text)?.value ?: text.trim()
        if (url.isBlank()) return
        setUrl(url)
        fetch()
    }

    fun setPlaylist(on: Boolean) {
        _state.update { it.copy(playlist = on, options = it.options.copy(playlist = on)) }
        if (_state.value.fetch !is FetchState.Idle) fetch()
    }

    fun fetch() {
        val s = _state.value
        if (s.url.isBlank()) return
        fetchJob?.cancel()
        _state.update { it.copy(fetch = FetchState.Loading, showFormats = false) }
        fetchJob = viewModelScope.launch {
            val result = try {
                FetchState.Loaded(Engine.fetchInfo(s.url.trim(), s.playlist))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                FetchState.Error(ProgressParser.summarizeError(t.message.orEmpty()) ?: "Could not read this link")
            }
            _state.update { if (it.url == s.url) it.copy(fetch = result) else it }
        }
    }

    fun selectPreset(p: Preset) = _state.update { it.copy(options = it.options.copy(preset = p, formatId = null)) }

    fun selectFormat(f: MediaFormat) = _state.update {
        it.copy(options = it.options.copy(
            preset = Preset.Custom, formatId = f.id, formatIsVideoOnly = f.kind == MediaFormat.Kind.VideoOnly,
        ))
    }

    fun toggleFormats() = _state.update { it.copy(showFormats = !it.showFormats) }

    fun updateOptions(f: (DownloadOptions) -> DownloadOptions) = _state.update { it.copy(options = f(it.options)) }

    fun download() {
        val s = _state.value
        val url = s.url.trim()
        if (url.isBlank()) return
        val info = (s.fetch as? FetchState.Loaded)?.info
        siphon.downloads.enqueue(url, info, s.options.copy(playlist = s.playlist))
        messages.value = "Added “${info?.title ?: url}” to queue"
        fetchJob?.cancel()
        _state.update { DownloadUiState(options = it.options) }
        viewModelScope.launch { resetOptions() }
    }

    companion object {
        private val URL_RE = Regex("""https?://[^\s<>"']+""")
    }
}
