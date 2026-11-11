package com.ahmed.fpssetter

import android.content.SharedPreferences
import android.os.Build
import android.view.Display
import java.util.Locale
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

    /** The fastest refresh rate the screen supports at the current resolution. */
    fun maxHz(display: Display): Int =
        sameResolutionModes(display).maxOfOrNull { it.refreshRate.roundToInt() } ?: 60

    private fun isSamsung() =
        (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.US).contains("samsung")

    /**
     * Input lag reducer, screen part: hold the screen at its fastest rate so
     * every tap shows up on the very next frame instead of waiting for a slow one.
     */
    private fun screenOn(display: Display, p: SharedPreferences): List<Step> {
        if (!p.getBoolean(Cfg.INPUT, true)) return emptyList()
        val steps = mutableListOf<Step>()
        val hz = maxHz(display)
        val value = String.format(Locale.US, "%.1f", hz.toFloat())
        steps += Step("Screen held at $hz Hz", "settings put system peak_refresh_rate $value")
        steps += Step(
            "Screen stopped from dropping to a slower rate",
            "settings put system min_refresh_rate $value"
        )
        if (Build.VERSION.SDK_INT >= 33) {
            sameResolutionModes(display).maxByOrNull { it.refreshRate }?.let { m ->
                steps += Step(
                    "Fastest display mode forced",
                    "cmd display set-user-preferred-display-mode " +
                        "${m.physicalWidth} ${m.physicalHeight} ${m.refreshRate}"
                )
            }
        }

        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.US)
        when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> {
                steps += Step("Xiaomi refresh setting", "settings put system user_refresh_rate $hz")
                steps += Step("MIUI refresh setting", "settings put secure miui_refresh_rate $hz")
            }
            isSamsung() && hz > 60 ->
                // Samsung: 0 = standard (60), 1 = adaptive, 2 = always at the panel's max
                steps += Step(
                    "Samsung motion smoothness fixed at $hz Hz",
                    "settings put secure refresh_rate_mode 2"
                )
        }
        return steps
    }

    private fun screenOff(p: SharedPreferences): List<Step> {
        val steps = mutableListOf(
            Step("Screen refresh back to default", "settings delete system peak_refresh_rate"),
            Step("Refresh lock removed", "settings delete system min_refresh_rate")
        )
        if (Build.VERSION.SDK_INT >= 33) {
            steps += Step("Display mode released", "cmd display clear-user-preferred-display-mode")
        }
        val samsungMode = p.getString(Cfg.SAMSUNG_MODE, null)
        if (isSamsung() && samsungMode != null && samsungMode.toIntOrNull() != null) {
            steps += Step(
                "Samsung motion smoothness put back",
                "settings put secure refresh_rate_mode $samsungMode"
            )
        }
        return steps
    }

    // ---- System-wide -------------------------------------------------------

    private fun globalOn(p: SharedPreferences): List<Step> {
        val steps = mutableListOf<Step>()
        if (p.getBoolean(Cfg.INPUT, true) && Build.VERSION.SDK_INT >= 35) {
            steps += Step(
                "Android's frame limit for games lifted",
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
                "Android's frame limit for games restored",
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
        screenOff(p) + globalOff(p) + games.flatMap { gameOff(it) }

    /**
     * Blocking. Call before [start]. On Samsung, remembers the current motion
     * smoothness setting so [stop] can put it back.
     */
    fun prepare(p: SharedPreferences) {
        if (!isSamsung() || !p.getBoolean(Cfg.INPUT, true)) return
        val now = ShizukuShell.exec("settings get secure refresh_rate_mode").stdout.trim()
        // "2" is what this app sets, so only remember a value that isn't ours
        if (now.toIntOrNull() != null && (now != "2" || p.getString(Cfg.SAMSUNG_MODE, null) == null)) {
            p.edit().putString(Cfg.SAMSUNG_MODE, now).apply()
        }
    }

    /** Blocking. Call from a background thread. */
    fun run(steps: List<Step>): List<Done> = steps.map { step ->
        val r = ShizukuShell.exec(step.cmd)
        val detail = listOf(r.stdout, r.stderr).filter { it.isNotEmpty() }.joinToString(" | ")
        Done(step, r.ok, detail)
    }
}
