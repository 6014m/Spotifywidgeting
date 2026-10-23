package com.sixzero.sunny

import android.content.Context
import android.content.SharedPreferences

/** All user-facing settings. Widgets and the overlay read from here. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("sunny", Context.MODE_PRIVATE)

    var ghostText: Boolean
        get() = sp.getBoolean("ghost", false)
        set(v) = sp.edit().putBoolean("ghost", v).apply()

    var textShadow: Boolean
        get() = sp.getBoolean("shadow", true)
        set(v) = sp.edit().putBoolean("shadow", v).apply()

    var artAccent: Boolean
        get() = sp.getBoolean("artAccent", true)
        set(v) = sp.edit().putBoolean("artAccent", v).apply()

    var visualizerOn: Boolean
        get() = sp.getBoolean("viz", false)
        set(v) = sp.edit().putBoolean("viz", v).apply()

    /** Overlay height in dp. */
    var vizHeightDp: Int
        get() = sp.getInt("vizHeight", 120)
        set(v) = sp.edit().putInt("vizHeight", v).apply()

    var vizBars: Int
        get() = sp.getInt("vizBars", 36)
        set(v) = sp.edit().putInt("vizBars", v).apply()

    var vizAlpha: Int
        get() = sp.getInt("vizAlpha", 200)
        set(v) = sp.edit().putInt("vizAlpha", v).apply()

    var invertText: Boolean
        get() = sp.getBoolean("invert", true)
        set(v) = sp.edit().putBoolean("invert", v).apply()

    /** Calibrated overlay rectangle in screen pixels; -1 means not calibrated. */
    var rectLeft: Int
        get() = sp.getInt("rl", -1)
        set(v) = sp.edit().putInt("rl", v).apply()
    var rectTop: Int
        get() = sp.getInt("rt", -1)
        set(v) = sp.edit().putInt("rt", v).apply()
    var rectWidth: Int
        get() = sp.getInt("rw", -1)
        set(v) = sp.edit().putInt("rw", v).apply()
    var rectHeight: Int
        get() = sp.getInt("rh", -1)
        set(v) = sp.edit().putInt("rh", v).apply()
    val calibrated: Boolean get() = rectWidth > 0 && rectHeight > 0

    /** True when the rect came from the accessibility lookup rather than manual dragging. */
    var autoLocated: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var vizMirror: Boolean
        get() = sp.getBoolean("vizMirror", true)
        set(v) = sp.edit().putBoolean("vizMirror", v).apply()
}
