package com.ahmed.fpssetter

import android.content.SharedPreferences
import android.os.Build
import android.view.Display
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds the shell commands for every tweak. Each one has a plain-language
 * label so the app can report results without showing raw commands.
 * Nothing here needs root: every command works from the `shell` user,
 * which is what Shizuku provides.
 */
object Tweaks {

    data class Step(val label: String, val cmd: String)
    data class Done(val step: Step, val ok: Boolean, val detail: String)

    private val PKG = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    // Android 15+ caps games at a default frame rate (usually 60). Same switch as
    // Developer options > "Disable default frame rate for games".
    private const val GAME_CAP_PROP = "debug.graphics.game_default_frame_rate.disabled"

    // Tells the game service to re-read system properties right away.
    private const val POKE_GAME_SERVICE = "service call game 1599295570"

    fun validPackage(p: String) = PKG.matches(p)

    // ---- Screen ------------------------------------------------------------

    private fun sameResolutionModes(display: Display): List<Display.Mode> {
        val current = display.mode
        return display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth &&
                it.physicalHeight == current.physicalHeight
        }
    }

    /** Refresh rates the screen supports at the current resolution. */
    fun rates(display: Display): List<Int> =
        sameResolutionModes(display).map { it.refreshRate.roundToInt() }.distinct().sorted()

    /** The rate to use: the saved choice, or the highest one. */
    fun targetHz(display: Display, p: SharedPreferences): Int {
        val all = rates(display)
        if (all.isEmpty()) return 60
        val want = p.getInt(Cfg.HZ, 0)
        return if (want == 0) all.last() else all.minByOrNull { abs(it - want) } ?: all.last()
    }

    private fun screenOn(display: Display, p: SharedPreferences): List<Step> {
        val steps = mutableListOf<Step>()
        val hz = targetHz(display, p)
        val value = String.format(Locale.US, "%.1f", hz.toFloat())
        steps += Step("Screen set to $hz Hz", "settings put system peak_refresh_rate $value")

        if (p.getBoolean(Cfg.LOCK, true)) {
            steps += Step(
                "Refresh rate locked so it can't drop",
                "settings put system min_refresh_rate $value"
            )
            if (Build.VERSION.SDK_INT >= 33) {
                sameResolutionModes(display).minByOrNull { abs(it.refreshRate - hz) }?.let { m ->
                    steps += Step(
                        "Display mode forced",
                        "cmd display set-user-preferred-display-mode " +
                            "${m.physicalWidth} ${m.physicalHeight} ${m.refreshRate}"
                    )
                }
            }
        } else {
            steps += Step("Refresh rate left adaptive", "settings delete system min_refresh_rate")
        }

        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.US)
        when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> {
                steps += Step("Xiaomi refresh setting", "settings put system user_refresh_rate $hz")
                steps += Step("MIUI refresh setting", "settings put secure miui_refresh_rate $hz")
            }
            brand.contains("samsung") ->
                // 0 = standard (60), 2 = high (fixed at the panel's max)
                steps += Step(
                    "Samsung motion smoothness",
                    "settings put secure refresh_rate_mode ${if (hz > 60) 2 else 0}"
                )
        }
        return steps
    }

    private fun screenOff(): List<Step> {
        val steps = mutableListOf(
            Step("Screen refresh back to default", "settings delete system peak_refresh_rate"),
            Step("Refresh lock removed", "settings delete system min_refresh_rate")
        )
        if (Build.VERSION.SDK_INT >= 33) {
            steps += Step("Display mode released", "cmd display clear-user-preferred-display-mode")
        }
        return steps
    }

    // ---- System-wide -------------------------------------------------------

    private fun globalOn(p: SharedPreferences): List<Step> {
        val steps = mutableListOf<Step>()
        if (p.getBoolean(Cfg.UNCAP, true) && Build.VERSION.SDK_INT >= 35) {
            steps += Step(
                "Android's game FPS cap removed",
                "setprop $GAME_CAP_PROP true; $POKE_GAME_SERVICE"
            )
        }
        if (p.getBoolean(Cfg.SAVER_OFF, true)) {
            steps += Step("Battery saver off", "cmd power set-mode 0")
        }
        if (p.getBoolean(Cfg.DND, false)) {
            steps += Step("Do not disturb on", "cmd notification set_dnd priority")
        }
        return steps
    }

    private fun globalOff(p: SharedPreferences): List<Step> {
        val steps = mutableListOf<Step>()
        if (Build.VERSION.SDK_INT >= 35) {
            steps += Step(
                "Android's game FPS cap restored",
                "setprop $GAME_CAP_PROP false; $POKE_GAME_SERVICE"
            )
        }
        if (p.getBoolean(Cfg.DND, false)) {
            steps += Step("Do not disturb off", "cmd notification set_dnd off")
        }
        return steps
    }

    // ---- Per game ----------------------------------------------------------

    private fun gameOn(p: SharedPreferences, pkg: String): List<Step> {
        if (!validPackage(pkg) || Build.VERSION.SDK_INT < 33) return emptyList()
        val steps = mutableListOf<Step>()
        val scale = p.getInt(Cfg.SCALE, 100)
        if (p.getBoolean(Cfg.PERF, true) || scale < 100) {
            steps += Step("Performance mode on", "cmd game mode performance $pkg")
            val downscale =
                if (scale < 100) String.format(Locale.US, "%.2f", scale / 100f) else "disable"
            steps += Step(
                if (scale < 100) "FPS throttle lifted, rendering at $scale%"
                else "Per-game FPS throttle lifted",
                "cmd game set --mode performance --downscale $downscale --fps disable $pkg"
            )
        }
        return steps
    }

    private fun gameOff(pkg: String): List<Step> {
        if (!validPackage(pkg) || Build.VERSION.SDK_INT < 33) return emptyList()
        return listOf(
            Step("Game settings cleared", "cmd game reset $pkg"),
            Step("Game mode back to standard", "cmd game mode standard $pkg")
        )
    }

    // ---- Public ------------------------------------------------------------

    fun start(display: Display, p: SharedPreferences, games: Collection<String>): List<Step> =
        screenOn(display, p) + globalOn(p) + games.flatMap { gameOn(p, it) }

    fun stop(p: SharedPreferences, games: Collection<String>): List<Step> =
        screenOff() + globalOff(p) + games.flatMap { gameOff(it) }

    /** Blocking. Call from a background thread. */
    fun run(steps: List<Step>): List<Done> = steps.map { step ->
        val r = ShizukuShell.exec(step.cmd)
        val detail = listOf(r.stdout, r.stderr).filter { it.isNotEmpty() }.joinToString(" | ")
        Done(step, r.ok, detail)
    }
}
