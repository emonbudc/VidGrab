package com.vidgrab.app.core.media

import android.content.Context
import android.net.Uri
import com.vidgrab.app.core.data.PlaybackHistoryRepository
import com.vidgrab.app.core.data.SettingsRepository
import com.vidgrab.app.core.model.AppSettings
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Same values as the old Media3 constants, kept so Settings and the player screens don't need their own enum. */
object RepeatMode {
    const val OFF = 0
    const val ALL = 1
    const val ONE = 2
}

/** Something the shared player can play: a downloaded file or an online stream. */
data class PlayItem(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String = "",
    val artwork: String? = null,
    val isVideo: Boolean = true,
    /** Separate audio stream to merge (video-only online streams). */
    val audioUri: String? = null,
    val startPositionMs: Long = 0,
    /** Key in the playback history (file path, page URL); defaults to [id]. */
    val historyKey: String? = null,
) {
    val resumeKey: String get() = historyKey ?: id
}

data class PlaybackState(
    val current: PlayItem? = null,
    val queue: List<PlayItem> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Played to the end and stopped there (not repeating). */
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val speed: Float = 1f,
    val audioOnly: Boolean = false,
    val repeatMode: Int = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val error: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

/** The app-wide playback queue and history around the single [VlcEngine]. */
class PlayerController(
    private val context: Context,
    private val history: PlaybackHistoryRepository? = null,
    settingsRepo: SettingsRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine = VlcEngine.get(context.applicationContext)

    /** Settings → Player, kept current for synchronous reads. */
    @Volatile var prefs = AppSettings()
        private set

    /** Last saved position per history key, so [play] can resume without waiting for the database. */
    private val positions = ConcurrentHashMap<String, Long>()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var queue: List<PlayItem> = emptyList()
    private var index = 0
    private var repeatMode = RepeatMode.OFF
    private var shuffle = false
    private var audioOnly = false
    private var pauseAtEndOfItems = false
    private var endedHandled = false
    private var wasPlaying = false

    /** Called by the library so it can remember playback positions. */
    var onPositionSave: ((id: String, positionMs: Long) -> Unit)? = null

    init {
        settingsRepo?.let { repo -> scope.launch { repo.settings.collect { prefs = it } } }
        history?.let { h ->
            scope.launch {
                h.entries.collect { list ->
                    positions.keys.retainAll(list.mapTo(HashSet()) { it.key })
                    list.forEach { positions[it.key] = it.positionMs }
                }
            }
        }
        PlaybackService.commands = object : PlaybackService.Commands {
            override fun play() = resume()
            override fun pause() = pause()
            override fun next() = next()
            override fun previous() = previous()
            override fun stop() = stop()
            override fun seekTo(ms: Long) = seekTo(ms)
        }
        scope.launch {
            engine.snapshot.collect { snapshot ->
                sync(snapshot)
                if (snapshot.ended && !endedHandled) {
                    endedHandled = true
                    onEnded()
                } else if (!snapshot.ended) {
                    endedHandled = false
                }
                if (wasPlaying && !snapshot.isPlaying) saveCurrentPosition()
                wasPlaying = snapshot.isPlaying
            }
        }
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (engine.snapshot.value.isPlaying) saveCurrentPosition()
            }
        }
    }

    /** Kept for the app's startup call; libVLC needs no connection step. */
    fun connect() = Unit

    /** Attaches/detaches the video surface while playback itself continues. */
    fun attachVideo(layout: VLCVideoLayout) = engine.attachVideo(layout)
    fun detachVideo(layout: VLCVideoLayout) = engine.detachVideo(layout)

    fun play(items: List<PlayItem>, startIndex: Int = 0, audioOnly: Boolean = false) {
        if (items.isEmpty()) return
        saveCurrentPosition()
        val at = startIndex.coerceIn(0, items.lastIndex)
        queue = items
        index = at
        this.audioOnly = audioOnly || !items[at].isVideo
        val item = items[at]
        endedHandled = false
        _state.value = PlaybackState(
            current = item,
            queue = items,
            index = at,
            speed = prefs.playbackSpeed,
            audioOnly = this.audioOnly,
            repeatMode = repeatMode,
            shuffle = shuffle,
        )
        publishInfo()
        PlaybackService.ensureStarted(context)
        engine.play(item, startPosition(item), prefs.playbackSpeed, this.audioOnly)
        markOpened(item)
    }

    fun togglePlay() {
        val s = _state.value
        if (s.ended || engine.snapshot.value.ended) {
            engine.seekTo(0)
            _state.update { it.copy(ended = false, positionMs = 0) }
            engine.play()
        } else if (engine.snapshot.value.isPlaying) {
            engine.pause()
        } else {
            engine.play()
        }
    }

    fun pause() = engine.pause()

    fun resume() = engine.play()

    fun seekTo(ms: Long) {
        engine.seekTo(ms)
        _state.update { it.copy(positionMs = ms.coerceAtLeast(0), ended = false) }
    }

    fun seekBy(deltaMs: Long) = seekTo((engine.currentTimeMs + deltaMs).coerceAtLeast(0))

    fun next() {
        val target = when {
            shuffle && queue.size > 1 -> randomIndex()
            index < queue.lastIndex -> index + 1
            repeatMode == RepeatMode.ALL && queue.isNotEmpty() -> 0
            else -> -1
        }
        if (target >= 0) moveTo(target)
    }

    fun previous() {
        if (engine.currentTimeMs > 3_000 || index == 0) {
            seekTo(0)
            return
        }
        val target = when {
            shuffle && queue.size > 1 -> randomIndex()
            repeatMode == RepeatMode.ALL -> queue.lastIndex
            else -> index - 1
        }
        if (target >= 0 && target != index) moveTo(target)
    }

    fun skipTo(index: Int) {
        if (index in queue.indices) moveTo(index)
    }

    /** Plays the current item again from where it was, clearing a playback error. */
    fun reloadCurrent() {
        val item = queue.getOrNull(index) ?: return
        val at = engine.currentTimeMs
        _state.update { it.copy(error = null, ended = false) }
        engine.play(item, at, _state.value.speed, audioOnly)
    }

    fun setSpeed(speed: Float) {
        engine.setSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun setRepeat(mode: Int) {
        repeatMode = mode
        _state.update { it.copy(repeatMode = mode) }
    }

    fun setShuffle(on: Boolean) {
        shuffle = on
        _state.update { it.copy(shuffle = on) }
    }

    fun setAudioOnly(on: Boolean) {
        audioOnly = on
        engine.setAudioOnly(on)
        _state.update { it.copy(audioOnly = on) }
    }

    /** Player volume (0 = muted), independent of the device volume. */
    fun setVolume(volume: Float) {
        engine.setVolume(volume)
    }

    /** Live position for smooth progress bars. */
    val currentPositionMs: Long get() = engine.currentTimeMs

    /** Stop at the end of each item instead of moving on ("autoplay off"). */
    fun setPauseAtEndOfItems(on: Boolean) {
        pauseAtEndOfItems = on
    }

    /** Adds a subtitle file (SRT, VTT, SSA…) to the current item. */
    fun addSubtitle(uri: Uri, mimeType: String) {
        engine.addSubtitle(uri, mimeType)
    }

    data class AudioTrack(val label: String, val id: Int, val selected: Boolean)

    fun audioTracks(): List<AudioTrack> =
        engine.audioTracks().map { AudioTrack(it.label, it.id, it.selected) }

    fun selectAudioTrack(track: AudioTrack) {
        engine.selectAudioTrack(track.id)
    }

    /** The player's libVLC equalizer, created on first use; owned and released by the screen that asked for it. */
    fun equalizer(): VlcEqualizer = VlcEqualizer(engine)

    fun stop() {
        saveCurrentPosition()
        engine.stop()
        queue = emptyList()
        index = 0
        endedHandled = false
        _state.value = PlaybackState(repeatMode = repeatMode, shuffle = shuffle, speed = prefs.playbackSpeed)
        PlaybackService.nowPlaying.value = NowPlaying()
        PlaybackService.stopService(context)
    }

    private fun onEnded() {
        if (pauseAtEndOfItems) {
            _state.update { it.copy(ended = true, isPlaying = false) }
            saveCurrentPosition(finished = true)
            return
        }
        if (repeatMode == RepeatMode.ONE) {
            saveCurrentPosition(finished = true)
            engine.seekTo(0)
            engine.play()
            return
        }
        val target = when {
            shuffle && queue.size > 1 -> randomIndex()
            index < queue.lastIndex -> index + 1
            repeatMode == RepeatMode.ALL && queue.isNotEmpty() -> 0
            else -> -1
        }
        if (target < 0) {
            _state.update { it.copy(ended = true, isPlaying = false) }
            saveCurrentPosition(finished = true)
        } else {
            moveTo(target, auto = true)
        }
    }

    private fun moveTo(target: Int, auto: Boolean = false) {
        val next = queue.getOrNull(target) ?: return
        val previous = queue.getOrNull(index)
        if (previous != null) {
            val finished = auto || _state.value.ended
            record(previous, if (finished) 0 else _state.value.positionMs, _state.value.durationMs)
        }
        index = target
        endedHandled = false
        _state.update {
            it.copy(
                current = next, index = target, isPlaying = false, isBuffering = false, ended = false,
                positionMs = 0, durationMs = 0, bufferedMs = 0, error = null,
                videoWidth = 0, videoHeight = 0,
            )
        }
        publishInfo()
        PlaybackService.ensureStarted(context)
        engine.play(next, startPosition(next), _state.value.speed, audioOnly)
        markOpened(next)
    }

    private fun sync(snapshot: VlcSnapshot) {
        if (queue.isEmpty()) return
        val buffered = when {
            snapshot.lengthMs > 0 -> (snapshot.lengthMs * snapshot.bufferingPercent / 100).coerceAtLeast(snapshot.timeMs)
            else -> snapshot.timeMs
        }
        _state.update {
            it.copy(
                current = queue.getOrNull(index) ?: it.current,
                queue = queue,
                index = index,
                isPlaying = snapshot.isPlaying,
                isBuffering = snapshot.isBuffering,
                ended = snapshot.ended,
                positionMs = snapshot.timeMs,
                durationMs = snapshot.lengthMs,
                bufferedMs = buffered,
                speed = snapshot.speed,
                repeatMode = repeatMode,
                shuffle = shuffle,
                error = snapshot.error,
                videoWidth = snapshot.videoWidth,
                videoHeight = snapshot.videoHeight,
                audioOnly = audioOnly,
            )
        }
    }

    private fun publishInfo() {
        val item = queue.getOrNull(index)
        PlaybackService.nowPlaying.value = if (item == null) {
            NowPlaying()
        } else {
            NowPlaying(
                title = item.title,
                artist = item.artist,
                artwork = item.artwork,
                hasNext = index < queue.lastIndex,
                hasPrevious = index > 0,
            )
        }
    }

    private fun saveCurrentPosition(finished: Boolean = false) {
        val item = queue.getOrNull(index) ?: return
        val duration = engine.currentLengthMs
        val position = engine.currentTimeMs
        val saved = if (finished || (duration > 0 && position >= duration - 2_000)) 0 else position
        onPositionSave?.invoke(item.id, saved)
        record(item, saved, duration)
    }

    /** Where [item] starts: its own start position, else where it was left last time (Settings → Player → Resume). */
    private fun startPosition(item: PlayItem): Long = when {
        item.startPositionMs > 0 -> item.startPositionMs
        prefs.playHistory && prefs.resumePlayback -> positions[item.resumeKey]?.takeIf { it > RESUME_MIN_MS } ?: 0
        else -> 0
    }

    private fun record(item: PlayItem, positionMs: Long, durationMs: Long, opened: Boolean = false) {
        if (!prefs.playHistory) return
        positions[item.resumeKey] = positionMs
        val h = history ?: return
        scope.launch { runCatching { h.save(item.resumeKey, item.title, positionMs, durationMs, opened) } }
    }

    private fun markOpened(item: PlayItem) = record(item, positions[item.resumeKey] ?: 0, 0, opened = true)

    private fun randomIndex(): Int {
        if (queue.size <= 1) return index
        var next = Random.nextInt(queue.size)
        if (next == index) next = (next + 1) % queue.size
        return next
    }

    private companion object {
        /** Positions this close to the start aren't worth resuming. */
        const val RESUME_MIN_MS = 3_000L
    }
}
