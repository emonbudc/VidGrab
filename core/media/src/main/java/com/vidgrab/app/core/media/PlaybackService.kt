package com.vidgrab.app.core.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the notification and lock-screen controls show for the item that is playing. */
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val artwork: String? = null,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/** The foreground service that keeps playback alive in the background and on the lock screen. */
class PlaybackService : Service() {
    /** Set by [PlayerController] so notification and lock-screen buttons can drive the queue. */
    interface Commands {
        fun play()
        fun pause()
        fun next()
        fun previous()
        fun stop()
        fun seekTo(ms: Long)
    }

    private val engine by lazy { MediaEngine.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSessionCompat? = null
    private var contentIntent: PendingIntent? = null
    private var lastRendered: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisy: BroadcastReceiver? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                commands?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = engine.snapshot.value.isPlaying
                commands?.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                commands?.play()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        val launch = packageManager.getLaunchIntentForPackage(packageName)!!
            .putExtra("open", "player")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        contentIntent = PendingIntent.getActivity(this, 7, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val s = MediaSessionCompat(this, "VidGrab").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setSessionActivity(contentIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { commands?.play() }
                override fun onPause() { commands?.pause() }
                override fun onSkipToNext() { commands?.next() }
                override fun onSkipToPrevious() { commands?.previous() }
                override fun onStop() { commands?.stop() }
                override fun onSeekTo(pos: Long) { commands?.seekTo(pos) }
            })
            isActive = true
        }
        session = s
        registerNoisyReceiver()
        audioManager = getSystemService(AudioManager::class.java)
        requestAudioFocus(keep = false)
        scope.launch {
            combine(engine.snapshot, nowPlaying) { state, info -> state to info }.collect { (state, info) ->
                updateSession(state, info)
                render(state, info)
                updateWakeLock(state.isPlaying)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action != null && intent.action != ACTION_START) {
            session?.let { MediaButtonReceiver.handleIntent(it, intent) }
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(engine.snapshot.value, nowPlaying.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val s = engine.snapshot.value
        if (!s.isPlaying && !s.isBuffering) {
            commands?.pause()
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        noisy?.let { runCatching { unregisterReceiver(it) } }
        noisy = null
        abandonAudioFocus()
        updateWakeLock(false)
        session?.let { runCatching { it.release() } }
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun updateSession(state: MediaSnapshot, info: NowPlaying) {
        val s = session ?: return
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.lengthMs)
        info.artwork?.takeIf { it.isNotBlank() }?.let { art ->
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, art)
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, art)
            metadata.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, art)
        }
        s.setMetadata(metadata.build())
        val playbackState = when {
            state.error != null -> PlaybackStateCompat.STATE_ERROR
            state.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            state.ended -> PlaybackStateCompat.STATE_STOPPED
            state.timeMs > 0 -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        val builder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP,
            )
            .setState(playbackState, state.timeMs, if (state.isPlaying) state.speed else 1f)
            .setBufferedPosition((state.lengthMs * state.bufferingPercent / 100).coerceAtLeast(state.timeMs))
        state.error?.let { builder.setErrorMessage(it) }
        s.setPlaybackState(builder.build())
    }

    private fun render(state: MediaSnapshot, info: NowPlaying) {
        val key = listOf(
            info.title, info.artist, info.artwork, info.hasNext, info.hasPrevious, state.isPlaying, state.isBuffering,
            state.ended, state.error, state.timeMs / 5_000,
        ).joinToString("|")
        if (key == lastRendered) return
        lastRendered = key
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state, info))
    }

    private fun buildNotification(state: MediaSnapshot, info: NowPlaying): Notification {
        val s = session
        val playing = state.isPlaying
        val playPause = if (playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE),
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE),
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(info.title.ifBlank { "VidGrab" })
            .setContentText(info.artist.ifBlank { if (state.isBuffering) "Loading…" else "VidGrab" })
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_previous, "Previous",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS),
                ),
            )
            .addAction(playPause)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next, "Next",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT),
                ),
            )
        if (s != null) {
            builder.setStyle(MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
        }
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Video and music playing in VidGrab"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun registerNoisyReceiver() {
        if (noisy != null) return
        noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) commands?.pause()
            }
        }.also {
            ContextCompat.registerReceiver(
                this, it, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun requestAudioFocus(keep: Boolean) {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(true)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        if (keep) resumeOnFocusGain = true
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
        focusRequest = null
    }

    private fun updateWakeLock(on: Boolean) {
        if (on) {
            if (wakeLock?.isHeld != true) {
                val power = getSystemService(PowerManager::class.java)
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VidGrab:Playback").apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)
                }
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    companion object {
        const val ACTION_START = "com.vidgrab.app.action.PLAYBACK_START"
        private const val CHANNEL_ID = "vidgrab_playback"
        private const val NOTIFICATION_ID = 8

        @Volatile
        var commands: Commands? = null

        val nowPlaying = MutableStateFlow(NowPlaying())

        @Volatile
        private var running = false

        fun ensureStarted(context: Context) {
            if (running) return
            ContextCompat.startForegroundService(
                context, Intent(context, PlaybackService::class.java).setAction(ACTION_START),
            )
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
