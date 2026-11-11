package com.ahmed.fpssetter

import android.app.ActivityManager
import android.content.Context
import android.content.Intent

/**
 * Frees memory through Shizuku.
 *
 * Normal:     `am kill-all` closes every app that is only cached in the background.
 * Aggressive: also force-stops running apps you installed yourself.
 * Extreme:    also force-stops preinstalled apps that are safe to close: the
 *             ones with a home-screen icon (store, browser, YouTube, gallery…)
 *             and known background bloat (Bixby, Facebook services…).
 *
 * In every mode the phone's core is left alone: see [ALWAYS_KEEP], [isCore]
 * and the "SAFE" part of [PROBE].
 */
object RamBooster {

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private val PKG = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    private val FOREGROUND = Regex("u\\d+ ([A-Za-z0-9_.]+)/")

    // Never closed, whatever the settings say.
    private val ALWAYS_KEEP = setOf(
        SHIZUKU_PKG,
        "android",
        "com.google.android.gms",                 // Google Play services
        "com.google.android.gsf",
        "com.android.vending",                    // Play Store (licence checks, installs)
        "com.google.android.webview",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        // Alarms and reminders would be cancelled if these were closed
        "com.sec.android.app.clockpackage",
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.samsung.android.calendar",
        "com.google.android.calendar",
        // Samsung basics
        "com.sec.android.app.launcher",           // home screen
        "com.samsung.android.honeyboard",         // keyboard
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.messaging",
        "com.samsung.android.app.contacts",
        "com.samsung.android.lool",               // device care
        "com.samsung.android.app.watchmanager",   // watch connection
        "com.sec.android.app.camera"
    )

    // Parts of a package name that mark it as part of the phone's core.
    private val CORE_WORDS = listOf(
        "provider", "systemui", "inputmethod", "keyboard", "launcher", "telephony",
        ".ims", "bluetooth", ".nfc", "wifi", "networkstack", "permissioncontroller",
        "packageinstaller", "webview", "knox", "biometrics", "incallui", "dialer",
        "messaging", "contacts", "clock", "calendar", "settings", "shizuku",
        "overlay", "mainline", "telecom", "location", "security", "wallpaper",
        "accessibility", "print", "keychain", "certinstaller", "emergency"
    )

    // Preinstalled background services that are safe to close (no icon of their own).
    private val KNOWN_BLOAT = setOf(
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.wakeup",
        "com.samsung.android.bixbyvision.framework",
        "com.samsung.android.visionintelligence",
        "com.samsung.android.app.spage",          // Samsung Free
        "com.samsung.android.app.tips",
        "com.samsung.android.arzone",
        "com.samsung.android.aremoji",
        "com.samsung.android.smartsuggestions",
        "com.samsung.android.rubin.app",          // customization service
        "com.samsung.android.mdx",                // Link to Windows
        "com.samsung.android.scloud",
        "com.samsung.android.game.gamehome",
        "com.sec.android.app.samsungapps",        // Galaxy Store
        "com.facebook.services",
        "com.facebook.appmanager",
        "com.facebook.system",
        "com.microsoft.skydrive",
        "com.microsoft.appmanager"
    )

    private fun isCore(pkg: String): Boolean {
        if (pkg in ALWAYS_KEEP) return true
        // Everything under com.android is Android itself, except the Chrome browser
        if (pkg.startsWith("com.android.") && pkg != "com.android.chrome") return true
        return CORE_WORDS.any { pkg.contains(it) }
    }

    // One shell call that reports what is on screen, which apps the phone
    // depends on, installed apps, and running process names.
    private val PROBE = listOf(
        // Every app with a window on screen (covers split screen too)
        "dumpsys activity activities | grep -E 'ResumedActivity' | sed 's/^/FG:/'",
        // Apps the phone relies on: keyboard, launcher, dialer, SMS, accessibility,
        // notification access, VPN, assistant, autofill, device admin, live
        // wallpaper, and anything with an active media session (music, calls)
        "( settings get secure default_input_method" +
            "; cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -n 1" +
            "; telecom get-default-dialer" +
            "; settings get secure sms_default_application" +
            "; settings get secure enabled_accessibility_services" +
            "; settings get secure enabled_notification_listeners" +
            "; settings get secure always_on_vpn_app" +
            "; settings get secure assistant" +
            "; settings get secure voice_interaction_service" +
            "; settings get secure autofill_service" +
            "; dumpsys device_policy | grep -o 'ComponentInfo{[^}]*}'" +
            "; dumpsys wallpaper | grep -o 'mWallpaperComponent=ComponentInfo{[^}]*}'" +
            "; dumpsys media_session | grep -o 'package=[A-Za-z0-9_.]*'" +
            " ) 2>/dev/null | sed 's/^/SAFE:/'",
        "pm list packages -3 | sed 's/^package:/USER:/'",
        "pm list packages -s | sed 's/^package:/SYSTEM:/'",
        "echo PS:",
        "ps -A -o NAME"
    ).joinToString("; ")

    /** Pulls every package name out of a line like "a.b/.Svc:c.d/.Svc" or "package=a.b". */
    private fun packagesIn(line: String): List<String> =
        line.split(':', ',', '=', '{', '}', ' ', '\t')
            .map { it.substringBefore('/') }
            .filter { PKG.matches(it) }

    /** [mode] is one of the Cfg.RAM_ values. */
    data class Config(val mode: Int, val keep: Set<String>)

    data class Outcome(
        val ok: Boolean,
        val freedMb: Long,
        val stopped: List<String>,
        val message: String
    )

    /** Free and total RAM in MB. */
    fun memory(ctx: Context): Pair<Long, Long> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.availMem / 1048576L) to (info.totalMem / 1048576L)
    }

    /** Packages that have a home-screen icon. */
    private fun launchable(ctx: Context): Set<String> = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    } catch (t: Throwable) {
        emptySet()
    }

    /** Blocking. Call from a background thread. */
    fun boost(ctx: Context, cfg: Config): Outcome {
        val before = memory(ctx).first
        val commands = mutableListOf("am kill-all")
        val targets = mutableListOf<String>()
        var note = ""

        if (cfg.mode >= Cfg.RAM_AGGRESSIVE) {
            val probe = ShizukuShell.exec(PROBE)
            if (probe.exitCode == -1) {
                return Outcome(false, 0, emptyList(), probe.stderr.ifEmpty { "Shizuku command failed" })
            }
            val onScreen = mutableSetOf<String>()
            val protectedApps = mutableSetOf(ctx.packageName)
            protectedApps += ALWAYS_KEEP
            protectedApps += cfg.keep
            val userApps = mutableSetOf<String>()
            val systemApps = mutableSetOf<String>()
            val running = mutableSetOf<String>()
            var inProcessList = false

            for (raw in probe.stdout.lines()) {
                val line = raw.trim()
                when {
                    inProcessList -> running += line.substringBefore(':')
                    line == "PS:" -> inProcessList = true
                    line.startsWith("FG:") ->
                        FOREGROUND.find(line)?.groupValues?.get(1)?.let { onScreen += it }
                    line.startsWith("SAFE:") ->
                        protectedApps += packagesIn(line.removePrefix("SAFE:"))
                    line.startsWith("USER:") -> userApps += line.removePrefix("USER:")
                    line.startsWith("SYSTEM:") -> systemApps += line.removePrefix("SYSTEM:")
                }
            }

            if (onScreen.isEmpty()) {
                // Without knowing what is on screen we could close the game itself.
                note = " (couldn't tell which app is on screen, so only cached apps were cleared)"
            } else {
                protectedApps += onScreen
                val closable = userApps.toMutableSet()
                if (cfg.mode >= Cfg.RAM_EXTREME) {
                    // Preinstalled apps: only ones with an icon or known bloat,
                    // and never anything that looks like part of the phone's core.
                    val withIcon = launchable(ctx)
                    closable += systemApps.filter {
                        (it in withIcon || it in KNOWN_BLOAT) && !isCore(it)
                    }
                }
                targets += closable
                    .filter { it in running && it !in protectedApps && PKG.matches(it) }
                    .sorted()
                commands += targets.map { "am force-stop $it" }
            }
        }

        val run = ShizukuShell.exec(commands.joinToString("; "))
        if (run.exitCode == -1) {
            return Outcome(false, 0, emptyList(), run.stderr.ifEmpty { "Shizuku command failed" })
        }

        try {
            Thread.sleep(700)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val freed = (memory(ctx).first - before).coerceAtLeast(0)
        val summary = buildString {
            append("Freed $freed MB")
            if (targets.isNotEmpty()) append(" · closed ${targets.size} app(s)")
            append(note)
        }
        return Outcome(true, freed, targets, summary)
    }
}
