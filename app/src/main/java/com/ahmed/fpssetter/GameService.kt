package com.ahmed.fpssetter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Auto game mode.
 *
 * Checks every couple of seconds which app is on screen. When one of your
 * chosen games opens it applies your tweaks, starts clearing RAM and shows
 * the FPS counter. When you leave the game it puts everything back.
 */
class GameService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var worker: ScheduledExecutorService? = null
    private val meter = FpsMeter()

    private lateinit var prefs: SharedPreferences
    private lateinit var display: Display

    private var game: String? = null
    private var misses = 0
    private var lastBoost = 0L
    private var lastNotified = ""
    private var overlay: TextView? = null

    private val foreground = Regex("u\\d+ ([A-Za-z0-9_.]+)/")
    private val pingTime = Regex("time=([0-9.]+)")
    private var ticks = 0
    private var pingMs = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Cfg.prefs(this)
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        display = dm.getDisplay(Display.DEFAULT_DISPLAY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true

        if (worker == null) {
            val executor = Executors.newSingleThreadScheduledExecutor()
            executor.scheduleWithFixedDelay({
                try {
                    tick()
                } catch (t: Throwable) {
                    // Never let one bad pass stop the watcher
                }
            }, 0, TICK_SECONDS, TimeUnit.SECONDS)
            // If a past session ended badly, give apps their internet back
            executor.execute {
                try {
                    if (ShizukuShell.ready() && NetBlocker.hasLeftovers(prefs)) {
                        NetBlocker.unblock(prefs)
                    }
                } catch (t: Throwable) {
                }
            }
            worker = executor
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Put everything back before the service goes away
        val executor = worker
        worker = null
        executor?.execute {
            try {
                if (game != null) endSession()
            } catch (t: Throwable) {
            }
        }
        executor?.shutdown()
        running = false
        status = ""
        super.onDestroy()
    }

    // ---- The watcher -------------------------------------------------------

    private fun tick() {
        if (!ShizukuShell.ready()) {
            setStatus("Waiting for Shizuku. Start it to continue.")
            return
        }
        val games = Cfg.games(prefs)
        if (games.isEmpty()) {
            if (game != null) endSession()
            setStatus("Pick your games in the app first.")
            return
        }

        val onScreen = foregroundPackages() ?: return
        val found = onScreen.firstOrNull { it in games }

        when {
            found != null && found == game -> misses = 0
            found != null -> {
                if (game != null) endSession()
                beginSession(found)
            }
            game != null -> {
                // Give it one extra check so a pop-up doesn't end the session
                misses++
                if (misses >= 2) {
                    endSession()
                    setStatus("Watching for your games")
                }
            }
            else -> setStatus("Watching for your games")
        }

        game?.let { duringSession(it, games) }
    }

    /** Packages with a window on screen, or null if it couldn't be read. */
    private fun foregroundPackages(): Set<String>? {
        val r = ShizukuShell.exec("dumpsys activity activities | grep -E 'ResumedActivity'")
        if (r.exitCode == -1) return null
        val found = foreground.findAll(r.stdout).map { it.groupValues[1] }.toSet()
        return found.ifEmpty { null }
    }

    private fun beginSession(pkg: String) {
        game = pkg
        misses = 0
        lastBoost = SystemClock.elapsedRealtime()
        shownFps = -1
        renderedFps = -1
        gameLabel = labelOf(pkg)

        Tweaks.prepare(prefs)
        val done = Tweaks.run(Tweaks.start(display, prefs, listOf(pkg)))
        val worked = done.count { it.ok }
        lastSession = "${worked} of ${done.size} tweaks applied"

        if (prefs.getBoolean(Cfg.NET_BLOCK, false)) {
            val keep = RamBooster.parseKeep(prefs.getString(Cfg.KEEP, "").orEmpty()) +
                Cfg.games(prefs)
            val net = NetBlocker.block(this, prefs, keep)
            if (net.ok && net.count > 0) lastSession += " · ${net.count} apps offline"
        }
        ticks = 0
        pingMs = -1

        if (prefs.getBoolean(Cfg.OVERLAY, true)) {
            meter.start()
            showOverlay()
        }
        setStatus("Boosting $gameLabel · $lastSession")
    }

    private fun duringSession(pkg: String, games: Set<String>) {
        // RAM
        val mode = prefs.getInt(Cfg.RAM_MODE, Cfg.RAM_NORMAL)
        val interval = prefs.getInt(Cfg.RAM_INTERVAL, 30).coerceIn(10, 600) * 1000L
        val now = SystemClock.elapsedRealtime()
        if (mode != Cfg.RAM_OFF && now - lastBoost >= interval) {
            lastBoost = now
            val keep = RamBooster.parseKeep(prefs.getString(Cfg.KEEP, "").orEmpty()) + games
            val outcome = RamBooster.boost(
                this, RamBooster.Config(mode == Cfg.RAM_AGGRESSIVE, keep)
            )
            if (outcome.ok) freedMb += outcome.freedMb
        }

        // FPS
        if (prefs.getBoolean(Cfg.OVERLAY, true)) {
            if (overlay == null) {
                meter.start()
                showOverlay()
            }
            meter.read(pkg)?.let {
                shownFps = it.shown
                renderedFps = it.rendered
            }
            // Ping every few checks; it doesn't need to be as fresh as FPS
            if (ticks % 3 == 0) pingMs = measurePing()
            ticks++
            updateOverlay()
        } else if (overlay != null) {
            hideOverlay()
            meter.stop()
        }
    }

    private fun endSession() {
        val pkg = game ?: return
        game = null
        misses = 0
        Tweaks.run(Tweaks.stop(prefs, listOf(pkg)))
        if (NetBlocker.hasLeftovers(prefs)) NetBlocker.unblock(prefs)
        meter.stop()
        hideOverlay()
        shownFps = -1
        renderedFps = -1
        gameLabel = ""
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }

    // ---- In-game FPS counter -----------------------------------------------

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            // Shizuku can grant "display over other apps" without sending you to Settings
            ShizukuShell.exec("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        }
        main.post {
            if (overlay != null) return@post
            try {
                val density = resources.displayMetrics.density
                val view = TextView(this).apply {
                    setTextColor(Color.parseColor("#7CFFB2"))
                    textSize = 11f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    val pad = (6 * density).toInt()
                    setPadding(pad, pad / 2, pad, pad / 2)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#B0000000"))
                        cornerRadius = 8 * density
                    }
                    text = "… FPS"
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = (10 * density).toInt()
                lp.y = (30 * density).toInt()
                // Android blocks touches under overlays more opaque than this,
                // so staying at 0.8 keeps taps going through to the game.
                lp.alpha = 0.8f
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.addView(view, lp)
                overlay = view
            } catch (t: Throwable) {
                overlay = null
            }
        }
    }

    private fun updateOverlay() {
        val shown = shownFps
        val rendered = renderedFps
        val free = RamBooster.memory(this).first
        val text = buildString {
            append(if (shown >= 0) "$shown FPS" else "… FPS")
            // Only mention the GPU when it is clearly drawing more than the screen shows
            if (rendered > shown + 2) append("\nGPU $rendered")
            if (pingMs >= 0) append("\n$pingMs ms")
            append(String.format(Locale.US, "\nRAM %.1fG", free / 1024.0))
            val temp = batteryTemp()
            if (temp > 0) append("\n$temp°C")
        }
        main.post { overlay?.text = text }
    }

    /** Round trip to a nearby internet server in ms, or -1 if it couldn't be measured. */
    private fun measurePing(): Int {
        val r = ShizukuShell.exec("ping -c 1 -W 1 1.1.1.1")
        val ms = pingTime.find(r.stdout)?.groupValues?.get(1)?.toFloatOrNull() ?: return -1
        return ms.toInt()
    }

    private fun batteryTemp(): Int {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return tenths / 10
    }

    private fun hideOverlay() {
        main.post {
            val view = overlay ?: return@post
            overlay = null
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (t: Throwable) {
            }
        }
    }

    // ---- Notification ------------------------------------------------------

    private fun setStatus(text: String) {
        status = text
        if (text == lastNotified) return
        lastNotified = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Auto game mode", NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GameService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_boost)
            .setContentTitle("Auto game mode is on")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ahmed.fpssetter.STOP"
        private const val CHANNEL_ID = "auto"
        private const val NOTIFICATION_ID = 42
        private const val TICK_SECONDS = 2L

        // Read by the main screen
        @Volatile var running = false
        @Volatile var status = ""
        @Volatile var gameLabel = ""
        @Volatile var lastSession = ""
        @Volatile var shownFps = -1
        @Volatile var renderedFps = -1
        @Volatile var freedMb = 0L
    }
}
