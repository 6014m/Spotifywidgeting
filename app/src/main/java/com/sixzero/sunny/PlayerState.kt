package com.sixzero.sunny

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

/**
 * Process-wide snapshot of what's playing. Written by MediaWatcher, read by
 * the widgets and the visualizer overlay. Everything here is cheap to read.
 */
object PlayerState {
    const val AMBER = 0xFFFFB347.toInt()
    const val PEACH = 0xFFFFD08A.toInt()
    const val CREAM = 0xFFFFF8EA.toInt()

    @Volatile var title: String = ""
    @Volatile var artist: String = ""
    @Volatile var packageName: String = "com.spotify.music"
    @Volatile var playing: Boolean = false
    @Volatile var durationMs: Long = 0
    @Volatile var positionMs: Long = 0
    @Volatile var positionAtMs: Long = 0     // SystemClock.elapsedRealtime() when positionMs was read
    @Volatile var speed: Float = 1f
    @Volatile var art: Bitmap? = null
    @Volatile var artKey: String = ""

    /** Vibrant colour pulled from the art, already brightened for readability. */
    @Volatile var accent: Int = AMBER
    @Volatile var accentSoft: Int = PEACH
    @Volatile var accentLight: Int = CREAM

    val hasTrack: Boolean get() = title.isNotEmpty()

    /** Live position, extrapolated from the last playback state update. */
    fun currentPositionMs(now: Long): Long {
        if (!playing) return positionMs
        val p = positionMs + ((now - positionAtMs) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    fun progress(now: Long): Float =
        if (durationMs > 0) (currentPositionMs(now).toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Derive the three accent shades from a bitmap the same way the KWGT preset did. */
    fun updateAccent(bitmap: Bitmap?) {
        if (bitmap == null) { accent = AMBER; accentSoft = PEACH; accentLight = CREAM; return }
        val palette = Palette.from(bitmap).clearFilters().generate()
        val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch
            ?: palette.dominantSwatch ?: palette.mutedSwatch
        if (swatch == null) { accent = AMBER; accentSoft = PEACH; accentLight = CREAM; return }
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(swatch.rgb, hsl)
        accent = hsl(hsl[0], maxOf(hsl[1], 0.6f), 0.62f)
        accentSoft = hsl(hsl[0], maxOf(hsl[1], 0.45f), 0.78f)
        accentLight = hsl(hsl[0], maxOf(hsl[1], 0.5f), 0.88f)
    }

    private fun hsl(h: Float, s: Float, l: Float): Int =
        androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))) or (0xFF shl 24)

    fun reset() {
        title = ""; artist = ""; playing = false; durationMs = 0; positionMs = 0
        art = null; artKey = ""; accent = AMBER; accentSoft = PEACH; accentLight = CREAM
    }

}
