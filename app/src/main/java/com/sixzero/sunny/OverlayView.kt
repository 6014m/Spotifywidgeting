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

    // ---- waveform
    private var wave = FloatArray(96)
    private var target = FloatArray(96)
    private var lastWaveAt = 0L

    fun pushWaveform(pcm: ByteArray) {
        val n = target.size
        val step = pcm.size.toFloat() / n
        for (i in 0 until n) {
            // average a window of samples so the line doesn't jitter
            val a = (i * step).toInt(); val b = ((i + 1) * step).toInt().coerceAtMost(pcm.size)
            var sum = 0f; var c = 0
            for (k in a until b) { sum += ((pcm[k].toInt() and 0xFF) - 128) / 128f; c++ }
            target[i] = if (c > 0) sum / c else 0f
        }
        lastWaveAt = SystemClock.elapsedRealtime()
        postInvalidateOnAnimation()
    }

    private fun stepWave() {
        val idle = SystemClock.elapsedRealtime() - lastWaveAt > 400 || !PlayerState.playing
        for (i in wave.indices) {
            val t = if (idle) 0f else target[i]
            wave[i] += (t - wave[i]) * 0.35f
        }
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

        // waveform-as-progress
        stepWave()
        val barTop = colTop + artSz + 10 * d
        val midY = barTop + 3 * d
        val barLeft = left + pad; val barW = w - pad * 2
        val progress = PlayerState.progress(SystemClock.elapsedRealtime())
        val amp = 16 * d
        path.reset()
        val n = wave.size
        for (i in 0 until n) {
            val x = barLeft + barW * i / (n - 1)
            val yy = midY - wave[i] * amp
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        line.strokeWidth = 3.5f * d
        glow.strokeWidth = 9 * d
        glow.maskFilter = BlurMaskFilter(5 * d, BlurMaskFilter.Blur.NORMAL)
        // unplayed part: dim cream
        c.save()
        c.clipRect(barLeft + barW * progress, top, left + w, top + h)
        line.color = 0x59FFF4E0
        c.drawPath(path, line)
        c.restore()
        // played part: accent with glow
        c.save()
        c.clipRect(left, top, barLeft + barW * progress, top + h)
        glow.color = Art.withAlpha(acc, 0x80); c.drawPath(path, glow)
        line.color = acc; c.drawPath(path, line)
        c.restore()
        // playhead dot
        val px = barLeft + barW * progress
        val idx = ((n - 1) * progress).toInt().coerceIn(0, n - 1)
        fill.shader = null; fill.color = light
        c.drawCircle(px, midY - wave[idx] * amp, 3.5f * d, fill)
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
