package com.ahmed.fpssetter

import android.content.Context
import android.content.SharedPreferences

/** All saved settings live here. */
object Cfg {
    private const val PREFS = "cfg"

    const val GAMES = "games"            // set of package names
    const val HZ = "hz"                  // 0 = highest the screen supports
    const val LOCK = "lock"
    const val UNCAP = "uncap"
    const val PERF = "perf"
    const val SAVER_OFF = "saverOff"
    const val DND = "dnd"
    const val OVERLAY = "overlay"
    const val SCALE = "scale"            // render scale in percent
    const val RAM_MODE = "ramMode"       // 0 off, 1 normal, 2 aggressive
    const val RAM_INTERVAL = "ramInterval"
    const val KEEP = "keep"

    const val RAM_OFF = 0
    const val RAM_NORMAL = 1
    const val RAM_AGGRESSIVE = 2

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun games(p: SharedPreferences): Set<String> =
        p.getStringSet(GAMES, emptySet())?.toSet() ?: emptySet()
}
