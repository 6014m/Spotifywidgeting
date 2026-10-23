package com.sixzero.sunny

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

/** Bitmap helpers: rounded art, progress bar and the text renderer the widgets draw with. */
object Art {

    /** Centre-crop to a square of [size] px. */
    fun square(src: Bitmap, size: Int): Bitmap {
        val s = minOf(src.width, src.height)
        val x = (src.width - s) / 2
        val y = (src.height - s) / 2
        val cropped = Bitmap.createBitmap(src, x, y, s, s)
        return if (s == size) cropped else Bitmap.createScaledBitmap(cropped, size, size, true)
    }

    /** Rounded-corner version of a square bitmap, with a soft warm shadow baked in. */
    fun rounded(src: Bitmap, sizePx: Int, radiusPx: Float, shadow: Boolean): Bitmap {
        val pad = if (shadow) (sizePx * 0.08f).toInt() else 0
        val out = Bitmap.createBitmap(sizePx + pad * 2, sizePx + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rect = RectF(pad.toFloat(), pad.toFloat(), (pad + sizePx).toFloat(), (pad + sizePx).toFloat())
        if (shadow) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x80331400.toInt()
                maskFilter = BlurMaskFilter(pad.toFloat(), BlurMaskFilter.Blur.NORMAL)
            }
            c.drawRoundRect(rect, radiusPx, radiusPx, sp)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val scaled = Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
        p.shader = android.graphics.BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
            val m = android.graphics.Matrix(); m.postTranslate(pad.toFloat(), pad.toFloat()); it.setLocalMatrix(m)
        }
        c.drawRoundRect(rect, radiusPx, radiusPx, p)
        return out
    }

    /** Placeholder art when nothing is playing: a soft amber tile. */
    fun placeholder(sizePx: Int, radiusPx: Float): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFB347 }
        c.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radiusPx, radiusPx, p)
        return out
    }

    /** Progress bar: translucent cream track with an accent fill, glow included. */
    fun progressBar(widthPx: Int, heightPx: Int, fraction: Float, accent: Int): Bitmap {
        val w = maxOf(widthPx, 4); val h = maxOf(heightPx, 4)
        val out = Bitmap.createBitmap(w, h * 3, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val top = h.toFloat(); val bottom = (h * 2).toFloat(); val r = h / 2f
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFF4E0 }
        c.drawRoundRect(RectF(0f, top, w.toFloat(), bottom), r, r, track)
        val fw = (w * fraction.coerceIn(0f, 1f)).coerceAtLeast(h.toFloat())
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(accent, 0x90)
            maskFilter = BlurMaskFilter(h.toFloat(), BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(RectF(0f, top, fw, bottom), r, r, glow)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        c.drawRoundRect(RectF(0f, top, fw, bottom), r, r, fill)
        return out
    }

    /**
     * Render one line of text to a bitmap. [ghost] draws stroke-only with a
     * horizontal gradient from [gradStart] to [gradEnd]; otherwise a solid fill.
     */
    fun text(
        text: String, widthPx: Int, sizePx: Float, bold: Boolean, color: Int,
        ghost: Boolean, gradStart: Int, gradEnd: Int, shadow: Boolean
    ): Bitmap {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val w = maxOf(widthPx, 8)
        val shown = TextUtils.ellipsize(text.ifEmpty { " " }, tp, w.toFloat(), TextUtils.TruncateAt.END).toString()
        val fm = tp.fontMetrics
        val padY = sizePx * 0.35f
        val h = (fm.descent - fm.ascent + padY * 2).toInt()
        val out = Bitmap.createBitmap(w, maxOf(h, 2), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val baseline = padY - fm.ascent

        if (shadow) {
            val sp = TextPaint(tp).apply {
                this.color = 0xCC2E1200.toInt()
                maskFilter = BlurMaskFilter(sizePx * 0.22f, BlurMaskFilter.Blur.NORMAL)
                if (ghost) { style = Paint.Style.STROKE; strokeWidth = sizePx * 0.09f }
            }
            c.drawText(shown, 0f, baseline, sp)
        }

        if (ghost) {
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = maxOf(1.5f, sizePx * 0.055f)
            tp.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, gradStart, gradEnd, Shader.TileMode.CLAMP)
        } else {
            tp.style = Paint.Style.FILL
            tp.color = color
        }
        c.drawText(shown, 0f, baseline, tp)
        return out
    }

    /** 1x1 transparent bitmap for RemoteViews slots the overlay takes over. */
    fun blank(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
