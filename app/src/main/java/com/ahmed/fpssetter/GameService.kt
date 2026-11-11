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
 * The boost itself. Runs from "Start boosting" until "Stop boosting".
 *
 * On start:    checks Shizuku really works, applies your settings and shows a
 *              confirmation notification.
 * While on:    clears RAM on a timer, keeps the screen at its fastest rate,
 *              and gives extra help (performance mode, lower resolution,
 *              Wi-Fi guard, FPS counter) while one of your games is open.
 * On stop:     puts every setting back the way it was.
 */
class GameService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var worker: ScheduledExecutorService? = null
    private val meter = FpsMeter()

    private lateinit var prefs: SharedPreferences
    private lateinit var display: Display

    private var started = false
    private var game: String? = null
    private var misses = 0
    private var lastBoost = 0L
    private var lastNotified = ""
    private var ticks = 0
    private var pingMs = -1

    // Keeping the screen fast
    private var guardStage = 0
    private var lowTicks = 0
    private var guardNote = ""

    // In-game counter
    private var overlay: TextView? = null
    private var overlayOn = false

    private val foreground = Regex("u\\d+ ([A-Za-z0-9_.]+)/")
    private val pingTime = Regex("time=([0-9.]+)")

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

        createChannels()
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
                    // Never let one bad pass stop the boost
                }
            }, 0, TICK_SECONDS, TimeUnit.SECONDS)
            worker = executor
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Put everything back before the service goes away
        val executor = worker
        worker = null
        val wasStarted = started
        executor?.execute {
            try {
                if (wasStarted) stopBoost()
            } catch (t: Throwable) {
            }
        }
        executor?.shutdown()
        running = false
        status = ""
        super.onDestroy()
    }

    // ---- Start / stop ------------------------------------------------------

    private fun startBoost() {
        // Prove Shizuku is really running our commands
        val id = ShizukuShell.exec("id -u")
        val who = when (id.stdout.trim()) {
            "0" -> "root"
            "2000" -> "shell"
            else -> ""
        }
        if (!id.ok || who.isEmpty()) {
            verified = ""
            setStatus("Shizuku didn't run the test command. Open Shizuku and check it is running.")
            return
        }
        verified = "Shizuku verified (commands run as $who)"

        // Let our notifications through without a pop-up asking
        if (Build.VERSION.SDK_INT >= 33) {
            ShizukuShell.exec("pm grant $packageName android.permission.POST_NOTIFICATIONS")
        }

        // Clean up after a session that ended badly
        if (NetBlocker.hasLeftovers(prefs)) NetBlocker.unblock(prefs)
        if (Tweaks.hasLeftovers(prefs)) Tweaks.stop(prefs)

        val done = Tweaks.start(display, prefs)
        report = done
        started = true
        guardStage = 0
        lowTicks = 0
        guardNote = ""
        lastBoost = 0L   // first RAM clear happens right away
        freedMb = 0

        val on = done.count { it.ok }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            CONFIRM_ID,
            NotificationCompat.Builder(this, CONFIRM_CHANNEL)
                .setSmallIcon(R.drawable.ic_boost)
                .setContentTitle("Boosting is running ✓")
                .setContentText("Connected to Shizuku · $on settings switched on")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Connected to Shizuku and verified.\n" +
                            done.filter { it.ok }.joinToString("\n") { "✓ ${it.label}" }
                    )
                )
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .setContentIntent(openApp())
                .build()
        )
        setStatus("Boosting · $on settings on")
    }

    private fun stopBoost() {
        if (game != null) endGame()
        if (NetBlocker.hasLeftovers(prefs)) NetBlocker.unblock(prefs)
        Tweaks.stop(prefs)
        started = false
        report = emptyList()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(CONFIRM_ID)
    }

    // ---- The loop ----------------------------------------------------------

    private fun tick() {
        if (!ShizukuShell.ready()) {
            setStatus("Waiting for Shizuku. Start it to continue.")
            return
        }
        if (!started) {
            startBoost()
            if (!started) return
        }
        // Nothing to do while the screen is off
        if (display.state != Display.STATE_ON) return

        guardScreenRate()
        watchForGames()
        clearRamIfDue()
        game?.let { duringGame(it) }
        ticks++

        setStatus(buildString {
            append(if (gameLabel.isNotEmpty()) "Boosting $gameLabel" else "Boosting")
            append(" · ${report.count { it.ok }} settings on")
            if (freedMb > 0) append(" · $freedMb MB cleared")
            if (guardNote.isNotEmpty()) append("\n$guardNote")
        })
    }

    /** If anything pushes the screen below its fastest rate, push it back. */
    private fun guardScreenRate() {
        if (!prefs.getBoolean(Cfg.INPUT, true) || guardStage > Tweaks.GUARD_STAGES) return
        val max = Tweaks.maxHz(display)
        if (max <= 60) return
        if (display.refreshRate >= max - 1f) {
            lowTicks = 0
            if (guardStage > 0) guardNote = "Screen held at $max Hz"
            return
        }
        // Ignore a single short dip
        if (++lowTicks < 2) return
        lowTicks = 0

        while (guardStage < Tweaks.GUARD_STAGES) {
            val done = Tweaks.guard(display, prefs, guardStage++)
            if (done != null) {
                guardNote = "Screen fell below $max Hz, forcing it back…"
                return
            }
        }
        // Every method was tried and the screen is still slow
        guardStage = Tweaks.GUARD_STAGES + 1
        Tweaks.giveUpScreen(prefs)
        guardNote = "Couldn't hold $max Hz: the phone or this app is forcing a lower rate."
    }

    private fun watchForGames() {
        val games = Cfg.games(prefs)
        if (games.isEmpty()) {
            if (game != null) endGame()
            return
        }
        val onScreen = foregroundPackages() ?: return
        val found = onScreen.firstOrNull { it in games }
        when {
            found != null && found == game -> misses = 0
            found != null -> {
                if (game != null) endGame()
                beginGame(found)
            }
            game != null -> {
                // One extra check so a pop-up doesn't end the game session
                if (++misses >= 2) endGame()
            }
        }
    }

    /** Packages with a window on screen, or null if it couldn't be read. */
    private fun foregroundPackages(): Set<String>? {
        val r = ShizukuShell.exec(
            "dumpsys activity activities | grep 'ResumedActivity' | grep -v 'Last'"
        )
        if (r.exitCode == -1) return null
        val found = foreground.findAll(r.stdout).map { it.groupValues[1] }.toSet()
        return found.ifEmpty { null }
    }

    private fun clearRamIfDue() {
        val mode = prefs.getInt(Cfg.RAM_MODE, Cfg.RAM_NORMAL)
        if (mode == Cfg.RAM_OFF) return
        val interval = prefs.getInt(Cfg.RAM_INTERVAL, 30).coerceIn(10, 600) * 1000L
        val now = SystemClock.elapsedRealtime()
        if (lastBoost != 0L && now - lastBoost < interval) return
        lastBoost = now
        val keep = Cfg.keep(prefs) + Cfg.games(prefs)
        val outcome = RamBooster.boost(this, RamBooster.Config(mode, keep))
        if (outcome.ok) freedMb += outcome.freedMb
    }

    // ---- While a chosen game is open ---------------------------------------

    private fun beginGame(pkg: String) {
        game = pkg
        misses = 0
        shownFps = -1
        renderedFps = -1
        pingMs = -1
        gameLabel = labelOf(pkg)

        Tweaks.gameStart(prefs, pkg)
        if (prefs.getBoolean(Cfg.NET_BLOCK, false)) {
            NetBlocker.block(this, prefs, Cfg.keep(prefs) + Cfg.games(prefs))
        }
        lastBoost = 0L   // clear RAM as the game opens
    }

    private fun duringGame(pkg: String) {
        val wanted = prefs.getBoolean(Cfg.OVERLAY, true)
        if (wanted && !overlayOn) {
            overlayOn = true
            meter.start()
            showOverlay()
        } else if (!wanted && overlayOn) {
            overlayOn = false
            hideOverlay()
            meter.stop()
        }
        if (!overlayOn) return

        meter.read(pkg)?.let {
            shownFps = it.shown
            renderedFps = it.rendered
        }
        // Ping every few checks; it doesn't need to be as fresh as FPS
        if (ticks % 3 == 0) pingMs = measurePing()
        updateOverlay()
    }

    private fun endGame() {
        val pkg = game ?: return
        game = null
        misses = 0
        Tweaks.gameStop(prefs, pkg)
        if (NetBlocker.hasLeftovers(prefs)) NetBlocker.unblock(prefs)
        if (overlayOn) {
            overlayOn = false
            meter.stop()
            hideOverlay()
        }
        shownFps = -1
        renderedFps = -1
        gameLabel = ""
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }

    // ---- In-game counter ---------------------------------------------------

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

    // ---- Notifications -----------------------------------------------------

    private fun setStatus(text: String) {
        status = text
        if (text == lastNotified) return
        lastNotified = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ongoing = NotificationChannel(
            CHANNEL_ID, "Boost status", NotificationManager.IMPORTANCE_LOW
        )
        ongoing.setShowBadge(false)
        nm.createNotificationChannel(ongoing)

        // Pops up once when boosting starts, without a sound
        val confirm = NotificationChannel(
            CONFIRM_CHANNEL, "Boost started", NotificationManager.IMPORTANCE_HIGH
        )
        confirm.setSound(null, null)
        confirm.enableVibration(false)
        nm.createNotificationChannel(confirm)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GameService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_boost)
            .setContentTitle("Boosting is on")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Stop boosting", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ahmed.fpssetter.STOP"
        private const val CHANNEL_ID = "status"
        private const val CONFIRM_CHANNEL = "started"
        private const val NOTIFICATION_ID = 42
        private const val CONFIRM_ID = 43
        private const val TICK_SECONDS = 2L

        // Read by the main screen
        @Volatile var running = false
        @Volatile var status = ""
        @Volatile var verified = ""
        @Volatile var gameLabel = ""
        @Volatile var report: List<Tweaks.Done> = emptyList()
        @Volatile var shownFps = -1
        @Volatile var renderedFps = -1
        @Volatile var freedMb = 0L
    }
}
