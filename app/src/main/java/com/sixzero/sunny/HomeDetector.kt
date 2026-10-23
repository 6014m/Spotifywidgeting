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
        launchers = launcherPackages(this)
        onHome = true   // assume home until told otherwise
        OverlayService.onStateChanged(this)
        scheduleLocate()
    }

    private var retries = 0

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val locate = Runnable { locateWidget() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return   // our own overlay
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val home = pkg == launcher || launchers.contains(pkg)
                if (home) launcher = pkg
                if (home != onHome) {
                    onHome = home
                    OverlayService.onStateChanged(this)
                }
                if (home) scheduleLocate()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Page swipes and widget moves: follow the widget quickly, hide when it leaves.
                if (onHome && (pkg == launcher || launchers.contains(pkg))) scheduleLocate(60)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Our own overlay attaching/detaching fires this too; don't rescan for that.
                if (onHome && android.os.SystemClock.elapsedRealtime() - OverlayService.lastLayoutAt > 1200) scheduleLocate()
            }
        }
    }

    private fun scheduleLocate(delay: Long = 150) {
        retries = 0
        main.removeCallbacks(locate)
        main.postDelayed(locate, delay)
    }

    /** Try again a few times: the launcher's tree is often empty for a moment after a window change. */
    private fun retryLocate() {
        if (retries++ >= 8) return
        main.removeCallbacks(locate)
        main.postDelayed(locate, 700)
    }

    /**
     * Walk the launcher's view tree for the widget root (tagged by content
     * description) and remember its screen bounds. Handles page swipes too:
     * if the widget isn't on the visible page the overlay is told to hide.
     */
    private fun locateWidget() {
        // Prefer the launcher's window from the full window list; fall back to the active one.
        val roots = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        try {
            windows?.forEach { w ->
                val r = w.root ?: return@forEach
                val p = r.packageName?.toString() ?: ""
                if (p == launcher || launchers.contains(p)) roots += r
            }
        } catch (_: Exception) {}
        try { rootInActiveWindow?.let { if (roots.isEmpty()) roots += it } } catch (_: Exception) {}
        if (roots.isEmpty()) { lastResult = "no launcher window yet"; retryLocate(); return }
        val marker = getString(R.string.marker_player)
        var node: android.view.accessibility.AccessibilityNodeInfo? = null
        for (root in roots) {
            val hits = try { root.findAccessibilityNodeInfosByText(marker) } catch (_: Exception) { null }
            node = hits?.firstOrNull { it.contentDescription?.toString() == marker }
            if (node == null) node = findByDesc(root, marker, 0)
            if (node != null) break
        }
        val r = android.graphics.Rect()
        if (node != null) {
            node.getBoundsInScreen(r)
            val bounds = Wallpaper.screenBounds(this)
            // Only accept a widget that is fully on screen (not mid-swipe or on another page).
            val onScreen = r.width() > 0 && r.height() > 0 && r.left >= 0 && r.right <= bounds.width()
            if (onScreen) {
                val prefs = Prefs(this)
                var changed = !widgetVisible
                if (r.left != prefs.rectLeft || r.top != prefs.rectTop || r.width() != prefs.rectWidth || r.height() != prefs.rectHeight) {
                    prefs.rectLeft = r.left; prefs.rectTop = r.top; prefs.rectWidth = r.width(); prefs.rectHeight = r.height()
                    prefs.autoLocated = true
                    changed = true
                }
                widgetVisible = true
                lastResult = "widget at ${r.left},${r.top} ${r.width()}x${r.height()}"
                if (changed) OverlayService.onStateChanged(this)
                return
            }
            lastResult = "widget found but off-screen (${r.left}..${r.right})"
        } else {
            lastResult = "widget node not found in ${roots.map { it.packageName }.joinToString()}"
            retryLocate()
        }
        val was = widgetVisible
        widgetVisible = false
        if (was) OverlayService.onStateChanged(this)
    }

    /** Manual tree walk, since findAccessibilityNodeInfosByText skips some launchers' hosts. */
    private fun findByDesc(n: android.view.accessibility.AccessibilityNodeInfo?, marker: String, depth: Int): android.view.accessibility.AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        if (n.contentDescription?.toString() == marker) return n
        for (i in 0 until n.childCount) {
            val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
            val hit = findByDesc(c, marker, depth + 1)
            if (hit != null) return hit
        }
        return null
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
        @Volatile var launchers: Set<String> = emptySet()
        @Volatile var lastResult: String = "not scanned yet"

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return flat.contains(context.packageName + "/")
        }

        fun launcherPackages(context: Context): Set<String> {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            return context.packageManager.queryIntentActivities(i, 0)
                .map { it.activityInfo.packageName }.filter { it != "android" }.toSet()
        }

        fun launcherPackage(context: Context): String {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            return context.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName ?: ""
        }
    }
}
