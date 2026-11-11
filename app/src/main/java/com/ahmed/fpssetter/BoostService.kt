package com.ahmed.fpssetter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps clearing RAM on a timer while it is running.
 * Shows a permanent notification with a Stop button.
 */
class BoostService : Service() {

    private var worker: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

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
        schedule()
        return START_STICKY
    }

    override fun onDestroy() {
        worker?.shutdownNow()
        worker = null
        running = false
        super.onDestroy()
    }

    private fun schedule() {
        worker?.shutdownNow()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val interval = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
            .coerceIn(MIN_INTERVAL, MAX_INTERVAL).toLong()

        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleWithFixedDelay({
            try {
                onePass()
            } catch (t: Throwable) {
                // Never let one bad pass stop the timer
            }
        }, 0, interval, TimeUnit.SECONDS)
        worker = executor
    }

    private fun onePass() {
        if (!ShizukuShell.ready()) {
            update("Waiting for Shizuku. Start it to keep boosting.")
            return
        }
        // Read settings every pass so changes in the app apply right away
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keep = RamBooster.parseKeep(
            prefs.getString(KEY_KEEP, "").orEmpty() + "," + prefs.getString(KEY_GAME, "").orEmpty()
        )
        val outcome = RamBooster.boost(
            this, RamBooster.Config(prefs.getBoolean(KEY_AGGRESSIVE, false), keep)
        )
        passes++
        if (outcome.ok) totalFreedMb += outcome.freedMb
        val free = RamBooster.memory(this).first
        update(
            if (outcome.ok) "${outcome.message} · $free MB free now"
            else "Problem: ${outcome.message}"
        )
    }

    private fun update(text: String) {
        lastStatus = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "RAM booster", NotificationManager.IMPORTANCE_LOW
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
            this, 1, Intent(this, BoostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_boost)
            .setContentTitle("RAM booster is on")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ahmed.fpssetter.STOP_BOOST"

        const val PREFS = "boost"
        const val KEY_INTERVAL = "interval"
        const val KEY_AGGRESSIVE = "aggressive"
        const val KEY_KEEP = "keep"
        const val KEY_GAME = "game"

        const val DEFAULT_INTERVAL = 30
        const val MIN_INTERVAL = 10
        const val MAX_INTERVAL = 600

        private const val CHANNEL_ID = "boost"
        private const val NOTIFICATION_ID = 42

        @Volatile var running = false
        @Volatile var lastStatus = ""
        @Volatile var passes = 0
        @Volatile var totalFreedMb = 0L
    }
}
