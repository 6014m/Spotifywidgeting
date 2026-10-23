package com.sixzero.sunny

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
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

        bindSwitch(R.id.swGhost, prefs.ghostText) { prefs.ghostText = it; refreshWidgets() }
        bindSwitch(R.id.swShadow, prefs.textShadow) { prefs.textShadow = it; refreshWidgets() }
        bindSwitch(R.id.swArtAccent, prefs.artAccent) { prefs.artAccent = it; refreshWidgets(); VisualizerService.onPlaybackChanged(this) }
        bindSwitch(R.id.swViz, prefs.visualizerOn) { prefs.visualizerOn = it; applyViz() }
        bindSwitch(R.id.swMirror, prefs.vizMirror) { prefs.vizMirror = it; applyViz() }

        bindSeek(R.id.sbHeight, prefs.vizHeightDp) { prefs.vizHeightDp = maxOf(it, 30); applyViz() }
        bindSeek(R.id.sbBars, prefs.vizBars) { prefs.vizBars = maxOf(it, 8); applyViz() }
        bindSeek(R.id.sbAlpha, prefs.vizAlpha) { prefs.vizAlpha = it; applyViz() }
    }

    override fun onResume() {
        super.onResume()
        MediaWatcher.requestRebindIfNeeded(this)
        val s = StringBuilder()
        s.append(if (MediaWatcher.isEnabled(this)) "✓ Notification access" else "✗ Notification access — widgets stay empty without it")
        s.append('\n')
        s.append(if (VisualizerService.canOverlay(this)) "✓ Overlay" else "✗ Overlay — visualizer can't draw")
        s.append('\n')
        s.append(if (VisualizerService.hasMic(this)) "✓ Microphone" else "✗ Microphone — visualizer can't hear audio")
        if (PlayerState.hasTrack) s.append("\n\nNow: ${PlayerState.title} — ${PlayerState.artist}")
        findViewById<TextView>(R.id.status).text = s
        refreshWidgets()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        applyViz()
    }

    private fun applyViz() {
        if (prefs.visualizerOn && VisualizerService.canOverlay(this) && VisualizerService.hasMic(this)) {
            startForegroundService(Intent(this, VisualizerService::class.java))
        }
        VisualizerService.onPlaybackChanged(this)
    }

    private fun refreshWidgets() = WidgetUpdater.updateAll(this)

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun bindSeek(id: Int, initial: Int, onChange: (Int) -> Unit) {
        val sb = findViewById<SeekBar>(id)
        sb.progress = initial
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
}
