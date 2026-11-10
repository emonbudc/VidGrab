package com.vidgrab.app.core.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.vidgrab.app.core.common.BrowserSession
import com.vidgrab.app.core.model.SubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The state the media player reports, mapped to the small set of values the app's player UI needs. */
data class MediaSnapshot(
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
 * The app's single ExoPlayer instance. It plays one media at a time; [PlayerController] owns the queue around it.
 * The video view can be attached and detached as screens come and go; playback (and audio) continues without one.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MediaEngine private constructor(private val context: Context) {
    private val _snapshot = MutableStateFlow(MediaSnapshot())
    val snapshot: StateFlow<MediaSnapshot> = _snapshot.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
    // The signed-in browser session only applies to Google/YouTube URLs; every other source stays anonymous.
    private val dataSourceFactory = ResolvingDataSource.Factory(DefaultDataSource.Factory(context, httpDataSourceFactory)) { spec ->
        val session = BrowserSession.headers(spec.uri.toString())
        if (session.isEmpty()) spec else spec.withRequestHeaders(session)
    }
    private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

    private var player: ExoPlayer? = null
    private var currentItem: PlayItem? = null
    private var selectedSubtitle: SubtitleTrack? = null
    private var audioOnly = false
    private val views = mutableListOf<PlayerView>()
    private var volume = 1f

    /** The current audio session, for Android's [android.media.audiofx.Equalizer]. */
    @Volatile var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
        private set

    val currentTimeMs: Long get() = _snapshot.value.timeMs.coerceAtLeast(0)
    val currentLengthMs: Long get() = _snapshot.value.lengthMs.coerceAtLeast(0)

    /** Some caption files place cues at the left edge (ASS/WebVTT positioning); force every cue to the center. */
    private val centerSubtitlesListener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            val centered = cueGroup.cues.map { cue ->
                cue.buildUpon()
                    .setPosition(Cue.DIMEN_UNSET)
                    .setPositionAnchor(Cue.TYPE_UNSET)
                    .setTextAlignment(null)
                    .build()
            }
            views.forEach { it.subtitleView?.setCues(centered) }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            val cur = _snapshot.value
            _snapshot.value = when (playbackState) {
                Player.STATE_BUFFERING -> cur.copy(
                    isBuffering = true, isPlaying = p.isPlaying, ended = false, error = null,
                    timeMs = p.currentPosition.coerceAtLeast(0),
                )
                Player.STATE_READY -> cur.copy(
                    isBuffering = false, isPlaying = p.isPlaying, ended = false, error = null,
                    timeMs = p.currentPosition.coerceAtLeast(0),
                    lengthMs = duration(p),
                    bufferingPercent = p.bufferedPercentage,
                )
                Player.STATE_ENDED -> cur.copy(
                    isPlaying = false, isBuffering = false, ended = true, bufferingPercent = 100,
                    timeMs = cur.lengthMs.coerceAtLeast(p.currentPosition.coerceAtLeast(0)),
                )
                else -> cur.copy(isPlaying = p.isPlaying)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            _snapshot.value = _snapshot.value.copy(
                isPlaying = isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                ended = p.playbackState == Player.STATE_ENDED,
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            _snapshot.value = _snapshot.value.copy(
                isPlaying = false, isBuffering = false, ended = false,
                error = "The video can't be played.",
            )
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val turned = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
            _snapshot.value = _snapshot.value.copy(
                videoWidth = if (turned) videoSize.height else videoSize.width,
                videoHeight = if (turned) videoSize.width else videoSize.height,
                hasVideo = !audioOnly,
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            val hasVideo = tracks.groups.any { group ->
                group.type == C.TRACK_TYPE_VIDEO && group.isSupported &&
                    (0 until group.length).any { group.isTrackSelected(it) }
            }
            _snapshot.value = _snapshot.value.copy(hasVideo = hasVideo && !audioOnly)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            this@MediaEngine.audioSessionId = audioSessionId
        }
    }

    init {
        scope.launch {
            while (isActive) {
                delay(500)
                publishProgress()
            }
        }
    }

    fun play(item: PlayItem, startMs: Long, speed: Float, audioOnly: Boolean, subtitle: SubtitleTrack? = null) {
        selectedSubtitle = subtitle
        load(item, startMs, speed, audioOnly)
    }

    private fun load(item: PlayItem, startMs: Long, speed: Float, audioOnly: Boolean) {
        val p = ensurePlayer()
        this.audioOnly = audioOnly
        currentItem = item
        _snapshot.value = MediaSnapshot(speed = speed, timeMs = startMs.coerceAtLeast(0))
        val source = runCatching { buildSource(item, selectedSubtitle) }.getOrElse { error ->
            _snapshot.value = MediaSnapshot(error = error.message ?: "The video can't be played.")
            return
        }
        runCatching {
            p.stop()
            if (startMs > 0) p.setMediaSource(source, startMs) else p.setMediaSource(source)
            p.setPlaybackSpeed(speed)
            p.volume = volume
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
                .build()
            p.prepare()
            p.playWhenReady = true
        }.onFailure { error ->
            _snapshot.value = MediaSnapshot(error = error.message ?: "The video can't be played.")
        }
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun toggle() {
        if (player?.isPlaying == true) pause() else play()
    }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        currentItem = null
        selectedSubtitle = null
        _snapshot.value = MediaSnapshot()
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val target = ms.coerceAtLeast(0)
        p.seekTo(target)
        _snapshot.value = _snapshot.value.copy(timeMs = target, ended = false)
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        _snapshot.value = _snapshot.value.copy(speed = speed)
    }

    fun setVolume(fraction: Float) {
        volume = fraction.coerceIn(0f, 1f)
        player?.volume = volume
    }

    fun setAudioOnly(on: Boolean) {
        audioOnly = on
        val p = player ?: run {
            if (on) _snapshot.value = _snapshot.value.copy(hasVideo = false)
            return
        }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on)
            .build()
        if (on) _snapshot.value = _snapshot.value.copy(hasVideo = false)
    }

    /** Attaches a view for the picture; the most recently attached view owns the surface. */
    fun attachVideo(view: PlayerView) {
        val p = ensurePlayer()
        if (views.lastOrNull() === view && view.player === p) return
        views.remove(view)
        views.add(view)
        view.useController = false
        activate(view)
    }

    fun detachVideo(view: PlayerView) {
        val current = views.lastOrNull() === view
        views.remove(view)
        view.player = null
        if (current) views.lastOrNull()?.let(::activate)
    }

    private fun activate(view: PlayerView) {
        view.player = null
        val p = ensurePlayer()
        view.player = p
        // PlayerView adds its cue listener in setPlayer; keep ours last so it can override cue positions.
        p.removeListener(centerSubtitlesListener)
        p.addListener(centerSubtitlesListener)
    }

    fun selectSubtitle(track: SubtitleTrack?): Boolean {
        val item = currentItem ?: return false
        selectedSubtitle = track
        load(item, currentTimeMs, _snapshot.value.speed, audioOnly)
        return true
    }

    fun addSubtitle(uri: Uri, mimeType: String): Boolean =
        selectSubtitle(SubtitleTrack("und", "Selected file", uri.toString(), mimeType))

    fun audioTracks(): List<AudioTrackInfo> {
        val p = player ?: return emptyList()
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        return groups.flatMapIndexed { groupIndex, group ->
            (0 until group.length).mapNotNull { trackIndex ->
                val format = runCatching { group.getTrackFormat(trackIndex) }.getOrNull() ?: return@mapNotNull null
                val label = format.label ?: format.language ?: "Track ${groupIndex + 1}"
                AudioTrackInfo(
                    label = label,
                    id = (groupIndex shl 8) or trackIndex,
                    selected = group.isSelected && runCatching { group.isTrackSelected(trackIndex) }.getOrDefault(false),
                )
            }
        }
    }

    fun selectAudioTrack(id: Int) {
        val p = player ?: return
        val groupIndex = id ushr 8
        val trackIndex = id and 0xff
        val group = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(groupIndex) ?: return
        val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(override)
            .build()
    }

    fun release() {
        scope.cancel()
        views.forEach { it.player = null }
        views.clear()
        runCatching { player?.removeListener(listener) }
        runCatching { player?.release() }
        player = null
        currentItem = null
        selectedSubtitle = null
        _snapshot.value = MediaSnapshot()
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context.applicationContext).build()
        p.addListener(listener)
        p.volume = volume
        player = p
        return p
    }

    private fun buildSource(item: PlayItem, subtitle: SubtitleTrack?): MediaSource {
        val mainUri = if (audioOnly) item.audioUri?.takeIf { it.isNotBlank() } ?: item.uri else item.uri
        val mainItem = mediaItem(mainUri, subtitle)
        val separateAudio = if (!audioOnly) item.audioUri?.takeIf { it.isNotBlank() } else null
        if (separateAudio != null && !isAdaptive(mainUri) && !isRtsp(mainUri)) {
            return MergingMediaSource(progressive(mainItem), progressive(mediaItem(separateAudio, null)))
        }
        if (isRtsp(mainUri)) return RtspMediaSource.Factory().createMediaSource(mainItem)
        return mediaSourceFactory.createMediaSource(mainItem)
    }

    private fun mediaItem(uriString: String, subtitle: SubtitleTrack?): ExoMediaItem {
        val uri = Uri.parse(uriString)
        val builder = ExoMediaItem.Builder().setUri(uri)
        inferMime(uri)?.let(builder::setMimeType)
        subtitle?.let { track ->
            val config = ExoMediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setMimeType(subtitleMime(track.mimeType))
                .setLanguage(track.language.ifBlank { "und" })
                .setLabel(track.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            builder.setSubtitleConfigurations(listOf(config))
        }
        return builder.build()
    }

    private fun progressive(item: ExoMediaItem): MediaSource =
        ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)

    private fun inferMime(uri: Uri): String? {
        val path = uri.path.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    private fun isAdaptive(uri: String): Boolean {
        val path = Uri.parse(uri).path.orEmpty().lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    private fun isRtsp(uri: String): Boolean = Uri.parse(uri).scheme.equals("rtsp", true)

    private fun subtitleMime(mimeType: String): String = when (mimeType.lowercase()) {
        "text/vtt", "text/webvtt" -> MimeTypes.TEXT_VTT
        "text/x-ssa", "text/x-ass" -> MimeTypes.TEXT_SSA
        "application/ttml+xml", "application/xml", "text/xml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun duration(p: Player): Long =
        p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0

    private fun publishProgress() {
        val p = player ?: return
        if (p.mediaItemCount == 0 || p.playbackState == Player.STATE_IDLE) return
        val cur = _snapshot.value
        _snapshot.value = cur.copy(
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            ended = p.playbackState == Player.STATE_ENDED,
            timeMs = p.currentPosition.coerceAtLeast(0),
            lengthMs = duration(p),
            bufferingPercent = p.bufferedPercentage,
            speed = p.playbackParameters.speed,
        )
    }

    data class AudioTrackInfo(val label: String, val id: Int, val selected: Boolean)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        @Volatile
        private var instance: MediaEngine? = null

        fun get(context: Context): MediaEngine =
            instance ?: synchronized(this) {
                instance ?: MediaEngine(context.applicationContext).also { instance = it }
            }
    }
}
