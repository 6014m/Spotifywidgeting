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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Ignore our own overlay and transient system bits that fire without taking over the screen.
            if (pkg == packageName) return
            val home = pkg == launcher
            if (home != onHome) {
                onHome = home
                OverlayService.onStateChanged(this)
            }
        }
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
