package com.vidgrab.app.core.playerui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import com.vidgrab.app.core.media.PlayerController

/**
 * The media player's video view. It attaches while the screen is started and detaches on stop, so only one part of
 * the app draws the video at a time while playback itself keeps running.
 */
@Composable
fun MediaVideoSurface(controller: PlayerController, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var view by remember { mutableStateOf<PlayerView?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    started = true
                    view?.let { controller.attachVideo(it) }
                }
                Lifecycle.Event.ON_STOP -> {
                    started = false
                    view?.let { controller.detachVideo(it) }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { context ->
            PlayerView(context).also { playerView ->
                playerView.useController = false
                playerView.setBackgroundColor(Color.BLACK)
                view = playerView
                if (started) controller.attachVideo(playerView)
            }
        },
        modifier = modifier,
        onRelease = { playerView ->
            view = null
            controller.detachVideo(playerView)
        },
    )
}
