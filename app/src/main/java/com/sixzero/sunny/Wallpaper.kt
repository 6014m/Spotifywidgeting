package com.sixzero.sunny

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Environment
import android.view.WindowManager

/**
 * Reads the home wallpaper and keeps a screen-sized copy. Android 13+ only hands
 * the real wallpaper to apps with "All files access", otherwise we get the stock
 * default and the invert effect is meaningless, so callers check [readable].
 */
object Wallpaper {
    @Volatile private var screenCopy: Bitmap? = null
    @Volatile private var inverted: Bitmap? = null

    val readable: Boolean get() =
        android.os.Build.VERSION.SDK_INT < 33 || (android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())

    /** Full physical screen size in pixels. */
    fun screenBounds(context: Context): Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= 30) Rect(wm.maximumWindowMetrics.bounds)
        else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    fun invalidate() { screenCopy = null; inverted = null }

    /** Screen-sized bitmap of the wallpaper (centre-cropped the way launchers draw a static image). */
    fun screen(context: Context): Bitmap? {
        screenCopy?.let { return it }
        if (!readable) return null
        val wm = WallpaperManager.getInstance(context)
        val src: Bitmap = try {
            val d = wm.getDrawable(WallpaperManager.FLAG_SYSTEM) ?: return null
            (d as? BitmapDrawable)?.bitmap ?: run {
                val b = Bitmap.createBitmap(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, b.width, b.height); d.draw(Canvas(b)); b
            }
        } catch (e: Exception) {
            return null
        }
        val bounds: Rect = screenBounds(context)
        val sw = bounds.width(); val sh = bounds.height()
        val scale = maxOf(sw.toFloat() / src.width, sh.toFloat() / src.height)
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.postScale(scale, scale)
        m.postTranslate((sw - src.width * scale) / 2f, (sh - src.height * scale) / 2f)
        Canvas(out).drawBitmap(src, m, null)
        screenCopy = out
        return out
    }

    /** Same, with colours inverted, ready to be used as a shader for "ghost" text. */
    fun invertedScreen(context: Context): Bitmap? {
        inverted?.let { return it }
        val s = screen(context) ?: return null
        val out = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint()
        p.colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f))
        Canvas(out).drawBitmap(s, 0f, 0f, p)
        inverted = out
        return out
    }
}
