package com.sixzero.sunny

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Foreground service that floats a VisualizerView over everything (launcher
 * included) and feeds it FFT data from the system audio output.
 */
class VisualizerService : Service() {

    private lateinit var wm: WindowManager
    private var view: VisualizerView? = null
    private var visualizer: Visualizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var shown = false

    private val idleDecay = object : Runnable {
        override fun run() {
            val v = view ?: return
            v.decay()
            if (!PlayerState.playing) main.postDelayed(this, 40)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        if (!prefs.visualizerOn || !canOverlay(this) || !hasMic(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureView(prefs)
        applyPlayback()
        return START_STICKY
    }

    private fun ensureView(prefs: Prefs) {
        val d = resources.displayMetrics.density
        val heightPx = (prefs.vizHeightDp * d).toInt()
        val v = view ?: VisualizerView(this).also { view = it }
        v.bars = prefs.vizBars
        v.mirror = prefs.vizMirror
        v.alpha255 = prefs.vizAlpha
        applyColors()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        try {
            if (shown) wm.updateViewLayout(v, lp) else { wm.addView(v, lp); shown = true }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "overlay failed", e)
        }
    }

    private fun applyColors() {
        val prefs = Prefs(this)
        view?.colorA = if (prefs.artAccent) PlayerState.accent else PlayerState.AMBER
        view?.colorB = if (prefs.artAccent) PlayerState.accentLight else PlayerState.PEACH
    }

    private fun applyPlayback() {
        applyColors()
        if (PlayerState.playing) {
            view?.visibility = android.view.View.VISIBLE
            startCapture()
        } else {
            stopCapture()
            main.removeCallbacks(idleDecay)
            main.post(idleDecay)
            // hide after the bars have settled
            main.postDelayed({ if (!PlayerState.playing) view?.visibility = android.view.View.GONE }, 900)
        }
    }

    private fun startCapture() {
        if (visualizer != null) return
        try {
            val vz = Visualizer(0)   // session 0 = the mix of everything playing
            val size = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            vz.captureSize = size
            vz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (fft != null) view?.push(bands(fft, view?.bars ?: 36))
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            vz.enabled = true
            visualizer = vz
        } catch (e: Exception) {
            android.util.Log.w(TAG, "visualizer unavailable", e)
        }
    }

    private fun stopCapture() {
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        visualizer = null
    }

    /**
     * Collapse the FFT into [count] log-spaced bands, scaled to roughly 0..1.
     * fft layout: [dc, nyquist, re1, im1, re2, im2, ...]
     */
    private fun bands(fft: ByteArray, count: Int): FloatArray {
        val n = fft.size / 2
        val out = FloatArray(count)
        val minBin = 2
        val maxBin = (n * 0.6).toInt().coerceAtLeast(minBin + count)   // ignore the very top end
        val logMin = ln(minBin.toDouble()); val logMax = ln(maxBin.toDouble())
        for (b in 0 until count) {
            val lo = Math.exp(logMin + (logMax - logMin) * b / count).toInt().coerceIn(minBin, n - 1)
            val hi = Math.exp(logMin + (logMax - logMin) * (b + 1) / count).toInt().coerceIn(lo + 1, n)
            var sum = 0.0
            for (k in lo until hi) {
                val re = fft[2 * k].toInt(); val im = fft[2 * k + 1].toInt()
                sum += hypot(re.toDouble(), im.toDouble())
            }
            val mag = sum / (hi - lo)
            // dB-ish scale, then boost the highs a touch since they're naturally quieter
            val db = (20 * Math.log10(mag + 1)).toFloat()
            val tilt = 1f + b.toFloat() / count * 0.9f
            out[b] = ((db / 42f) * tilt).coerceIn(0f, 1f)
        }
        return out
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Visualizer", NotificationManager.IMPORTANCE_MIN))
        val tap = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("Sunny visualizer running")
            .setContentText("Reacts to whatever's playing")
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        stopCapture()
        main.removeCallbacksAndMessages(null)
        try { if (shown) wm.removeView(view) } catch (_: Exception) {}
        shown = false
        view = null
        instance = null
        super.onDestroy()
    }

    init { instance = this }

    companion object {
        private const val TAG = "VisualizerService"
        private const val CHANNEL = "viz"
        private const val NOTIF_ID = 60
        @Volatile private var instance: VisualizerService? = null

        fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)
        fun hasMic(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Called by MediaWatcher on every playback change and by MainActivity on settings changes. */
        fun onPlaybackChanged(context: Context) {
            val prefs = Prefs(context)
            val svc = instance
            if (!prefs.visualizerOn) { svc?.stopSelf(); return }
            if (svc != null) {
                svc.main.post { svc.ensureView(prefs); svc.applyPlayback() }
            } else if (canOverlay(context) && hasMic(context)) {
                try {
                    context.startForegroundService(Intent(context, VisualizerService::class.java))
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "could not start", e)
                }
            }
        }
    }
}
