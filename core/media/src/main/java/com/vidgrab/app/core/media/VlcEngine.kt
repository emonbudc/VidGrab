package com.vidgrab.app.core.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/** The state libVLC reports, mapped to the small set of values the app's player UI needs. */
data class VlcSnapshot(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val ended: Boolean = false,
    val timeMs: Long = 0,
    val lengthMs: Long = 0,
    val bufferingPercent: Int = 0,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: String? = null,
    val hasVideo: Boolean = false,
)

/**
 * The app's single libVLC player. It plays one media at a time; [PlayerController] owns the queue around it.
 * The video surface can be attached and detached as screens come and go; playback (and audio) continues without one.
 */
class VlcEngine private constructor(private val context: Context) {
    private val _snapshot = MutableStateFlow(VlcSnapshot())
    val snapshot: StateFlow<VlcSnapshot> = _snapshot.asStateFlow()

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var currentMedia: Media? = null
    private var currentItem: PlayItem? = null
    private var currentFd: ParcelFileDescriptor? = null
    private var subtitleFile: File? = null
    private var audioOnly = false
    private var attached: VLCVideoLayout? = null
    private var activeEqualizer: MediaPlayer.Equalizer? = null
    private var videoRetry = 0
    private var continueAfterDetach = false

    val currentTimeMs: Long get() = _snapshot.value.timeMs.coerceAtLeast(0)
    val currentLengthMs: Long get() = _snapshot.value.lengthMs.coerceAtLeast(0)

    fun play(item: PlayItem, startMs: Long, speed: Float, audioOnly: Boolean) {
        val p = ensurePlayer()
        this.audioOnly = audioOnly
        videoRetry++
        pause()
        releaseCurrentMedia()
        currentItem = null
        _snapshot.value = VlcSnapshot(speed = speed, timeMs = startMs.coerceAtLeast(0))

        val uri = Uri.parse(item.uri)
        val media = runCatching { createMedia(requireNotNull(libVlc), uri) }.getOrElse { error ->
            _snapshot.value = VlcSnapshot(error = error.message ?: "The video can't be played.")
            return
        }
        media.setHWDecoderEnabled(true, false)
        val network = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
        if (network) {
            media.addOption(":network-caching=1500")
            media.addOption(":http-user-agent=$USER_AGENT")
        } else {
            media.addOption(":file-caching=1500")
        }
        item.audioUri?.takeIf { it.isNotBlank() }?.let {
            media.addSlave(IMedia.Slave(IMedia.Slave.Type.Audio, SLAVE_PRIORITY, it))
        }
        currentMedia = media
        currentItem = item
        p.setMedia(media)
        p.setRate(speed)
        activeEqualizer?.let { p.setEqualizer(it) }
        p.setVideoTrackEnabled(!audioOnly && attached != null)
        p.play()
        if (startMs > 0) p.setTime(startMs)
        p.setVolume((volume.coerceIn(0f, 1f) * 100).toInt())
    }

    fun play() {
        player?.play()
        player?.setRate(_snapshot.value.speed)
    }

    fun pause() {
        continueAfterDetach = false
        player?.pause()
    }

    fun toggle() {
        if (player?.isPlaying == true) pause() else play()
    }

    fun stop() {
        videoRetry++
        continueAfterDetach = false
        player?.stop()
        releaseCurrentMedia()
        currentItem = null
        _snapshot.value = VlcSnapshot()
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val target = ms.coerceAtLeast(0)
        p.setTime(target)
        _snapshot.value = _snapshot.value.copy(timeMs = target, ended = false)
    }

    fun setSpeed(speed: Float) {
        player?.setRate(speed)
        _snapshot.value = _snapshot.value.copy(speed = speed)
    }

    fun setVolume(fraction: Float) {
        volume = fraction.coerceIn(0f, 1f)
        player?.setVolume((volume * 100).toInt())
    }

    fun setAudioOnly(on: Boolean) {
        audioOnly = on
        val p = player
        if (p == null) {
            if (on) _snapshot.value = _snapshot.value.copy(hasVideo = false)
            return
        }
        p.setVideoTrackEnabled(!on)
        if (on) {
            _snapshot.value = _snapshot.value.copy(hasVideo = false)
            return
        }
        updateVideoSize()
        // Some decoders drop the video output for good when its track is switched off: if the picture doesn't come
        // back shortly after the switch, start the same stream again at the same moment.
        val item = currentItem ?: return
        val token = ++videoRetry
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (token != videoRetry || audioOnly || player !== p) return@postDelayed
            val track = runCatching { p.currentVideoTrack }.getOrNull()
            if (track == null && attached != null) play(item, currentTimeMs, _snapshot.value.speed, false)
        }, 800)
    }

    /** Enables or disables the video track as the screen and the audio-only mode require. */
    fun attachVideo(layout: VLCVideoLayout) {
        val p = ensurePlayer()
        if (attached === layout) return
        if (attached != null) detachVideo(attached!!)
        attached = layout
        runCatching { p.attachViews(layout, null, true, true) }
        p.setVideoTrackEnabled(!audioOnly)
        updateVideoSize()
        _snapshot.value = _snapshot.value.copy(hasVideo = !audioOnly && _snapshot.value.hasVideo)
    }

    fun detachVideo(layout: VLCVideoLayout) {
        if (attached !== layout) return
        val s = _snapshot.value
        continueAfterDetach = !audioOnly && currentItem != null && !s.ended && (s.isPlaying || s.isBuffering)
        attached = null
        runCatching { player?.detachViews() }
        if (!continueAfterDetach) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val p = player ?: return@postDelayed
            if (continueAfterDetach && currentItem != null && !_snapshot.value.ended && !p.isPlaying) {
                p.play()
                p.setTime(s.timeMs)
            }
        }, 250)
    }

    fun addSubtitle(uri: Uri, mimeType: String): Boolean = runCatching {
        val target = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val ext = when (mimeType) {
                "text/vtt" -> "vtt"
                "text/x-ssa", "text/x-ass" -> "ass"
                else -> "srt"
            }
            subtitleFile?.delete()
            File.createTempFile("subtitle-", ".$ext", context.cacheDir).also { file ->
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Can't read the subtitle file.")
                subtitleFile = file
            }.let(Uri::fromFile)
        } else {
            uri
        }
        player?.addSlave(IMedia.Slave.Type.Subtitle, target, true) == true
    }.getOrDefault(false)

    fun audioTracks(): List<AudioTrackInfo> {
        val p = player ?: return emptyList()
        val current = runCatching { p.getAudioTrack() }.getOrDefault(-1)
        return p.getAudioTracks()?.map { AudioTrackInfo(it.name.ifBlank { "Track ${it.id}" }, it.id, it.id == current) }.orEmpty()
    }

    fun selectAudioTrack(id: Int) {
        player?.setAudioTrack(id)
    }

    fun setEqualizer(eq: MediaPlayer.Equalizer?) {
        activeEqualizer = eq
        player?.setEqualizer(eq)
    }

    fun release() {
        videoRetry++
        continueAfterDetach = false
        runCatching { player?.setEventListener(null) }
        runCatching { player?.detachViews() }
        attached = null
        runCatching { player?.release() }
        player = null
        releaseCurrentMedia()
        currentItem = null
        subtitleFile?.delete()
        subtitleFile = null
        libVlc?.release()
        libVlc = null
    }

    private fun createMedia(lib: LibVLC, uri: Uri): Media {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Can't open this file.")
            currentFd = fd
            return Media(lib, fd.fileDescriptor)
        }
        if (uri.scheme == "file") {
            val path = uri.path
            if (path != null) return Media(lib, Uri.fromFile(File(Uri.decode(path))))
        }
        return Media(lib, uri)
    }

    private fun releaseCurrentMedia() {
        currentMedia?.release()
        currentMedia = null
        runCatching { currentFd?.close() }
        currentFd = null
    }

    private fun ensurePlayer(): MediaPlayer {
        player?.let { return it }
        val lib = LibVLC(context.applicationContext, arrayListOf("--no-drop-late-frames", "--no-skip-frames", "--audio-time-stretch"))
        libVlc = lib
        val p = MediaPlayer(lib)
        p.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) = onPlayerEvent(event)
        })
        p.setVolume((volume * 100).toInt())
        player = p
        attached?.let { runCatching { p.attachViews(it, null, true, true) } }
        activeEqualizer?.let { p.setEqualizer(it) }
        return p
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        val p = player ?: return
        when (event.type) {
            MediaPlayer.Event.Opening -> _snapshot.value = _snapshot.value.copy(
                isBuffering = true, ended = false, error = null,
            )
            MediaPlayer.Event.Buffering -> {
                val percent = event.buffering.coerceIn(0f, 100f).toInt()
                val cur = _snapshot.value
                _snapshot.value = cur.copy(
                    isBuffering = percent < 100,
                    bufferingPercent = percent,
                )
            }
            MediaPlayer.Event.Playing -> {
                val cur = _snapshot.value
                _snapshot.value = cur.copy(
                    isPlaying = true, isBuffering = false, ended = false, error = null,
                    timeMs = p.getTime().coerceAtLeast(0),
                    lengthMs = p.getLength().coerceAtLeast(0),
                    speed = p.getRate(),
                )
                p.setVideoTrackEnabled(!audioOnly && attached != null)
                updateVideoSize()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateVideoSize() }, 700)
            }
            MediaPlayer.Event.Paused -> _snapshot.value = _snapshot.value.copy(
                isPlaying = false, isBuffering = false, timeMs = p.getTime().coerceAtLeast(0),
            )
            MediaPlayer.Event.Stopped -> _snapshot.value = _snapshot.value.copy(
                isPlaying = false, isBuffering = false, timeMs = p.getTime().coerceAtLeast(0),
            )
            MediaPlayer.Event.EndReached -> {
                val cur = _snapshot.value
                _snapshot.value = cur.copy(
                    isPlaying = false, isBuffering = false, ended = true,
                    timeMs = cur.lengthMs.coerceAtLeast(p.getTime().coerceAtLeast(0)),
                )
            }
            MediaPlayer.Event.EncounteredError -> _snapshot.value = _snapshot.value.copy(
                isPlaying = false, isBuffering = false, ended = false,
                error = "The video can't be played.",
            )
            MediaPlayer.Event.TimeChanged -> {
                val t = event.timeChanged.coerceAtLeast(0)
                val cur = _snapshot.value
                val ended = cur.ended && (cur.lengthMs <= 0 || t >= cur.lengthMs - 1_000)
                if (t != cur.timeMs || ended != cur.ended) _snapshot.value = cur.copy(timeMs = t, ended = ended)
            }
            MediaPlayer.Event.LengthChanged -> {
                val length = event.lengthChanged.coerceAtLeast(0)
                if (length > 0) {
                    val cur = _snapshot.value
                    _snapshot.value = cur.copy(
                        lengthMs = length,
                        ended = cur.ended && cur.timeMs >= length - 1_000,
                    )
                }
            }
            MediaPlayer.Event.PositionChanged -> {
                val length = _snapshot.value.lengthMs
                if (length > 0) {
                    val t = (event.positionChanged.coerceIn(0f, 1f) * length).toLong()
                    _snapshot.value = _snapshot.value.copy(timeMs = t)
                }
            }
            MediaPlayer.Event.Vout -> updateVideoSize()
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> updateVideoSize()
        }
    }

    private fun updateVideoSize() {
        val p = player ?: return
        val video = runCatching { p.currentVideoTrack }.getOrNull() ?: currentMedia?.let { m ->
            (0 until m.trackCount).firstOrNull { m.getTrack(it)?.type == IMedia.Track.Type.Video }?.let { i ->
                m.getTrack(i) as? IMedia.VideoTrack
            }
        }
        if (video == null) return
        val rotated = video.orientation == IMedia.VideoTrack.Orientation.LeftBottom ||
            video.orientation == IMedia.VideoTrack.Orientation.RightTop
        val width = if (rotated) video.height else video.width
        val height = if (rotated) video.width else video.height
        if (width > 0 && height > 0) {
            _snapshot.value = _snapshot.value.copy(videoWidth = width, videoHeight = height, hasVideo = true)
        }
    }

    private var volume: Float = 1f

    data class AudioTrackInfo(val label: String, val id: Int, val selected: Boolean)

    companion object {
        private const val SLAVE_PRIORITY = 4
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        @Volatile
        private var instance: VlcEngine? = null

        fun get(context: Context): VlcEngine =
            instance ?: synchronized(this) {
                instance ?: VlcEngine(context.applicationContext).also { instance = it }
            }
    }
}
