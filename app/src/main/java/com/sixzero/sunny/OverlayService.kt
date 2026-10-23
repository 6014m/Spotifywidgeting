package com.sixzero.sunny

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Foreground service that pins an [OverlayView] over the Sunny Player widget
 * while the home screen is showing, and feeds it audio waveform data.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var view: OverlayView? = null
    private var attached = false
    private var visualizer: Visualizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; refresh() }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> { screenOn = true; refresh() }
                Intent.ACTION_WALLPAPER_CHANGED -> { Wallpaper.invalidate(); view?.invalidate() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForeground()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_WALLPAPER_CHANGED)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canOverlay(this)) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_CALIBRATE) { calibrate(); return START_STICKY }
        refresh()
        return START_STICKY
    }

    private fun ensureView(): OverlayView {
        val v = view ?: OverlayView(this).also { view = it }
        val prefs = Prefs(this)
        v.ghost = prefs.ghostText
        v.invert = prefs.invertText
        v.shadow = prefs.textShadow
        v.artAccent = prefs.artAccent
        return v
    }

    private fun layoutParams(full: Boolean, r: Rect): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (full) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        return WindowManager.LayoutParams(
            if (full) WindowManager.LayoutParams.MATCH_PARENT else r.width(),
            if (full) WindowManager.LayoutParams.MATCH_PARENT else r.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (full) 0 else r.left
            y = if (full) 0 else r.top
        }
    }

    private fun attach(lp: WindowManager.LayoutParams) {
        val v = ensureView()
        try {
            if (attached) wm.updateViewLayout(v, lp) else { wm.addView(v, lp); attached = true }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "overlay attach failed", e)
        }
    }

    private fun detach() {
        val v = view ?: return
        if (attached) try { wm.removeView(v) } catch (_: Exception) {}
        attached = false
    }

    /** Decide whether the overlay should be up right now and apply it. */
    private fun refresh() {
        val prefs = Prefs(this)
        val v = ensureView()
        if (v.calibrating) return
        val shouldShow = prefs.visualizerOn && prefs.calibrated && screenOn && HomeDetector.onHome
        if (!shouldShow) {
            stopCapture()
            detach()
            active = false
            WidgetUpdater.updateAll(this)
            return
        }
        val r = Rect(prefs.rectLeft, prefs.rectTop, prefs.rectLeft + prefs.rectWidth, prefs.rectTop + prefs.rectHeight)
        v.rect = r
        v.visibility = View.VISIBLE
        attach(layoutParams(false, r))
        val wasActive = active
        active = true
        if (!wasActive) WidgetUpdater.updateAll(this)   // widget drops its own art/text so nothing doubles up
        if (PlayerState.playing) startCapture() else stopCapture()
        v.invalidate()
    }

    private fun calibrate() {
        val prefs = Prefs(this)
        val v = ensureView()
        val bounds = Wallpaper.screenBounds(this)
        val d = resources.displayMetrics.density
        val initial = if (prefs.calibrated)
            Rect(prefs.rectLeft, prefs.rectTop, prefs.rectLeft + prefs.rectWidth, prefs.rectTop + prefs.rectHeight)
        else {
            val w = (bounds.width() * 0.92f).toInt(); val h = (180 * d).toInt()
            val l = (bounds.width() - w) / 2; val t = (bounds.height() * 0.58f).toInt()
            Rect(l, t, l + w, t + h)
        }
        v.onCalibrated = { r ->
            if (r != null) {
                prefs.rectLeft = r.left; prefs.rectTop = r.top
                prefs.rectWidth = r.width(); prefs.rectHeight = r.height()
                if (!prefs.visualizerOn) prefs.visualizerOn = true
            }
            // calibration window is the full-screen one; swap back to the pinned one
            detach()
            refresh()
        }
        v.rect = initial
        attach(layoutParams(true, initial))
        v.startCalibration(initial)
    }

    // ---- audio ---------------------------------------------------------------

    private fun startCapture() {
        if (visualizer != null || !hasMic(this)) return
        try {
            val vz = Visualizer(0)
            vz.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(512)
            vz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {
                    if (waveform != null) view?.pushWaveform(waveform)
                }
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {}
            }, Visualizer.getMaxCaptureRate(), true, false)
            vz.enabled = true
            visualizer = vz
        } catch (e: Exception) {
            android.util.Log.w(TAG, "visualizer unavailable", e)
        }
    }

    private fun stopCapture() {
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        view?.postInvalidate()
    }

    // ---- plumbing ------------------------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_MIN))
        val tap = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_viz)
            .setContentTitle("Sunny overlay running")
            .setContentText("Animates the player widget on your home screen")
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        stopCapture()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        detach()
        view = null
        active = false
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        WidgetUpdater.updateAll(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 60
        const val ACTION_CALIBRATE = "com.sixzero.sunny.CALIBRATE"

        @Volatile private var instance: OverlayService? = null
        /** True while the overlay is drawn; the widget then renders only its buttons. */
        @Volatile var active: Boolean = false

        fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)
        fun hasMic(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Playback, home/away, or settings changed. Safe to call from anywhere. */
        fun onStateChanged(context: Context) {
            val prefs = Prefs(context)
            val svc = instance
            if (!prefs.visualizerOn) { svc?.stopSelf(); return }
            if (svc != null) svc.main.post { svc.refresh() }
            else if (canOverlay(context)) {
                try { context.startForegroundService(Intent(context, OverlayService::class.java)) }
                catch (e: Exception) { android.util.Log.w(TAG, "could not start", e) }
            }
        }

        fun startCalibration(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_CALIBRATE))
        }
    }
}
