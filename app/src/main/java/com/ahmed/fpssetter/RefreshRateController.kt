package com.ahmed.fpssetter

import android.os.Build
import android.view.Display
import java.util.Locale
import kotlin.math.abs

/**
 * Builds the shell commands that change the screen refresh rate.
 * Nothing here needs root: every command works from the `shell` user,
 * which is what Shizuku provides.
 */
object RefreshRateController {

    data class Options(
        val lockMin: Boolean,
        val forceMode: Boolean,
        val vendorExtras: Boolean,
        val removeGameCap: Boolean,
        val gamePackage: String
    )

    // Android 15+ caps games at a default frame rate (usually 60). This is the
    // same switch as Developer options > "Disable default frame rate for games".
    private const val GAME_CAP_PROP = "debug.graphics.game_default_frame_rate.disabled"

    // Tells the game service to re-read system properties right away.
    private const val POKE_GAME_SERVICE = "service call game 1599295570"

    /** Only letters, digits, dots and underscores, so it is safe in a shell command. */
    fun cleanPackage(raw: String): String? {
        val p = raw.trim()
        return if (Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$").matches(p)) p else null
    }

    private fun fmt(fps: Float) = String.format(Locale.US, "%.1f", fps)

    /** The supported display mode closest to [fps] at the current resolution. */
    fun closestMode(display: Display, fps: Float): Display.Mode? {
        val current = display.mode
        return display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .minByOrNull { abs(it.refreshRate - fps) }
    }

    fun applyCommands(display: Display, fps: Float, opt: Options): List<String> {
        val cmds = mutableListOf<String>()
        val value = fmt(fps)

        // Standard Android keys (Pixel, most AOSP-based phones, and many others)
        cmds += "settings put system peak_refresh_rate $value"
        cmds += if (opt.lockMin) {
            "settings put system min_refresh_rate $value"
        } else {
            "settings delete system min_refresh_rate"
        }

        if (opt.vendorExtras) {
            val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.US)
            val whole = Math.round(fps)
            when {
                brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> {
                    cmds += "settings put system user_refresh_rate $whole"
                    cmds += "settings put secure miui_refresh_rate $whole"
                }
                brand.contains("samsung") -> {
                    // 0 = standard (60), 2 = high (fixed at the panel's max)
                    cmds += "settings put secure refresh_rate_mode ${if (whole > 60) 2 else 0}"
                }
            }
        }

        if (opt.forceMode) {
            if (Build.VERSION.SDK_INT >= 33) {
                closestMode(display, fps)?.let { m ->
                    cmds += "cmd display set-user-preferred-display-mode " +
                        "${m.physicalWidth} ${m.physicalHeight} ${m.refreshRate}"
                }
            }
        } else if (Build.VERSION.SDK_INT >= 33) {
            cmds += "cmd display clear-user-preferred-display-mode"
        }

        // ---- System caps on apps and games ----
        if (Build.VERSION.SDK_INT >= 35) {
            cmds += "setprop $GAME_CAP_PROP ${opt.removeGameCap}"
            cmds += POKE_GAME_SERVICE
        }
        cleanPackage(opt.gamePackage)?.let { pkg ->
            if (Build.VERSION.SDK_INT >= 33) {
                // Put the game in performance mode and lift its system FPS throttle
                cmds += "cmd game mode performance $pkg"
                cmds += "cmd game set --mode performance --fps disable $pkg"
            }
        }
        return cmds
    }

    fun resetCommands(gamePackage: String): List<String> {
        val cmds = mutableListOf(
            "settings delete system peak_refresh_rate",
            "settings delete system min_refresh_rate"
        )
        if (Build.VERSION.SDK_INT >= 33) {
            cmds += "cmd display clear-user-preferred-display-mode"
        }
        if (Build.VERSION.SDK_INT >= 35) {
            cmds += "setprop $GAME_CAP_PROP false"
            cmds += POKE_GAME_SERVICE
        }
        cleanPackage(gamePackage)?.let { pkg ->
            if (Build.VERSION.SDK_INT >= 33) {
                cmds += "cmd game reset $pkg"
                cmds += "cmd game mode standard $pkg"
            }
        }
        return cmds
    }
}
