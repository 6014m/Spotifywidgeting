package com.sixzero.sunny

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * Notification-listener stub. Being *enabled* in settings is what grants media
 * session access; the real watching lives in [SessionWatcher] because some ROMs
 * never actually bind this service.
 */
class MediaWatcher : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        SessionWatcher.start(this)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    companion object {
        @Volatile var instance: MediaWatcher? = null
        /** Package names of the sessions seen on the last scan, for the debug readout. */
        @Volatile var lastSeen: List<String> = emptyList()

        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(context.packageName)
        }

        fun requestRebindIfNeeded(context: Context) {
            if (instance != null || !isEnabled(context)) return
            try { requestRebind(ComponentName(context, MediaWatcher::class.java)) } catch (_: Exception) {}
        }
    }
}
