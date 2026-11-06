package com.vidgrab.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidgrab.app.download.DownloadService
import com.vidgrab.app.engine.Engine
import com.vidgrab.app.engine.YtDlpUpdater
import com.vidgrab.app.model.Quality
import com.vidgrab.app.model.VideoInfoUi
import com.vidgrab.app.util.isHttpUrl
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val url: String = "",
        val loading: Boolean = false,
        val info: VideoInfoUi? = null,
        val error: String? = null,
        val selected: Quality = Quality.BEST
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    private var fetchJob: Job? = null

    fun onUrlChange(value: String) {
        _ui.update { it.copy(url = value, error = null) }
    }

    fun selectQuality(quality: Quality) {
        _ui.update { it.copy(selected = quality) }
    }

    fun loadUrl(url: String) {
        _ui.update { it.copy(url = url, error = null) }
        fetch()
    }

    fun fetch() {
        val url = _ui.value.url.trim()
        if (url.isBlank()) {
            _ui.update { it.copy(error = "Paste a link first.") }
            return
        }
        if (!isHttpUrl(url)) {
            _ui.update { it.copy(error = "That does not look like a video link.") }
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, info = null) }
            try {
                Engine.await().getOrThrow()
                val info = withContext(Dispatchers.IO) { YoutubeDL.getInstance().getInfo(url) }
                showInfo(info, url)
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                var finalError = t
                if (shouldTryEngineUpdate()) {
                    val updated = withTimeoutOrNull(45_000) {
                        runCatching {
                            YtDlpUpdater.update(getApplication(), YtDlpUpdater.Channel.STABLE)
                        }.getOrNull()
                    }
                    if (updated != null) {
                        try {
                            val info = withContext(Dispatchers.IO) { YoutubeDL.getInstance().getInfo(url) }
                            showInfo(info, url)
                            return@launch
                        } catch (again: Throwable) {
                            if (again is CancellationException) return@launch
                            finalError = again
                        }
                    }
                }
                _ui.update { it.copy(loading = false, error = messageOf(finalError)) }
            }
        }
    }

    private fun showInfo(info: VideoInfo, url: String) {
        val title = info.title ?: info.fulltitle ?: url
        _ui.update {
            it.copy(
                loading = false,
                info = VideoInfoUi(
                    title = title,
                    thumbnail = info.thumbnail,
                    durationSeconds = info.duration,
                    uploader = info.uploader,
                    webpageUrl = info.webpageUrl ?: url
                )
            )
        }
    }

    private fun shouldTryEngineUpdate(): Boolean {
        val prefs = getApplication<Application>()
            .getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
        return prefs.getString("dlpVersion", null) == null
    }

    fun download(quality: Quality) {
        val state = _ui.value
        val url = state.url.trim()
        if (url.isBlank()) return
        DownloadService.start(getApplication(), url, state.info?.title, quality)
    }

    private fun messageOf(t: Throwable): String {
        val raw = t.message ?: t.javaClass.simpleName
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return (lines.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: raw).take(900)
    }
}
