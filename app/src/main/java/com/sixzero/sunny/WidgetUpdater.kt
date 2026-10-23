package com.sixzero.sunny

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews

/** Builds RemoteViews for both widgets from [PlayerState] and pushes them. */
object WidgetUpdater {

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        mgr.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java)).forEach { id ->
            mgr.updateAppWidget(id, buildPlayer(context, mgr.getAppWidgetOptions(id), id))
        }
        mgr.getAppWidgetIds(ComponentName(context, MiniWidget::class.java)).forEach { id ->
            mgr.updateAppWidget(id, buildMini(context, mgr.getAppWidgetOptions(id), id))
        }
    }

    /** Cheap partial update: only the progress bar bitmap, every couple of seconds. */
    fun updateProgress(context: Context) {
        if (OverlayService.active) return
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
        if (ids.isEmpty()) return
        val d = context.resources.displayMetrics.density
        ids.forEach { id ->
            val widthDp = widthDp(mgr.getAppWidgetOptions(id), 250)
            val rv = RemoteViews(context.packageName, R.layout.widget_player)
            rv.setImageViewBitmap(R.id.progress, Art.progressBar(
                ((widthDp - 12) * d).toInt(), (5 * d).toInt(),
                PlayerState.progress(SystemClock.elapsedRealtime()), accent(context)))
            mgr.partiallyUpdateAppWidget(id, rv)
        }
    }

    fun buildPlayer(context: Context, options: Bundle?, widgetId: Int): RemoteViews {
        val prefs = Prefs(context)
        val d = context.resources.displayMetrics.density
        val widthDp = widthDp(options, 250)
        val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: 110
        val rv = RemoteViews(context.packageName, R.layout.widget_player)
        val acc = accent(context)
        val soft = if (prefs.artAccent) PlayerState.accentSoft else PlayerState.PEACH

        if (OverlayService.active) {
            // The overlay draws art/text/progress; the widget only keeps the tap targets.
            val blank = Art.blank()
            rv.setImageViewBitmap(R.id.art, blank)
            rv.setImageViewBitmap(R.id.title, blank)
            rv.setImageViewBitmap(R.id.artist, blank)
            rv.setImageViewBitmap(R.id.progress, blank)
            wireButtons(context, rv, acc, soft)
            return rv
        }

        // Album art — fixed 88dp box in the layout; render slightly larger for crispness.
        val artPx = (88 * d).toInt()
        val radius = artPx / 6f
        val art = PlayerState.art
        rv.setImageViewBitmap(R.id.art,
            if (art != null) Art.rounded(art, artPx, radius, prefs.textShadow)
            else Art.placeholder(artPx, radius))

        // Text column width = widget width - padding - art - gap.
        val textWidthPx = ((widthDp - 12 - 88 - 12) * d).toInt()
        val titleSize = (heightDp.coerceIn(100, 220) / 7.2f) * d
        val artistSize = (heightDp.coerceIn(100, 220) / 10f) * d
        val light = if (prefs.artAccent) PlayerState.accentLight else PlayerState.CREAM
        val title = if (PlayerState.hasTrack) PlayerState.title else "Nothing playing"
        val artist = if (PlayerState.hasTrack) PlayerState.artist else "Spotify"
        rv.setImageViewBitmap(R.id.title, Art.text(title, textWidthPx, titleSize, true,
            PlayerState.CREAM, prefs.ghostText, light, acc, prefs.textShadow))
        rv.setImageViewBitmap(R.id.artist, Art.text(artist, textWidthPx, artistSize, false,
            0xF5FFEFD6.toInt(), prefs.ghostText, light, acc, prefs.textShadow))

        rv.setImageViewBitmap(R.id.progress, Art.progressBar(
            ((widthDp - 12) * d).toInt(), (5 * d).toInt(),
            PlayerState.progress(SystemClock.elapsedRealtime()), acc))

        wireButtons(context, rv, acc, soft)
        return rv
    }

    private fun wireButtons(context: Context, rv: RemoteViews, acc: Int, soft: Int) {
        rv.setImageViewResource(R.id.play, if (PlayerState.playing) R.drawable.ic_pause else R.drawable.ic_play)
        rv.setInt(R.id.play, "setColorFilter", acc)
        rv.setInt(R.id.prev, "setColorFilter", soft)
        rv.setInt(R.id.next, "setColorFilter", soft)
        rv.setOnClickPendingIntent(R.id.prev, WidgetActions.pending(context, WidgetActions.PREV))
        rv.setOnClickPendingIntent(R.id.play, WidgetActions.pending(context, WidgetActions.PLAY_PAUSE))
        rv.setOnClickPendingIntent(R.id.next, WidgetActions.pending(context, WidgetActions.NEXT))
        rv.setOnClickPendingIntent(R.id.root, openPlayer(context))
        rv.setOnClickPendingIntent(R.id.art, openPlayer(context))
    }

    fun buildMini(context: Context, options: Bundle?, widgetId: Int): RemoteViews {
        val prefs = Prefs(context)
        val d = context.resources.displayMetrics.density
        val widthDp = widthDp(options, 250)
        val rv = RemoteViews(context.packageName, R.layout.widget_mini)
        val artPx = (40 * d).toInt()
        val art = PlayerState.art
        rv.setImageViewBitmap(R.id.art,
            if (art != null) Art.rounded(art, artPx, artPx / 5f, prefs.textShadow)
            else Art.placeholder(artPx, artPx / 5f))
        val acc = accent(context)
        val light = if (prefs.artAccent) PlayerState.accentLight else PlayerState.CREAM
        val line = if (PlayerState.hasTrack) "${PlayerState.title}  ·  ${PlayerState.artist}" else "Nothing playing"
        val textWidthPx = ((widthDp - 8 - 40 - 10 - 40 - 36) * d).toInt()
        rv.setImageViewBitmap(R.id.title, Art.text(line, textWidthPx, 15 * d, true,
            PlayerState.CREAM, prefs.ghostText, light, acc, prefs.textShadow))
        rv.setImageViewResource(R.id.play, if (PlayerState.playing) R.drawable.ic_pause else R.drawable.ic_play)
        rv.setInt(R.id.play, "setColorFilter", acc)
        rv.setInt(R.id.next, "setColorFilter", if (prefs.artAccent) PlayerState.accentSoft else PlayerState.PEACH)
        rv.setOnClickPendingIntent(R.id.play, WidgetActions.pending(context, WidgetActions.PLAY_PAUSE))
        rv.setOnClickPendingIntent(R.id.next, WidgetActions.pending(context, WidgetActions.NEXT))
        rv.setOnClickPendingIntent(R.id.root, openPlayer(context))
        return rv
    }

    private fun accent(context: Context): Int =
        if (Prefs(context).artAccent) PlayerState.accent else PlayerState.AMBER

    private fun widthDp(options: Bundle?, fallback: Int): Int =
        options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: fallback

    /** Tap-through to whichever app owns the session (Spotify by default), or our setup screen. */
    private fun openPlayer(context: Context): PendingIntent {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(PlayerState.packageName)
            ?: pm.getLaunchIntentForPackage("com.spotify.music")
            ?: Intent(context, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 7, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
