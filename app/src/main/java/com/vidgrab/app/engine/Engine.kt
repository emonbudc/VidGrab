package com.vidgrab.app.engine

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred

object Engine {
    private val ready = CompletableDeferred<Result<Unit>>()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread {
            val result = runCatching {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
            }
            ready.complete(result)
        }.start()
    }

    suspend fun await(): Result<Unit> = ready.await()
}
