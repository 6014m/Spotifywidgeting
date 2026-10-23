package com.sixzero.sunny

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

/**
 * The animated layer that sits exactly over the Sunny Player widget. Draws art,
 * title, artist and a waveform that doubles as the progress bar. Buttons stay
 * on the widget underneath (touches pass through this view).
 *
 * In calibration mode the view covers the whole screen and lets the user drag
 * a frame onto the widget.
 */
class OverlayView(context: Context) : View(context) {

    // ---- state fed from outside
    var rect = Rect()                 // absolute screen rect this view represents
    var calibrating = false
    var onCalibrated: ((Rect?) -> Unit)? = null

    var ghost = false
    var invert = true
    var shadow = true
    var artAccent = true

    private val d = resources.displayMetrics.density

    // ---- water: loudness drives swell height, phase scrolls with time
    private var energy = 0f          // smoothed loudness 0..1
    private var targetEnergy = 0f
    private var phase = 0f
    private var lastFrame = 0L
    private var lastWaveAt = 0L

    fun pushWaveform(pcm: ByteArray) {
        var sum = 0.0
        for (b in pcm) { val v = ((b.toInt() and 0xFF) - 128) / 128.0; sum += v * v }
        val rms = Math.sqrt(sum / pcm.size).toFloat()
        // music RMS lives around 0.05..0.4; map that to 0..1 with some headroom
        targetEnergy = ((rms - 0.03f) / 0.3f).coerceIn(0f, 1f)
        lastWaveAt = SystemClock.elapsedRealtime()
    }

    private fun stepWater() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(0, 100)
        lastFrame = now
        val idle = now - lastWaveAt > 500 || !PlayerState.playing
        val t = if (idle) 0f else targetEnergy
        energy += (t - energy) * (if (t > energy) 0.25f else 0.08f)
        phase += dt / 1000f * (1.6f + energy * 3f)
    }

    // ---- paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        if (calibrating) { drawCalibration(canvas); return }
        drawPlayer(canvas, 0f, 0f, width.toFloat(), height.toFloat())
        if (PlayerState.playing) postInvalidateOnAnimation()
    }

    /** Mirrors widget_player.xml so the overlay lines up with the widget's own buttons. */
    private var originX = 0f
    private var originY = 0f

    private fun drawPlayer(c: Canvas, left: Float, top: Float, w: Float, h: Float) {
        // canvas->screen offset, used to sample the wallpaper behind the glyphs
        originX = if (calibrating) 0f else rect.left.toFloat()
        originY = if (calibrating) 0f else rect.top.toFloat()
        val pad = 6 * d
        val artSz = 88 * d
        val colH = artSz + 10 * d + 6 * d + 8 * d + 52 * d
        val colTop = top + (h - colH) / 2f
        val acc = if (artAccent) PlayerState.accent else PlayerState.AMBER
        val light = if (artAccent) PlayerState.accentLight else PlayerState.CREAM

        // art
        val art = PlayerState.art
        val artRect = RectF(left + pad, colTop, left + pad + artSz, colTop + artSz)
        val radius = artSz / 6f
        if (shadow) {
            fill.shader = null; fill.maskFilter = BlurMaskFilter(artSz * 0.08f, BlurMaskFilter.Blur.NORMAL)
            fill.color = 0x80331400.toInt()
            c.drawRoundRect(artRect, radius, radius, fill)
            fill.maskFilter = null
        }
        if (art != null) {
            val m = Matrix()
            val s = artSz / art.width
            m.setScale(s, s); m.postTranslate(artRect.left, artRect.top)
            fill.shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            fill.color = -1
            c.drawRoundRect(artRect, radius, radius, fill)
            fill.shader = null
        } else {
            fill.shader = null; fill.color = 0x66FFB347
            c.drawRoundRect(artRect, radius, radius, fill)
        }

        // text column
        val textLeft = artRect.right + 12 * d
        val textW = left + w - pad - textLeft
        val heightDp = (h / d).coerceIn(100f, 220f)
        val title = if (PlayerState.hasTrack) PlayerState.title else "Nothing playing"
        val artist = if (PlayerState.hasTrack) PlayerState.artist else "Spotify"
        val titleSize = heightDp / 7.2f * d
        val artistSize = heightDp / 10f * d
        var y = colTop + titleSize * 0.35f
        y = drawText(c, title, textLeft, y, textW, titleSize, true, PlayerState.CREAM, light, acc)
        y += 4 * d
        drawText(c, artist, textLeft, y, textW, artistSize, false, 0xF5FFEFD6.toInt(), light, acc)

        // water on the progress bar
        stepWater()
        val barTop = colTop + artSz + 10 * d
        val barH = 6 * d
        val barBottom = barTop + barH
        val barLeft = left + pad; val barW = w - pad * 2
        val progress = PlayerState.progress(SystemClock.elapsedRealtime())
        val px = barLeft + barW * progress
        val r = barH / 2

        // track
        fill.shader = null; fill.maskFilter = null; fill.color = 0x59FFF4E0
        c.drawRoundRect(RectF(barLeft, barTop, barLeft + barW, barBottom), r, r, fill)

        // water body: surface rises above the bar over the played part
        val swell = 4 * d + energy * 18 * d
        path.reset()
        path.moveTo(barLeft, barBottom)
        val steps = 72
        for (i in 0..steps) {
            val x = barLeft + (px - barLeft) * i / steps
            val u = (x - barLeft) / (12 * d)
            val s1 = Math.sin((u * 0.9f + phase * 2.1f).toDouble()).toFloat()
            val s2 = Math.sin((u * 1.7f - phase * 3.3f + 1.3f).toDouble()).toFloat()
            val s3 = Math.sin((u * 0.35f + phase * 0.8f).toDouble()).toFloat()
            // taper the swell near the leading edge so it meets the bar cleanly
            val edge = ((px - x) / (28 * d)).coerceIn(0f, 1f)
            val y = barBottom - r - swell * edge * (0.55f * s1 + 0.3f * s2 + 0.15f * s3 + 0.9f) * 0.5f
            path.lineTo(x, y)
        }
        path.lineTo(px, barBottom)
        path.close()
        glow.maskFilter = BlurMaskFilter(6 * d, BlurMaskFilter.Blur.NORMAL)
        glow.style = Paint.Style.FILL; glow.color = Art.withAlpha(acc, 0x66)
        c.drawPath(path, glow)
        glow.style = Paint.Style.STROKE
        fill.color = Art.withAlpha(acc, 0xCC)
        c.drawPath(path, fill)
        // bright crest line
        line.strokeWidth = 2 * d; line.maskFilter = null; line.color = light
        c.save(); c.clipRect(barLeft, top, px, barBottom - r); c.drawPath(path, line); c.restore()
        // solid played bar underneath so it reads as progress even when the water is calm
        fill.color = acc
        c.drawRoundRect(RectF(barLeft, barTop, maxOf(px, barLeft + barH), barBottom), r, r, fill)
        // playhead
        fill.color = light
        c.drawCircle(px, barTop + r, 4 * d, fill)
    }

    /** Draws one ellipsized line; returns the y below it. */
    private fun drawText(c: Canvas, s: String, x: Float, y: Float, w: Float, size: Float, bold: Boolean,
                         solid: Int, gradA: Int, gradB: Int): Float {
        text.textSize = size
        text.typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        text.shader = null; text.colorFilter = null; text.maskFilter = null
        val shown = TextUtils.ellipsize(s.ifEmpty { " " }, text, w, TextUtils.TruncateAt.END).toString()
        val fm = text.fontMetrics
        val baseline = y - fm.ascent
        val strokeW = maxOf(1.5f, size * 0.055f)

        val wall = if (invert) Wallpaper.invertedScreen(context) else null
        if (shadow && wall == null) {
            text.style = if (ghost) Paint.Style.STROKE else Paint.Style.FILL
            text.strokeWidth = size * 0.09f
            text.color = 0xCC2E1200.toInt()
            text.maskFilter = BlurMaskFilter(size * 0.22f, BlurMaskFilter.Blur.NORMAL)
            c.drawText(shown, x, baseline, text)
            text.maskFilter = null
        }
        if (wall != null) {
            // Fill the glyphs with the inverted wallpaper that sits exactly behind them.
            val m = Matrix(); m.setTranslate(-originX, -originY)
            text.shader = BitmapShader(wall, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            text.style = if (ghost) Paint.Style.STROKE else Paint.Style.FILL
            text.strokeWidth = strokeW
            text.color = -1
            c.drawText(shown, x, baseline, text)
            text.shader = null
        } else if (ghost) {
            text.style = Paint.Style.STROKE
            text.strokeWidth = strokeW
            text.shader = LinearGradient(x, 0f, x + w, 0f, gradA, gradB, Shader.TileMode.CLAMP)
            text.color = -1
            c.drawText(shown, x, baseline, text)
            text.shader = null
        } else {
            text.style = Paint.Style.FILL
            text.color = solid
            c.drawText(shown, x, baseline, text)
        }
        return baseline + fm.descent
    }

    // ---- calibration -------------------------------------------------------

    private var frame = RectF()
    private var dragMode = 0   // 0 none, 1 move, 2 resize
    private var lastX = 0f; private var lastY = 0f
    private val saveBtn = RectF(); private val cancelBtn = RectF()

    fun startCalibration(initial: Rect) {
        calibrating = true
        frame = RectF(initial)
        invalidate()
    }

    private fun drawCalibration(c: Canvas) {
        fill.shader = null; fill.maskFilter = null
        fill.color = 0x66000000
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        // show the live layout inside the frame so lining it up is easy
        c.save(); c.clipRect(frame)
        fill.color = 0x22FFFFFF; c.drawRect(frame, fill)
        drawPlayer(c, frame.left, frame.top, frame.width(), frame.height())
        c.restore()
        line.color = PlayerState.AMBER; line.strokeWidth = 2 * d; line.maskFilter = null
        c.drawRoundRect(frame, 12 * d, 12 * d, line)
        fill.color = PlayerState.AMBER
        c.drawCircle(frame.right, frame.bottom, 14 * d, fill)

        text.shader = null; text.style = Paint.Style.FILL; text.maskFilter = null
        text.textSize = 15 * d; text.typeface = Typeface.DEFAULT_BOLD; text.color = -1
        val msg = "Drag the frame onto the widget. Corner resizes."
        c.drawText(msg, (width - text.measureText(msg)) / 2f, 90 * d, text)

        fun pill(r: RectF, label: String, color: Int) {
            fill.color = color; c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
            text.color = 0xFF2B1A12.toInt()
            c.drawText(label, r.centerX() - text.measureText(label) / 2, r.centerY() + text.textSize / 3, text)
        }
        saveBtn.set(width / 2f + 8 * d, 110 * d, width / 2f + 128 * d, 154 * d)
        cancelBtn.set(width / 2f - 128 * d, 110 * d, width / 2f - 8 * d, 154 * d)
        pill(saveBtn, "Save", PlayerState.AMBER)
        pill(cancelBtn, "Cancel", 0xFFD9D0C2.toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!calibrating) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                if (saveBtn.contains(e.x, e.y)) { finish(true); return true }
                if (cancelBtn.contains(e.x, e.y)) { finish(false); return true }
                val handle = Math.hypot((e.x - frame.right).toDouble(), (e.y - frame.bottom).toDouble()) < 28 * d
                dragMode = if (handle) 2 else if (frame.contains(e.x, e.y)) 1 else 1
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                if (dragMode == 2) {
                    frame.right = (frame.right + dx).coerceAtLeast(frame.left + 120 * d)
                    frame.bottom = (frame.bottom + dy).coerceAtLeast(frame.top + 80 * d)
                } else if (dragMode == 1) {
                    frame.offset(dx, dy)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = 0
        }
        return true
    }

    private fun finish(save: Boolean) {
        calibrating = false
        val r = Rect(frame.left.toInt(), frame.top.toInt(), frame.right.toInt(), frame.bottom.toInt())
        onCalibrated?.invoke(if (save) r else null)
    }
}
