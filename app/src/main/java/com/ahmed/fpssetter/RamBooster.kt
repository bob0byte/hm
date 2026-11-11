package com.ahmed.fpssetter

import android.app.ActivityManager
import android.content.Context

/**
 * Frees memory through Shizuku.
 *
 * Normal pass:     `am kill-all` closes every app that is only cached in the
 *                  background. Nothing visible is lost.
 * Aggressive pass: also force-stops running apps you installed yourself.
 *                  System apps are never touched, and neither are the apps on
 *                  screen, the apps the phone relies on (see PROBE), Shizuku,
 *                  this app, or anything on the keep list.
 */
object RamBooster {

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private val PKG = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    private val FOREGROUND = Regex("u\\d+ ([A-Za-z0-9_.]+)/")

    // Apps that are never closed, whatever the settings say.
    private val ALWAYS_KEEP = setOf(
        SHIZUKU_PKG,
        "com.google.android.gms",          // Google Play services
        "com.google.android.gsf",
        "com.android.vending",             // Play Store
        "com.google.android.webview",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings"
    )

    // One shell call that reports what is on screen, which apps the phone
    // depends on, installed (non-system) apps, and running process names.
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
        "pm list packages -3",
        "echo PS:",
        "ps -A -o NAME"
    ).joinToString("; ")

    /** Pulls every package name out of a line like "a.b/.Svc:c.d/.Svc" or "package=a.b". */
    private fun packagesIn(line: String): List<String> =
        line.split(':', ',', '=', '{', '}', ' ', '\t')
            .map { it.substringBefore('/') }
            .filter { PKG.matches(it) }

    data class Config(val aggressive: Boolean, val keep: Set<String>)

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

    /** Turns "com.a, com.b" into a clean set of package names. */
    fun parseKeep(raw: String): Set<String> =
        raw.split(',', ' ', '\n', ';')
            .map { it.trim() }
            .filter { PKG.matches(it) }
            .toSet()

    /** Blocking. Call from a background thread. */
    fun boost(ctx: Context, cfg: Config): Outcome {
        val before = memory(ctx).first
        val commands = mutableListOf("am kill-all")
        val targets = mutableListOf<String>()
        var note = ""

        if (cfg.aggressive) {
            val probe = ShizukuShell.exec(PROBE)
            if (probe.exitCode == -1) {
                return Outcome(false, 0, emptyList(), probe.stderr.ifEmpty { "Shizuku command failed" })
            }
            val onScreen = mutableSetOf<String>()
            val protectedApps = mutableSetOf(ctx.packageName)
            protectedApps += ALWAYS_KEEP
            protectedApps += cfg.keep
            val installed = mutableSetOf<String>()
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
                    line.startsWith("package:") ->
                        installed += line.removePrefix("package:")
                }
            }

            if (onScreen.isEmpty()) {
                // Without knowing what is on screen we could close the game itself.
                note = " (couldn't tell which app is on screen, so only cached apps were cleared)"
            } else {
                protectedApps += onScreen
                // Only apps you installed yourself can ever be closed. System apps
                // are not in the "installed" list, so they are never touched.
                targets += installed
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
