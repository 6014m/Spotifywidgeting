package com.sixzero.sunny

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Draws spectrum bars rising from the bottom edge. Fed by VisualizerService with
 * smoothed band levels in 0..1.
 */
class VisualizerView(context: Context) : View(context) {

    var bars: Int = 36
        set(v) { field = v.coerceIn(8, 64); levels = FloatArray(field); peaks = FloatArray(field) }
    var mirror: Boolean = true
    var alpha255: Int = 200
    var colorA: Int = PlayerState.AMBER      // bottom / base
    var colorB: Int = PlayerState.PEACH      // tips

    private var levels = FloatArray(36)
    private var peaks = FloatArray(36)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun push(newLevels: FloatArray) {
        val n = minOf(newLevels.size, levels.size)
        for (i in 0 until n) {
            val target = newLevels[i]
            // fast attack, slow decay
            levels[i] = if (target > levels[i]) levels[i] + (target - levels[i]) * 0.6f
                        else levels[i] + (target - levels[i]) * 0.18f
            peaks[i] = if (levels[i] > peaks[i]) levels[i] else peaks[i] - 0.015f
        }
        postInvalidateOnAnimation()
    }

    fun decay() {
        for (i in levels.indices) { levels[i] *= 0.85f; peaks[i] -= 0.02f }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val n = levels.size
        val gap = w / n * 0.28f
        val bw = (w - gap * (n + 1)) / n
        glow.maskFilter = BlurMaskFilter(bw * 0.9f, BlurMaskFilter.Blur.NORMAL)
        for (i in 0 until n) {
            // mirror: low frequencies in the centre, highs at the edges
            val src = if (mirror) {
                val half = n / 2f
                val dist = kotlin.math.abs(i + 0.5f - half) / half
                (dist * (n - 1)).toInt().coerceIn(0, n - 1)
            } else i
            val lv = levels[src].coerceIn(0f, 1f)
            val bh = maxOf(bw * 0.5f, lv * h * 0.92f)
            val x = gap + i * (bw + gap)
            val t = (i.toFloat() / n)
            val c = blend(colorA, colorB, if (mirror) kotlin.math.abs(t - 0.5f) * 2 else t)
            rect.set(x, h - bh, x + bw, h)
            glow.color = Art.withAlpha(c, alpha255 / 2)
            canvas.drawRoundRect(rect, bw / 2, bw / 2, glow)
            fill.color = Art.withAlpha(c, alpha255)
            canvas.drawRoundRect(rect, bw / 2, bw / 2, fill)
            // peak dot
            val pk = peaks[src].coerceIn(0f, 1f)
            if (pk > lv + 0.03f) {
                val py = h - pk * h * 0.92f
                fill.color = Art.withAlpha(colorB, alpha255)
                canvas.drawRoundRect(x, py - bw * 0.35f, x + bw, py + bw * 0.35f, bw / 2, bw / 2, fill)
            }
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
