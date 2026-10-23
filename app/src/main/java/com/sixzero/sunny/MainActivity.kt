package com.sixzero.sunny

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnMic).setOnClickListener {
            val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(wanted.toTypedArray(), 1)
        }
        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnFiles).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnCalibrate).setOnClickListener {
            if (!OverlayService.canOverlay(this)) {
                findViewById<TextView>(R.id.status).text = "Grant \"Draw over other apps\" first."
                return@setOnClickListener
            }
            OverlayService.startCalibration(this)
            // drop to the launcher so the real widget is visible under the frame
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        bindSwitch(R.id.swViz, prefs.visualizerOn) { prefs.visualizerOn = it; applyOverlay() }
        bindSwitch(R.id.swInvert, prefs.invertText) { prefs.invertText = it; applyOverlay() }
        bindSwitch(R.id.swGhost, prefs.ghostText) { prefs.ghostText = it; refreshWidgets(); applyOverlay() }
        bindSwitch(R.id.swShadow, prefs.textShadow) { prefs.textShadow = it; refreshWidgets(); applyOverlay() }
        bindSwitch(R.id.swArtAccent, prefs.artAccent) { prefs.artAccent = it; refreshWidgets(); applyOverlay() }
    }

    override fun onResume() {
        super.onResume()
        MediaWatcher.requestRebindIfNeeded(this)
        MediaWatcher.instance?.rescan()
        Wallpaper.invalidate()
        val s = StringBuilder()
        fun line(ok: Boolean, name: String, hint: String) = s.append(if (ok) "✓ $name\n" else "✗ $name — $hint\n")
        line(MediaWatcher.isEnabled(this), "Notification access", "widgets stay empty without it")
        line(OverlayService.canOverlay(this), "Overlay", "no live layer")
        line(OverlayService.hasMic(this), "Microphone", "wave stays flat")
        line(HomeDetector.isEnabled(this), "Accessibility", "overlay can't tell when you leave home")
        line(Wallpaper.readable, "All files access", "inverted text falls back to outline")
        line(prefs.calibrated, "Live layer positioned", "tap step 6")
        s.append('\n')
        s.append(if (MediaWatcher.instance == null) "Listener: not connected (toggle notification access off and on)"
                 else "Listener: connected, sessions seen: ${MediaWatcher.lastSeen.ifEmpty { listOf("none") }.joinToString()}")
        if (PlayerState.hasTrack) s.append("\nNow: ${PlayerState.title} — ${PlayerState.artist}${if (PlayerState.playing) " (playing)" else " (paused)"}")
        findViewById<TextView>(R.id.status).text = s
        refreshWidgets()
        applyOverlay()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        applyOverlay()
    }

    private fun applyOverlay() = OverlayService.onStateChanged(this)

    private fun refreshWidgets() = WidgetUpdater.updateAll(this)

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
}
