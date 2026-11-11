package com.ahmed.fpssetter

import android.app.ActivityManager
import android.content.Context

/**
 * Frees memory through Shizuku.
 *
 * Normal pass:     `am kill-all` closes every app that is only cached in the
 *                  background. Nothing visible is lost.
 * Aggressive pass: also force-stops every running app you installed, except
 *                  the one on screen, your keyboard, your launcher, Shizuku,
 *                  this app, and anything on the keep list.
 */
object RamBooster {

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private val PKG = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    private val FOREGROUND = Regex("u\\d+ ([A-Za-z0-9_.]+)/")

    // One shell call that reports: app on screen, keyboard, launcher,
    // installed (non-system) apps, and running process names.
    private val PROBE = listOf(
        "echo \"FG:$(dumpsys activity activities | grep -m 1 -E 'topResumedActivity|mResumedActivity')\"",
        "echo \"IME:$(settings get secure default_input_method)\"",
        "echo \"HOME:$(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -n 1)\"",
        "pm list packages -3",
        "echo PS:",
        "ps -A -o NAME"
    ).joinToString("; ")

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
            var foreground: String? = null
            val protectedApps = mutableSetOf(ctx.packageName, SHIZUKU_PKG)
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
                        foreground = FOREGROUND.find(line)?.groupValues?.get(1)
                    line.startsWith("IME:") ->
                        protectedApps += line.removePrefix("IME:").substringBefore('/')
                    line.startsWith("HOME:") ->
                        protectedApps += line.removePrefix("HOME:").substringBefore('/')
                    line.startsWith("package:") ->
                        installed += line.removePrefix("package:")
                }
            }

            val onScreen = foreground
            if (onScreen == null) {
                // Without knowing what is on screen we could close the game itself.
                note = " (couldn't tell which app is on screen, so only cached apps were cleared)"
            } else {
                protectedApps += onScreen
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
