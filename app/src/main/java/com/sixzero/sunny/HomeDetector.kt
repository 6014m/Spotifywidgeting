package com.sixzero.sunny

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Tiny accessibility service whose only job is to say whether the launcher is
 * in front. It never reads window content (canRetrieveWindowContent=false).
 */
class HomeDetector : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        launcher = launcherPackage(this)
        onHome = true   // assume home until told otherwise
        OverlayService.onStateChanged(this)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val locate = Runnable { locateWidget() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return   // our own overlay
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val home = pkg == launcher
                if (home != onHome) {
                    onHome = home
                    OverlayService.onStateChanged(this)
                }
                if (home) scheduleLocate()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (onHome) scheduleLocate()
        }
    }

    private fun scheduleLocate() {
        main.removeCallbacks(locate)
        main.postDelayed(locate, 150)
    }

    /**
     * Walk the launcher's view tree for the widget root (tagged by content
     * description) and remember its screen bounds. Handles page swipes too:
     * if the widget isn't on the visible page the overlay is told to hide.
     */
    private fun locateWidget() {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        if (root.packageName?.toString() != launcher) return
        val marker = getString(R.string.marker_player)
        val hits = try { root.findAccessibilityNodeInfosByText(marker) } catch (_: Exception) { null }
        val node = hits?.firstOrNull { it.contentDescription?.toString() == marker }
        val r = android.graphics.Rect()
        if (node != null) {
            node.getBoundsInScreen(r)
            val bounds = Wallpaper.screenBounds(this)
            // Only accept a widget that is fully on screen (not mid-swipe or on another page).
            val onScreen = r.width() > 0 && r.height() > 0 && r.left >= 0 && r.right <= bounds.width()
            if (onScreen) {
                val prefs = Prefs(this)
                if (r.left != prefs.rectLeft || r.top != prefs.rectTop || r.width() != prefs.rectWidth || r.height() != prefs.rectHeight) {
                    prefs.rectLeft = r.left; prefs.rectTop = r.top; prefs.rectWidth = r.width(); prefs.rectHeight = r.height()
                    prefs.autoLocated = true
                }
                widgetVisible = true
                OverlayService.onStateChanged(this)
                return
            }
        }
        widgetVisible = false
        OverlayService.onStateChanged(this)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        onHome = true
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: HomeDetector? = null
        @Volatile var onHome: Boolean = true
        /** False when the widget is scrolled off to another home page. */
        @Volatile var widgetVisible: Boolean = true
        @Volatile var launcher: String = ""

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return flat.contains(context.packageName + "/")
        }

        fun launcherPackage(context: Context): String {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            return context.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName ?: ""
        }
    }
}
