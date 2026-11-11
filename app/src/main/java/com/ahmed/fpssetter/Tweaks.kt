package com.ahmed.fpssetter

import android.content.SharedPreferences
import android.os.Build
import android.view.Display
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds the shell commands for every tweak. Each one has a plain-language
 * label so the app can report results without showing raw commands.
 *
 * Rule: before changing a system setting, [prepare] saves the value it had.
 * [stop] puts that exact value back. Nothing is ever reset to a guess.
 */
object Tweaks {

    data class Step(val label: String, val cmd: String)
    data class Done(val step: Step, val ok: Boolean, val detail: String)

    private val PKG = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    private val SIZE = Regex("(\\d+)x(\\d+)")
    private val NUMBER = Regex("^[0-9.]+$")

    // Saved originals
    private const val SNAP_PEAK = "snapPeak"
    private const val SNAP_MIN = "snapMin"
    private const val SNAP_SIZE = "snapSize"
    private const val SNAP_DENSITY = "snapDensity"
    private const val NEW_SIZE = "newSize"
    private const val NEW_DENSITY = "newDensity"
    private const val SCREEN_HOLD_BAD = "screenHoldBad"

    // Android 15+ limits games to a default frame rate. Same switch as
    // Developer options > "Disable default frame rate for games".
    private const val GAME_CAP_PROP = "debug.graphics.game_default_frame_rate.disabled"
    private const val POKE_GAME_SERVICE = "service call game 1599295570"

    fun validPackage(p: String) = PKG.matches(p)

    /** The fastest refresh rate the screen supports at the current resolution. */
    fun maxHz(display: Display): Int {
        val current = display.mode
        return display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxOfOrNull { it.refreshRate.roundToInt() } ?: 60
    }

    // ---- Saving originals --------------------------------------------------

    fun hasLeftovers(p: SharedPreferences): Boolean =
        p.contains(SNAP_PEAK) || p.contains(SNAP_MIN) || p.contains(SNAP_SIZE)

    /**
     * Blocking. Call right before [start]. Saves the settings about to be
     * changed (only if nothing is saved yet, so the true originals are kept).
     */
    fun prepare(p: SharedPreferences, inGame: Boolean) {
        val edit = p.edit()

        val holdAllowed = p.getBoolean(Cfg.INPUT, true) && !p.getBoolean(SCREEN_HOLD_BAD, false)
        if (holdAllowed && !p.contains(SNAP_PEAK)) {
            edit.putString(
                SNAP_PEAK, ShizukuShell.exec("settings get system peak_refresh_rate").stdout.trim()
            )
            edit.putString(
                SNAP_MIN, ShizukuShell.exec("settings get system min_refresh_rate").stdout.trim()
            )
        }

        val scale = p.getInt(Cfg.SCALE, 100)
        if (inGame && scale < 100 && !p.contains(SNAP_SIZE)) {
            val size = ShizukuShell.exec("wm size").stdout
            val density = ShizukuShell.exec("wm density").stdout
            val physicalSize = SIZE.find(size.substringAfter("Physical size", ""))
            val overrideSize = SIZE.find(size.substringAfter("Override size", ""))
            val physicalDensity = Regex("Physical density: (\\d+)").find(density)
            val overrideDensity = Regex("Override density: (\\d+)").find(density)
            val now = overrideSize ?: physicalSize
            val dpi = (overrideDensity ?: physicalDensity)?.groupValues?.get(1)?.toIntOrNull()
            if (now != null && dpi != null) {
                val w = now.groupValues[1].toInt()
                val h = now.groupValues[2].toInt()
                // Keep both sides even; shrinking the density by the same amount
                // keeps everything the same size on screen, just less sharp.
                val newW = (w * scale / 100) / 2 * 2
                val newH = (h * scale / 100) / 2 * 2
                edit.putString(SNAP_SIZE, overrideSize?.value ?: "reset")
                edit.putString(SNAP_DENSITY, overrideDensity?.groupValues?.get(1) ?: "reset")
                edit.putString(NEW_SIZE, "${newW}x$newH")
                edit.putInt(NEW_DENSITY, dpi * scale / 100)
            }
        }
        edit.commit()
    }

    /** Call after running [stop]. */
    fun forgetOriginals(p: SharedPreferences) {
        p.edit().remove(SNAP_PEAK).remove(SNAP_MIN).remove(SNAP_SIZE)
            .remove(SNAP_DENSITY).remove(NEW_SIZE).remove(NEW_DENSITY).apply()
    }

    // ---- Screen ------------------------------------------------------------

    /**
     * Input lag reducer, screen part: hold the screen at its fastest rate so
     * every tap shows up on the very next frame instead of waiting for a slow one.
     */
    private fun screenOn(display: Display, p: SharedPreferences): List<Step> {
        if (!p.getBoolean(Cfg.INPUT, true) || !p.contains(SNAP_PEAK)) return emptyList()
        val hz = maxHz(display)
        val value = String.format(Locale.US, "%.1f", hz.toFloat())
        return listOf(
            Step("Screen held at $hz Hz", "settings put system peak_refresh_rate $value"),
            Step(
                "Screen stopped from dropping to a slower rate",
                "settings put system min_refresh_rate $value"
            )
        )
    }

    private fun restoreSetting(label: String, key: String, saved: String?): Step? = when {
        saved == null -> null
        NUMBER.matches(saved) -> Step(label, "settings put system $key $saved")
        else -> Step(label, "settings delete system $key")   // it was not set before
    }

    /** Puts the refresh rate settings back exactly as they were. */
    fun screenOff(p: SharedPreferences): List<Step> = listOfNotNull(
        restoreSetting("Screen rate setting put back", "peak_refresh_rate", p.getString(SNAP_PEAK, null)),
        restoreSetting("Screen rate lock removed", "min_refresh_rate", p.getString(SNAP_MIN, null))
    )

    /**
     * Call after undoing the screen hold because it made the screen slower.
     * The app then stops trying it on this phone.
     */
    fun markScreenHoldBad(p: SharedPreferences) {
        p.edit().remove(SNAP_PEAK).remove(SNAP_MIN).putBoolean(SCREEN_HOLD_BAD, true).apply()
    }

    fun screenHoldBad(p: SharedPreferences) = p.getBoolean(SCREEN_HOLD_BAD, false)

    // ---- Resolution --------------------------------------------------------

    private fun resolutionOn(p: SharedPreferences): List<Step> {
        val size = p.getString(NEW_SIZE, null) ?: return emptyList()
        val density = p.getInt(NEW_DENSITY, 0)
        if (!SIZE.matches(size) || density < 80) return emptyList()
        return listOf(
            Step("Screen resolution lowered to $size", "wm size $size"),
            Step("Screen scale matched", "wm density $density")
        )
    }

    private fun resolutionOff(p: SharedPreferences): List<Step> {
        val size = p.getString(SNAP_SIZE, null) ?: return emptyList()
        val density = p.getString(SNAP_DENSITY, "reset") ?: "reset"
        return listOf(
            Step(
                "Screen resolution put back",
                if (SIZE.matches(size)) "wm size $size" else "wm size reset"
            ),
            Step(
                "Screen scale put back",
                if (density.toIntOrNull() != null) "wm density $density" else "wm density reset"
            )
        )
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
        if (!p.getBoolean(Cfg.PERF, true)) return emptyList()
        return listOf(
            Step("Performance mode on", "cmd game mode performance $pkg"),
            Step(
                "Per-game slowdown lifted",
                "cmd game set --mode performance --fps disable $pkg"
            )
        )
    }

    private fun gameOff(pkg: String): List<Step> {
        if (!validPackage(pkg) || Build.VERSION.SDK_INT < 33) return emptyList()
        return listOf(
            Step("Game settings cleared", "cmd game reset $pkg"),
            Step("Game mode back to standard", "cmd game mode standard $pkg")
        )
    }

    // ---- Public ------------------------------------------------------------

    /** [inGame] is true only when a game just opened; the resolution change needs that. */
    fun start(
        display: Display,
        p: SharedPreferences,
        games: Collection<String>,
        inGame: Boolean
    ): List<Step> =
        screenOn(display, p) +
            (if (inGame) resolutionOn(p) else emptyList()) +
            globalOn(p) +
            games.flatMap { gameOn(p, it) }

    fun stop(p: SharedPreferences, games: Collection<String>): List<Step> =
        resolutionOff(p) + screenOff(p) + globalOff(p) + games.flatMap { gameOff(it) }

    /** Blocking. Call from a background thread. */
    fun run(steps: List<Step>): List<Done> = steps.map { step ->
        val r = ShizukuShell.exec(step.cmd)
        val detail = listOf(r.stdout, r.stderr).filter { it.isNotEmpty() }.joinToString(" | ")
        Done(step, r.ok, detail)
    }
}
