package com.sixzero.sunny

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, WidgetUpdater.buildPlayer(context, mgr.getAppWidgetOptions(it), it)) }
        MediaWatcher.requestRebindIfNeeded(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        mgr.updateAppWidget(id, WidgetUpdater.buildPlayer(context, newOptions, id))
    }
}
