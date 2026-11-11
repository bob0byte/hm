package com.ahmed.fpssetter

import android.content.Context
import android.content.SharedPreferences

/**
 * Cuts other apps off from the internet (Wi-Fi and mobile data) while a game
 * is running, so downloads, updates and syncing can't compete with the game.
 *
 * Uses Android's built-in per-app firewall through Shizuku. Only apps you
 * installed yourself are blocked; system apps are never touched. The block is
 * lifted when the game closes, and Android clears it by itself on a reboot.
 */
object NetBlocker {

    private const val KEY_BLOCKED = "netBlocked"
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    data class Outcome(val ok: Boolean, val count: Int, val detail: String)

    fun hasLeftovers(p: SharedPreferences): Boolean =
        !p.getStringSet(KEY_BLOCKED, emptySet()).isNullOrEmpty()

    /** Blocking. [keep] are packages that must stay online (the game, your picks). */
    fun block(ctx: Context, p: SharedPreferences, keep: Set<String>): Outcome {
        val list = ShizukuShell.exec("pm list packages -3")
        if (list.exitCode == -1) return Outcome(false, 0, list.stderr)

        val skip = keep + ctx.packageName + SHIZUKU_PKG
        val targets = list.stdout.lines()
            .map { it.trim().removePrefix("package:") }
            .filter { Tweaks.validPackage(it) && it !in skip }
            .sorted()
        if (targets.isEmpty()) return Outcome(true, 0, "")

        // Remember them first, so they can always be unblocked later
        val all = (p.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()) + targets
        p.edit().putStringSet(KEY_BLOCKED, all).apply()

        val on = ShizukuShell.exec("cmd connectivity set-chain3-enabled true")
        if (!on.ok) {
            p.edit().remove(KEY_BLOCKED).apply()
            return Outcome(false, 0, listOf(on.stdout, on.stderr).joinToString(" ").trim())
        }
        for (chunk in targets.chunked(25)) {
            ShizukuShell.exec(chunk.joinToString("; ") {
                "cmd connectivity set-package-networking-enabled false $it"
            })
        }
        return Outcome(true, targets.size, "")
    }

    /** Blocking. Gives every blocked app its internet back. */
    fun unblock(p: SharedPreferences): Outcome {
        val blocked = p.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()
        for (chunk in blocked.filter { Tweaks.validPackage(it) }.chunked(25)) {
            ShizukuShell.exec(chunk.joinToString("; ") {
                "cmd connectivity set-package-networking-enabled true $it"
            })
        }
        val off = ShizukuShell.exec("cmd connectivity set-chain3-enabled false")
        if (off.exitCode != -1) p.edit().remove(KEY_BLOCKED).apply()
        return Outcome(off.exitCode != -1, blocked.size, off.stderr)
    }
}
