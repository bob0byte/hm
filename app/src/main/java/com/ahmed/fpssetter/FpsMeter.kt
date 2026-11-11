package com.ahmed.fpssetter

import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * Measures a game's real frame rate by asking the system compositor
 * (SurfaceFlinger) how many of the game's frames it received.
 *
 *  shown    = frames that reached the screen per second
 *  rendered = shown + frames the game drew that were thrown away because
 *             the screen had already moved on. When this is higher than
 *             "shown", the GPU is producing more frames than the panel can show.
 */
class FpsMeter {

    data class Reading(val shown: Int, val rendered: Int)

    private data class Layer(
        val name: String,
        var pkg: String = "",
        var total: Long = -1,
        var dropped: Long = 0
    )

    private val keyValue = Regex("^(\\w+)\\s*=\\s*(.*)$")

    private var lastName: String? = null
    private var lastTotal = 0L
    private var lastDropped = 0L
    private var lastTime = 0L

    fun start() {
        ShizukuShell.exec("dumpsys SurfaceFlinger --timestats -enable -clear")
        lastName = null
    }

    fun stop() {
        ShizukuShell.exec("dumpsys SurfaceFlinger --timestats -disable -clear")
        lastName = null
    }

    /** Blocking. Returns null until two samples exist or if the system gave nothing usable. */
    fun read(pkg: String): Reading? {
        val r = ShizukuShell.exec(
            "dumpsys SurfaceFlinger --timestats -dump -maxlayers 30 | " +
                "grep -E '^(layerName|packageName|totalFrames|droppedFrames)'"
        )
        if (r.exitCode == -1 || r.stdout.isEmpty()) return null
        val now = SystemClock.elapsedRealtime()

        val layers = mutableListOf<Layer>()
        var current: Layer? = null
        var globalTotal = -1L
        for (raw in r.stdout.lines()) {
            val m = keyValue.find(raw.trim()) ?: continue
            val value = m.groupValues[2].trim()
            when (m.groupValues[1]) {
                "layerName" -> current = Layer(value).also { layers += it }
                "packageName" -> current?.pkg = value
                "totalFrames" -> {
                    val n = value.toLongOrNull() ?: -1
                    val layer = current
                    if (layer == null) {
                        if (globalTotal < 0) globalTotal = n
                    } else if (layer.total < 0) {
                        layer.total = n
                    }
                }
                "droppedFrames" -> current?.dropped = value.toLongOrNull() ?: 0
            }
        }

        // Prefer the game's own surface; fall back to everything the screen composed.
        val mine = layers.filter { it.total >= 0 && (it.pkg == pkg || it.name.contains(pkg)) }
        val pick = mine.firstOrNull { it.name == lastName }
            ?: mine.maxByOrNull { it.total }
            ?: if (globalTotal >= 0) Layer("*screen*", total = globalTotal) else return null

        val sameLayer = pick.name == lastName
        val seconds = (now - lastTime) / 1000.0
        val shownDelta = pick.total - lastTotal
        val droppedDelta = pick.dropped - lastDropped

        lastName = pick.name
        lastTotal = pick.total
        lastDropped = pick.dropped
        lastTime = now

        if (!sameLayer || seconds <= 0.2 || shownDelta < 0 || droppedDelta < 0) return null
        val shown = (shownDelta / seconds).roundToInt()
        val rendered = ((shownDelta + droppedDelta) / seconds).roundToInt()
        return Reading(shown, rendered)
    }
}
