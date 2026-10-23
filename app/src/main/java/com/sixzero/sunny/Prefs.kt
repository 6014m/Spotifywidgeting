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

    var vizMirror: Boolean
        get() = sp.getBoolean("vizMirror", true)
        set(v) = sp.edit().putBoolean("vizMirror", v).apply()
}
