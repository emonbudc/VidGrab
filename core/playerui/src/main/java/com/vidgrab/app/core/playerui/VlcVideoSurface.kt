package com.vidgrab.app.core.playerui

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
import com.vidgrab.app.core.media.PlayerController
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * The libVLC video surface. It attaches while the screen is started and detaches on stop, so only one part of the
 * app draws the video at a time while playback itself keeps running.
 */
@Composable
fun VlcVideoSurface(controller: PlayerController, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var layout by remember { mutableStateOf<VLCVideoLayout?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    started = true
                    layout?.let { controller.attachVideo(it) }
                }
                Lifecycle.Event.ON_STOP -> {
                    started = false
                    layout?.let { controller.detachVideo(it) }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { context ->
            VLCVideoLayout(context).also { view ->
                view.setBackgroundColor(android.graphics.Color.BLACK)
                layout = view
                if (started) controller.attachVideo(view)
            }
        },
        modifier = modifier,
        onRelease = { view ->
            layout = null
            controller.detachVideo(view)
        },
    )
}
