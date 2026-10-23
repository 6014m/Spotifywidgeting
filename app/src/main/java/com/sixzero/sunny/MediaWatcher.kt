package com.sixzero.sunny

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Notification-listener service that watches media sessions. We never touch
 * notifications themselves; the listener binding is just what Android requires
 * before an app may read other apps' media sessions.
 */
class MediaWatcher : NotificationListenerService() {

    private lateinit var sessions: MediaSessionManager
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (PlayerState.playing) {
                WidgetUpdater.updateProgress(this@MediaWatcher)
                main.postDelayed(this, 2000)
            }
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pickController(list)
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { readMetadata(metadata); push() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { readPlayback(state); push() }
        override fun onSessionDestroyed() {
            controller = null
            PlayerState.reset()
            push()
            pickController(safeSessions())
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        sessions = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val me = ComponentName(this, MediaWatcher::class.java)
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, me)
        } catch (e: SecurityException) {
            Log.w(TAG, "no session access yet", e)
        }
        rescan()
    }

    override fun onListenerDisconnected() {
        try { sessions.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(callback)
        controller = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    /** Re-pick the session; called from the app and widgets when things look stale. */
    fun rescan() {
        val list = safeSessions()
        lastSeen = list.map { it.packageName }
        pickController(list)
    }

    private fun safeSessions(): List<MediaController> = try {
        sessions.getActiveSessions(ComponentName(this, MediaWatcher::class.java))
    } catch (e: SecurityException) {
        emptyList()
    }

    /** Prefer Spotify; otherwise whichever session is playing; otherwise the first with metadata. */
    private fun pickController(list: List<MediaController>?) {
        val all = list ?: emptyList()
        val chosen = all.firstOrNull { it.packageName == "com.spotify.music" }
            ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { it.metadata != null }
        if (chosen?.sessionToken == controller?.sessionToken && chosen != null) return
        controller?.unregisterCallback(callback)
        controller = chosen
        if (chosen == null) {
            PlayerState.reset()
            push()
            return
        }
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

        val art: Bitmap? = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val key = (m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: "") + PlayerState.title + PlayerState.artist
        if (key != PlayerState.artKey || PlayerState.art == null) {
            PlayerState.artKey = key
            PlayerState.art = art?.let { Art.square(it, 320) }
            PlayerState.updateAccent(PlayerState.art)
        }
    }

    private fun readPlayback(s: PlaybackState?) {
        val now = SystemClock.elapsedRealtime()
        if (s == null) { PlayerState.playing = false; return }
        PlayerState.playing = s.state == PlaybackState.STATE_PLAYING
        PlayerState.speed = if (s.playbackSpeed == 0f) 1f else s.playbackSpeed
        // Re-base position onto our own clock so widgets can extrapolate.
        val elapsed = now - (s.lastPositionUpdateTime.takeIf { it > 0 } ?: now)
        PlayerState.positionMs = (s.position + if (PlayerState.playing) (elapsed * PlayerState.speed).toLong() else 0L).coerceAtLeast(0)
        PlayerState.positionAtMs = now
        main.removeCallbacks(tick)
        if (PlayerState.playing) main.postDelayed(tick, 2000)
    }

    private fun push() {
        WidgetUpdater.updateAll(this)
        OverlayService.onStateChanged(this)
    }

    /** Transport controls used by the widget buttons. */
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

    companion object {
        private const val TAG = "MediaWatcher"
        @Volatile var instance: MediaWatcher? = null
        /** Package names of the sessions seen on the last scan, for the debug readout. */
        @Volatile var lastSeen: List<String> = emptyList()

        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(context.packageName)
        }

        /**
         * Fallback used when the service isn't bound (aggressive ROMs kill it):
         * reading sessions only needs the listener to be *enabled*, not running.
         * Fills PlayerState from the best session and returns the controller.
         */
        fun snapshot(context: Context): MediaController? {
            if (!isEnabled(context)) return null
            val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = try {
                mgr.getActiveSessions(ComponentName(context, MediaWatcher::class.java))
            } catch (e: SecurityException) { return null }
            lastSeen = list.map { it.packageName }
            val c = list.firstOrNull { it.packageName == "com.spotify.music" }
                ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull { it.metadata != null } ?: return null
            PlayerState.packageName = c.packageName
            val m = c.metadata
            if (m != null) {
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
            val st = c.playbackState
            val now = SystemClock.elapsedRealtime()
            PlayerState.playing = st?.state == PlaybackState.STATE_PLAYING
            if (st != null) {
                PlayerState.speed = if (st.playbackSpeed == 0f) 1f else st.playbackSpeed
                val elapsed = now - (st.lastPositionUpdateTime.takeIf { it > 0 } ?: now)
                PlayerState.positionMs = (st.position + if (PlayerState.playing) (elapsed * PlayerState.speed).toLong() else 0L).coerceAtLeast(0)
                PlayerState.positionAtMs = now
            }
            return c
        }

        /** Ask the system to (re)bind us if access is granted but the service isn't running. */
        fun requestRebindIfNeeded(context: Context) {
            if (instance != null || !isEnabled(context)) return
            try {
                NotificationListenerService.requestRebind(ComponentName(context, MediaWatcher::class.java))
            } catch (_: Exception) {}
        }
    }
}
