package com.vidgrab.app.engine

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class EngineState {
    data object Idle : EngineState()
    data object Starting : EngineState()
    data object Ready : EngineState()
    data class Failed(val report: String) : EngineState()
}

object Engine {
    private val ready = CompletableDeferred<Result<Unit>>()
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        _state.value = EngineState.Starting
        Thread {
            val result = runCatching {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
            }
            if (result.isSuccess) {
                _state.value = EngineState.Ready
            } else {
                _state.value = EngineState.Failed(describe(result.exceptionOrNull()))
            }
            ready.complete(result)
        }.start()
    }

    suspend fun await(): Result<Unit> = ready.await()

    fun describe(t: Throwable?): String {
        if (t == null) return "unknown failure"
        return buildString {
            var cause: Throwable? = t
            var depth = 0
            while (cause != null && depth < 6) {
                append(cause.javaClass.simpleName)
                append(": ")
                appendLine(cause.message ?: "(no message)")
                cause = cause.cause
                depth++
            }
            appendLine()
            append(t.stackTraceToString().take(3000))
        }
    }
}
