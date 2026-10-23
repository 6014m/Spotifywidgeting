package com.sixzero.sunny

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives widget button taps and forwards them to the media session. */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        SessionWatcher.start(context)
        SessionWatcher.transport(action)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ WidgetUpdater.updateAll(context) }, 500)
    }

    companion object {
        const val PLAY_PAUSE = "com.sixzero.sunny.PLAY_PAUSE"
        const val NEXT = "com.sixzero.sunny.NEXT"
        const val PREV = "com.sixzero.sunny.PREV"

        fun pending(context: Context, action: String): PendingIntent {
            val i = Intent(context, WidgetActions::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
