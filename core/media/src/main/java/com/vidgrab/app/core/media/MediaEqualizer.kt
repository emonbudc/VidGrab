package com.vidgrab.app.core.media

import android.media.audiofx.Equalizer
import androidx.media3.common.C

/**
 * A small facade over Android's audio equalizer attached to the player's audio session, with the shape the gallery's
 * equalizer sheet uses.
 */
class MediaEqualizer(private val engine: MediaEngine) {
    private var eq: Equalizer? = null
    private var sessionId = Int.MIN_VALUE
    private var active = true
    private var levels = ShortArray(0)

    var enabled: Boolean
        get() = active
        set(value) {
            active = value
            apply()
        }

    var currentPreset: Short = -1
        private set

    val bandLevelRange: ShortArray
        get() = ensure()?.bandLevelRange ?: DEFAULT_RANGE

    val numberOfBands: Int
        get() = ensure()?.numberOfBands ?: 0

    val numberOfPresets: Int
        get() = ensure()?.numberOfPresets ?: 0

    fun getPresetName(preset: Short): String =
        runCatching { ensure()?.getPresetName(preset) }.getOrNull() ?: "Preset $preset"

    fun usePreset(preset: Short) {
        currentPreset = preset
        val e = ensure() ?: return
        runCatching { e.usePreset(preset) }
        readLevels(e)
        apply()
    }

    fun getBandLevel(band: Short): Short {
        val e = ensure()
        if (e == null || band.toInt() !in levels.indices) return 0
        return runCatching { e.getBandLevel(band) }.getOrDefault(levels[band.toInt()])
    }

    fun setBandLevel(band: Short, level: Short) {
        val e = ensure() ?: return
        if (band.toInt() !in levels.indices) return
        levels[band.toInt()] = level
        currentPreset = -1
        runCatching { e.setBandLevel(band, level) }
        apply()
    }

    /** MilliHertz, like Android's equalizer. */
    fun getCenterFreq(band: Short): Int =
        runCatching { ensure()?.getCenterFreq(band) }.getOrDefault(0)

    fun release() {
        runCatching { eq?.release() }
        eq = null
        sessionId = Int.MIN_VALUE
    }

    private fun ensure(): Equalizer? {
        val desired = engine.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET } ?: 0
        val current = eq
        if (current != null && sessionId == desired) return current
        runCatching { current?.release() }
        eq = runCatching { Equalizer(0, desired) }.getOrNull()
        sessionId = desired
        eq?.let { e ->
            val previous = levels
            levels = ShortArray(e.numberOfBands) { previous.getOrElse(it) { 0 } }
            if (currentPreset.toInt() >= 0) runCatching { e.usePreset(currentPreset) }
        }
        apply()
        return eq
    }

    private fun readLevels(e: Equalizer) {
        levels = ShortArray(e.numberOfBands) { runCatching { e.getBandLevel(it.toShort()) }.getOrDefault(0) }
    }

    private fun apply() {
        val e = ensure() ?: return
        e.enabled = active
        if (currentPreset.toInt() >= 0) return
        levels.forEachIndexed { band, level -> runCatching { e.setBandLevel(band.toShort(), level) } }
    }

    private companion object {
        val DEFAULT_RANGE = shortArrayOf(-1500, 1500)
    }
}
