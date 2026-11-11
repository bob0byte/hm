package com.ahmed.fpssetter

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * Runs shell commands with Shizuku's privileges (the same as `adb shell`).
 *
 * Shizuku.newProcess is private in API 13, so it is reached by reflection.
 * It is the simplest way to run a few short commands; if a future Shizuku
 * release removes it, replace this file with a Shizuku UserService.
 */
object ShizukuShell {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok get() = exitCode == 0
    }

    /** True when Shizuku is running and this app has been granted access. */
    fun ready(): Boolean = try {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** Blocking. Call from a background thread. Exit code -1 means it could not run. */
    fun exec(command: String): Result {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(
                null, arrayOf("sh", "-c", command), null, null
            ) as ShizukuRemoteProcess

            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            process.destroy()
            Result(code, out.trim(), err.trim())
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Result(-1, "", "${cause.javaClass.simpleName}: ${cause.message}")
        }
    }
}
