package com.sixzero.sunny

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Watches media sessions from whatever process component is alive (the overlay
 * service, the app, a widget update). Reading sessions only requires the
 * notification listener to be *enabled* in settings, not actually bound, which
 * matters on ROMs that never start the listener service.
 */
object SessionWatcher {
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var mgr: MediaSessionManager? = null
    private var controller: MediaController? = null
    @Volatile var started = false
        private set

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { readMetadata(metadata); push() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { readPlayback(state); push() }
        override fun onSessionDestroyed() { controller = null; PlayerState.reset(); push(); rescan() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (PlayerState.playing) {
                appContext?.let { WidgetUpdater.updateProgress(it) }
                main.postDelayed(this, 2000)
            }
        }
    }

    /** Idempotent. Safe to call from any component on every start. */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!MediaWatcher.isEnabled(app)) return
        if (started) { rescan(); return }
        val m = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        mgr = m
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, ComponentName(app, MediaWatcher::class.java), main)
            started = true
        } catch (e: SecurityException) {
            return
        }
        rescan()
    }

    fun rescan() {
        val app = appContext ?: return
        val list = try {
            mgr?.getActiveSessions(ComponentName(app, MediaWatcher::class.java)) ?: emptyList()
        } catch (e: SecurityException) { emptyList() }
        MediaWatcher.lastSeen = list.map { it.packageName }
        pick(list)
    }

    private fun pick(list: List<MediaController>?) {
        val all = list ?: emptyList()
        val chosen = all.firstOrNull { it.packageName == "com.spotify.music" }
            ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { it.metadata != null }
        if (chosen != null && controller != null && chosen.sessionToken == controller?.sessionToken) {
            // same session; just refresh the snapshot
            readMetadata(chosen.metadata); readPlayback(chosen.playbackState); push(); return
        }
        controller?.unregisterCallback(callback)
        controller = chosen
        if (chosen == null) { PlayerState.reset(); push(); return }
        chosen.registerCallback(callback, main)
        PlayerState.packageName = chosen.packageName
        readMetadata(chosen.metadata)
        readPlayback(chosen.playbackState)
        push()
    }

    private fun readMetadata(m: MediaMetadata?) {
        if (m == null) { PlayerState.title = ""; PlayerState.artist = ""; return }
        PlayerState.title = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        PlayerState.artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        PlayerState.durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val key = (m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: "") + PlayerState.title + PlayerState.artist
        if (key != PlayerState.artKey || PlayerState.art == null) {
            val art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            PlayerState.artKey = key
            PlayerState.art = art?.let { Art.square(it, 320) }
            PlayerState.updateAccent(PlayerState.art)
        }
    }

    private fun readPlayback(s: PlaybackState?) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (s == null) { PlayerState.playing = false; return }
        PlayerState.playing = s.state == PlaybackState.STATE_PLAYING
        PlayerState.speed = if (s.playbackSpeed == 0f) 1f else s.playbackSpeed
        val elapsed = now - (s.lastPositionUpdateTime.takeIf { it > 0 } ?: now)
        PlayerState.positionMs = (s.position + if (PlayerState.playing) (elapsed * PlayerState.speed).toLong() else 0L).coerceAtLeast(0)
        PlayerState.positionAtMs = now
        main.removeCallbacks(tick)
        if (PlayerState.playing) main.postDelayed(tick, 2000)
    }

    private fun push() {
        val app = appContext ?: return
        WidgetUpdater.updateAll(app)
        OverlayService.onStateChanged(app)
    }

    fun transport(action: String) {
        val c = controller ?: return
        when (action) {
            WidgetActions.PLAY_PAUSE ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c.transportControls.play()
            WidgetActions.NEXT -> c.transportControls.skipToNext()
            WidgetActions.PREV -> c.transportControls.skipToPrevious()
        }
    }
}
