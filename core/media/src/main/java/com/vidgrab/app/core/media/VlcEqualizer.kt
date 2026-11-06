package com.vidgrab.app.core.media

import org.videolan.libvlc.MediaPlayer

/**
 * A small facade over libVLC's equalizer with the same shape as Android's [android.media.audiofx.Equalizer],
 * so the gallery's equalizer sheet keeps its existing controls.
 */
class VlcEqualizer(private val engine: VlcEngine) {
    private var eq: MediaPlayer.Equalizer = MediaPlayer.Equalizer.create()
    private var active = true

    var enabled: Boolean
        get() = active
        set(value) {
            active = value
            apply()
        }

    var currentPreset: Short = -1
        private set

    val bandLevelRange: ShortArray
        get() = shortArrayOf(MIN_LEVEL, MAX_LEVEL)

    val numberOfBands: Int
        get() = MediaPlayer.Equalizer.getBandCount()

    val numberOfPresets: Int
        get() = MediaPlayer.Equalizer.getPresetCount()

    fun getPresetName(preset: Short): String =
        MediaPlayer.Equalizer.getPresetName(preset.toInt()) ?: "Preset $preset"

    fun usePreset(preset: Short) {
        eq = MediaPlayer.Equalizer.createFromPreset(preset.toInt())
        currentPreset = preset
        apply()
    }

    fun getBandLevel(band: Short): Short =
        (eq.getAmp(band.toInt()) * 100).toInt().coerceIn(MIN_LEVEL.toInt(), MAX_LEVEL.toInt()).toShort()

    fun setBandLevel(band: Short, level: Short) {
        eq.setAmp(band.toInt(), level / 100f)
        currentPreset = -1
        apply()
    }

    /** MilliHertz, like Android's equalizer. */
    fun getCenterFreq(band: Short): Int = (MediaPlayer.Equalizer.getBandFrequency(band.toInt()) * 1000).toInt()

    fun release() {
        engine.setEqualizer(null)
    }

    private fun apply() {
        engine.setEqualizer(if (active) eq else null)
    }

    private companion object {
        const val MIN_LEVEL: Short = -2000
        const val MAX_LEVEL: Short = 2000
    }
}
