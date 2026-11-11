package com.ahmed.fpssetter

import android.content.Context
import android.content.SharedPreferences

/** All saved settings live here. */
object Cfg {
    private const val PREFS = "cfg"

    const val GAMES = "games"            // set of package names
    const val KEEP = "keepSet"           // apps never closed or blocked
    const val INPUT = "inputLag"         // keep the screen at its fastest rate
    const val UNCAP = "uncap"            // developer option: disable default frame rate for games
    const val ANIM = "anim"              // developer option: faster animations
    const val NET_BLOCK = "netBlock"     // block other apps' internet while playing
    const val PERF = "perf"
    const val SAVER_OFF = "saverOff"
    const val DND = "dnd"
    const val OVERLAY = "overlay"
    const val SCALE = "scale"            // screen resolution in percent while playing
    const val RAM_MODE = "ramMode"
    const val RAM_INTERVAL = "ramInterval"

    const val RAM_OFF = 0
    const val RAM_NORMAL = 1
    const val RAM_AGGRESSIVE = 2
    const val RAM_EXTREME = 3

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun games(p: SharedPreferences): Set<String> =
        p.getStringSet(GAMES, emptySet())?.toSet() ?: emptySet()

    fun keep(p: SharedPreferences): Set<String> =
        p.getStringSet(KEEP, emptySet())?.toSet() ?: emptySet()
}
