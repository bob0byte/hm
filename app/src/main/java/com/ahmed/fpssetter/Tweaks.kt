package com.ahmed.fpssetter

import android.content.SharedPreferences
import android.os.Build
import android.view.Display
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Every change this app makes to the phone goes through here.
 *
 * Rule: before a system setting is changed, the value it had is saved.
 * Stopping the boost puts that exact value back. Nothing is reset to a guess.
 *
 * All functions that touch the phone are blocking: call them from a
 * background thread.
 */
object Tweaks {

    /** One thing the app tried, in plain words, and whether it worked. */
    data class Done(val label: String, val ok: Boolean, val detail: String = "")

    private val PKG = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    private val SIZE = Regex("(\\d+)x(\\d+)")
    private val SAFE_VALUE = Regex("^[A-Za-z0-9_.:,+-]+$")

    private const val SNAP = "snap|"                 // saved original settings
    private const val SNAP_SIZE = "snapSize"
    private const val SNAP_DENSITY = "snapDensity"
    private const val DID_UNCAP = "didUncap"
    private const val DID_DND = "didDnd"
    private const val DID_MODE = "didMode"

    // Developer options > "Disable default frame rate for games" (Android 15+)
    private const val GAME_CAP_PROP = "debug.graphics.game_default_frame_rate.disabled"
    // Asks the game service to notice the change right away. Best effort.
    private const val POKE = "(service call game 1599295570 >/dev/null 2>&1; true)"

    fun validPackage(p: String) = PKG.matches(p)

    private fun isSamsung() =
        (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.US).contains("samsung")

    // ---- Building blocks ---------------------------------------------------

    private fun sh(label: String, cmd: String): Done {
        val r = ShizukuShell.exec(cmd)
        return Done(label, r.ok, listOf(r.stdout, r.stderr).filter { it.isNotEmpty() }.joinToString(" | "))
    }

    /** Changes one system setting, saving what it was first. */
    private fun put(p: SharedPreferences, ns: String, key: String, value: String, label: String): Done {
        val snapKey = "$SNAP$ns|$key"
        if (!p.contains(snapKey)) {
            val old = ShizukuShell.exec("settings get $ns $key")
            if (old.exitCode == -1) return Done(label, false, old.stderr)
            p.edit().putString(snapKey, old.stdout.trim()).commit()
        }
        return sh(label, "settings put $ns $key $value")
    }

    /** Puts saved settings back. [only] limits it to keys containing that text. */
    private fun restore(p: SharedPreferences, only: String? = null): List<Done> {
        val done = mutableListOf<Done>()
        val edit = p.edit()
        for ((snapKey, saved) in p.all) {
            if (!snapKey.startsWith(SNAP) || saved !is String) continue
            if (only != null && !snapKey.contains(only)) continue
            val parts = snapKey.removePrefix(SNAP).split('|')
            if (parts.size != 2) continue
            val (ns, key) = parts
            done += if (saved != "null" && SAFE_VALUE.matches(saved)) {
                sh("Put back: $key", "settings put $ns $key $saved")
            } else {
                sh("Put back: $key", "settings delete $ns $key")   // it was not set before
            }
            edit.remove(snapKey)
        }
        edit.commit()
        return done
    }

    fun hasLeftovers(p: SharedPreferences): Boolean =
        p.contains(SNAP_SIZE) || p.contains(DID_UNCAP) || p.contains(DID_DND) ||
            p.contains(DID_MODE) || p.all.keys.any { it.startsWith(SNAP) }

    // ---- Screen rate -------------------------------------------------------

    private fun fastestMode(display: Display): Display.Mode? {
        val current = display.mode
        return display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate }
    }

    /** The fastest refresh rate the screen supports. */
    fun maxHz(display: Display): Int = fastestMode(display)?.refreshRate?.roundToInt() ?: 60

    /** Developer option "Force peak refresh rate": hold the screen at its fastest rate. */
    private fun holdScreen(display: Display, p: SharedPreferences): List<Done> {
        val hz = maxHz(display)
        val value = String.format(Locale.US, "%.1f", hz.toFloat())
        return listOf(
            put(p, "system", "peak_refresh_rate", value, "Screen set to $hz Hz"),
            put(p, "system", "min_refresh_rate", value, "Screen stopped from dropping below $hz Hz")
        )
    }

    /**
     * Called when the screen has fallen below its fastest rate while boosting.
     * Each stage is a stronger way of forcing it back. Returns null when a
     * stage doesn't apply to this phone, or when there are no stages left.
     */
    fun guard(display: Display, p: SharedPreferences, stage: Int): Done? {
        val hz = maxHz(display)
        return when (stage) {
            0 -> {
                // Something overwrote our settings or battery saver came on
                ShizukuShell.exec("cmd power set-mode 0")
                val results = holdScreen(display, p)
                Done("Screen rate re-applied", results.all { it.ok })
            }
            1 -> if (isSamsung())
                put(p, "secure", "refresh_rate_mode", "1", "Samsung motion smoothness: adaptive")
            else null
            2 -> if (isSamsung())
                put(p, "secure", "refresh_rate_mode", "2", "Samsung motion smoothness: always high")
            else null
            3 -> {
                val m = fastestMode(display)
                if (Build.VERSION.SDK_INT >= 33 && m != null) {
                    p.edit().putBoolean(DID_MODE, true).commit()
                    sh(
                        "Display mode forced to $hz Hz",
                        "cmd display set-user-preferred-display-mode " +
                            "${m.physicalWidth} ${m.physicalHeight} ${m.refreshRate}"
                    )
                } else null
            }
            else -> null
        }
    }

    const val GUARD_STAGES = 4

    /** Nothing could hold the fast rate: put every screen setting back as it was. */
    fun giveUpScreen(p: SharedPreferences): List<Done> {
        val done = restore(p, "refresh_rate").toMutableList()
        if (p.getBoolean(DID_MODE, false)) {
            done += sh("Display mode released", "cmd display clear-user-preferred-display-mode")
            p.edit().remove(DID_MODE).commit()
        }
        return done
    }

    // ---- Start and stop ----------------------------------------------------

    /** Everything that is switched on the moment boosting starts. */
    fun start(display: Display, p: SharedPreferences): List<Done> {
        val done = mutableListOf<Done>()

        if (p.getBoolean(Cfg.INPUT, true) && maxHz(display) > 60) {
            done += holdScreen(display, p)
        }
        if (p.getBoolean(Cfg.UNCAP, true) && Build.VERSION.SDK_INT >= 35) {
            p.edit().putBoolean(DID_UNCAP, true).commit()
            done += sh(
                "Default frame rate limit for games disabled",
                "setprop $GAME_CAP_PROP true && $POKE"
            )
        }
        if (p.getBoolean(Cfg.ANIM, false)) {
            done += put(p, "global", "window_animation_scale", "0.5", "Window animations faster")
            done += put(p, "global", "transition_animation_scale", "0.5", "Transition animations faster")
            done += put(p, "global", "animator_duration_scale", "0.5", "Animator speed faster")
        }
        if (p.getBoolean(Cfg.SAVER_OFF, true)) {
            done += sh("Battery saver off", "cmd power set-mode 0")
        }
        if (p.getBoolean(Cfg.DND, false)) {
            // Only switch it on (and later off) if it was off to begin with
            val zen = ShizukuShell.exec("settings get global zen_mode").stdout.trim()
            if (zen == "0") {
                p.edit().putBoolean(DID_DND, true).commit()
                done += sh("Do not disturb on", "cmd notification set_dnd priority")
            }
        }
        return done
    }

    /** Puts back everything [start], [guard] and [gameStart] changed. */
    fun stop(p: SharedPreferences): List<Done> {
        val done = mutableListOf<Done>()
        done += resolutionOff(p)
        done += restore(p)
        val edit = p.edit()
        if (p.getBoolean(DID_MODE, false)) {
            done += sh("Display mode released", "cmd display clear-user-preferred-display-mode")
            edit.remove(DID_MODE)
        }
        if (p.getBoolean(DID_UNCAP, false)) {
            done += sh("Default frame rate limit restored", "setprop $GAME_CAP_PROP false && $POKE")
            edit.remove(DID_UNCAP)
        }
        if (p.getBoolean(DID_DND, false)) {
            done += sh("Do not disturb off", "cmd notification set_dnd off")
            edit.remove(DID_DND)
        }
        edit.commit()
        return done
    }

    // ---- While a chosen game is open ---------------------------------------

    fun gameStart(p: SharedPreferences, pkg: String): List<Done> {
        if (!validPackage(pkg)) return emptyList()
        val done = mutableListOf<Done>()
        if (p.getBoolean(Cfg.PERF, true) && Build.VERSION.SDK_INT >= 33) {
            done += sh("Performance mode for the game", "cmd game mode performance $pkg")
        }
        done += resolutionOn(p)
        return done
    }

    fun gameStop(p: SharedPreferences, pkg: String): List<Done> {
        val done = resolutionOff(p).toMutableList()
        if (validPackage(pkg) && p.getBoolean(Cfg.PERF, true) && Build.VERSION.SDK_INT >= 33) {
            done += sh("Game mode back to standard", "cmd game mode standard $pkg")
        }
        return done
    }

    // ---- Resolution --------------------------------------------------------

    private fun resolutionOn(p: SharedPreferences): List<Done> {
        val scale = p.getInt(Cfg.SCALE, 100)
        if (scale >= 100 || p.contains(SNAP_SIZE)) return emptyList()

        val size = ShizukuShell.exec("wm size").stdout
        val density = ShizukuShell.exec("wm density").stdout
        val physicalSize = SIZE.find(size.substringAfter("Physical size", ""))
        val overrideSize = SIZE.find(size.substringAfter("Override size", ""))
        val physicalDensity = Regex("Physical density: (\\d+)").find(density)
        val overrideDensity = Regex("Override density: (\\d+)").find(density)
        val now = overrideSize ?: physicalSize
        val dpi = (overrideDensity ?: physicalDensity)?.groupValues?.get(1)?.toIntOrNull()
        if (now == null || dpi == null) {
            return listOf(Done("Lower screen resolution", false, "Couldn't read the screen size"))
        }

        val w = now.groupValues[1].toInt()
        val h = now.groupValues[2].toInt()
        // Keep both sides even. Shrinking the density by the same amount keeps
        // everything the same size on screen, just less sharp.
        val newW = (w * scale / 100) / 2 * 2
        val newH = (h * scale / 100) / 2 * 2
        val newDpi = dpi * scale / 100
        if (newW < 320 || newH < 320 || newDpi < 80) return emptyList()

        p.edit()
            .putString(SNAP_SIZE, overrideSize?.value ?: "reset")
            .putString(SNAP_DENSITY, overrideDensity?.groupValues?.get(1) ?: "reset")
            .commit()
        return listOf(
            sh("Screen resolution lowered to ${newW}x$newH", "wm size ${newW}x$newH"),
            sh("Screen scale matched", "wm density $newDpi")
        )
    }

    private fun resolutionOff(p: SharedPreferences): List<Done> {
        val size = p.getString(SNAP_SIZE, null) ?: return emptyList()
        val density = p.getString(SNAP_DENSITY, "reset") ?: "reset"
        val done = listOf(
            sh(
                "Screen resolution put back",
                if (SIZE.matches(size)) "wm size $size" else "wm size reset"
            ),
            sh(
                "Screen scale put back",
                if (density.toIntOrNull() != null) "wm density $density" else "wm density reset"
            )
        )
        p.edit().remove(SNAP_SIZE).remove(SNAP_DENSITY).commit()
        return done
    }
}
