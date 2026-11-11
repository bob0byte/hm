package com.ahmed.fpssetter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var shizukuStatus: TextView
    private lateinit var grantButton: Button
    private lateinit var liveFps: TextView
    private lateinit var modeInfo: TextView
    private lateinit var rateChips: ChipGroup
    private lateinit var customFps: TextInputEditText
    private lateinit var lockMin: MaterialSwitch
    private lateinit var forceMode: MaterialSwitch
    private lateinit var vendorExtras: MaterialSwitch
    private lateinit var removeGameCap: MaterialSwitch
    private lateinit var gamePackage: TextInputEditText
    private lateinit var applyButton: Button
    private lateinit var resetButton: Button
    private lateinit var ramInfo: TextView
    private lateinit var boostStatus: TextView
    private lateinit var boostInterval: TextInputEditText
    private lateinit var aggressive: MaterialSwitch
    private lateinit var keepList: TextInputEditText
    private lateinit var boostNowButton: Button
    private lateinit var autoBoostButton: Button
    private lateinit var log: TextView

    private lateinit var display: Display
    private val chipRates = mutableMapOf<Int, Float>()

    // ---- Shizuku listeners -------------------------------------------------

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshShizukuState() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshShizukuState() }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizukuState() }

    // ---- Live FPS meter (also refreshes the RAM line once a second) --------

    private var frames = 0
    private var windowStart = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (windowStart == 0L) windowStart = frameTimeNanos
            frames++
            val elapsed = frameTimeNanos - windowStart
            if (elapsed >= 1_000_000_000L) {
                val fps = frames * 1_000_000_000.0 / elapsed
                liveFps.text = fps.roundToInt().toString()
                val m = display.mode
                modeInfo.text = "FPS now · mode ${m.physicalWidth}×${m.physicalHeight} @ " +
                    "${m.refreshRate.roundToInt()} Hz"
                frames = 0
                windowStart = frameTimeNanos
                refreshBoostState()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---- Lifecycle ---------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(BoostService.PREFS, Context.MODE_PRIVATE)

        shizukuStatus = findViewById(R.id.shizukuStatus)
        grantButton = findViewById(R.id.grantButton)
        liveFps = findViewById(R.id.liveFps)
        modeInfo = findViewById(R.id.modeInfo)
        rateChips = findViewById(R.id.rateChips)
        customFps = findViewById(R.id.customFps)
        lockMin = findViewById(R.id.lockMin)
        forceMode = findViewById(R.id.forceMode)
        vendorExtras = findViewById(R.id.vendorExtras)
        removeGameCap = findViewById(R.id.removeGameCap)
        gamePackage = findViewById(R.id.gamePackage)
        applyButton = findViewById(R.id.applyButton)
        resetButton = findViewById(R.id.resetButton)
        ramInfo = findViewById(R.id.ramInfo)
        boostStatus = findViewById(R.id.boostStatus)
        boostInterval = findViewById(R.id.boostInterval)
        aggressive = findViewById(R.id.aggressive)
        keepList = findViewById(R.id.keepList)
        boostNowButton = findViewById(R.id.boostNowButton)
        autoBoostButton = findViewById(R.id.autoBoostButton)
        log = findViewById(R.id.log)

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        display = dm.getDisplay(Display.DEFAULT_DISPLAY)

        buildRateChips()

        // Restore saved booster settings
        gamePackage.setText(prefs.getString(BoostService.KEY_GAME, ""))
        keepList.setText(prefs.getString(BoostService.KEY_KEEP, ""))
        aggressive.isChecked = prefs.getBoolean(BoostService.KEY_AGGRESSIVE, false)
        boostInterval.setText(
            prefs.getInt(BoostService.KEY_INTERVAL, BoostService.DEFAULT_INTERVAL).toString()
        )

        grantButton.setOnClickListener { requestShizuku() }
        applyButton.setOnClickListener { onApply() }
        resetButton.setOnClickListener { onReset() }
        boostNowButton.setOnClickListener { onBoostNow() }
        autoBoostButton.setOnClickListener { onToggleAutoBoost() }

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refreshShizukuState()
    }

    override fun onResume() {
        super.onResume()
        frames = 0
        windowStart = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
        refreshShizukuState()
        refreshBoostState()
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        saveBoostSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        io.shutdown()
    }

    // ---- Refresh rate ------------------------------------------------------

    private fun buildRateChips() {
        val current = display.mode
        val rates = display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .map { it.refreshRate }
            .distinctBy { it.roundToInt() }
            .sorted()

        rateChips.removeAllViews()
        chipRates.clear()
        for (rate in rates) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = "${rate.roundToInt()}"
                isCheckable = true
            }
            chipRates[chip.id] = rate
            rateChips.addView(chip)
        }
        rateChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) customFps.setText("")
        }
    }

    /** Custom field wins if filled in, otherwise the selected chip. */
    private fun chosenFps(): Float? {
        val typed = customFps.text?.toString()?.trim().orEmpty()
        if (typed.isNotEmpty()) return typed.toFloatOrNull()?.takeIf { it in 1f..1000f }
        return chipRates[rateChips.checkedChipId]
    }

    private fun onApply() {
        val fps = chosenFps()
        if (fps == null) {
            toast("Pick a rate or type one first")
            return
        }
        if (!ShizukuShell.ready()) {
            toast("Shizuku isn't ready yet")
            return
        }
        val supported = chipRates.values.any { it.roundToInt() == fps.roundToInt() }
        if (!supported) {
            appendLog("Note: ${fps.roundToInt()} isn't a rate this screen reports. " +
                "The system will use the nearest one it has.")
        }
        val pkg = gamePackage.text?.toString()?.trim().orEmpty()
        if (pkg.isNotEmpty() && RefreshRateController.cleanPackage(pkg) == null) {
            toast("That game package name doesn't look right")
            return
        }
        val opt = RefreshRateController.Options(
            lockMin = lockMin.isChecked,
            forceMode = forceMode.isChecked,
            vendorExtras = vendorExtras.isChecked,
            removeGameCap = removeGameCap.isChecked,
            gamePackage = pkg
        )
        saveBoostSettings()
        runAll(RefreshRateController.applyCommands(display, fps, opt))
    }

    private fun onReset() {
        if (!ShizukuShell.ready()) {
            toast("Shizuku isn't ready yet")
            return
        }
        rateChips.clearCheck()
        customFps.setText("")
        runAll(RefreshRateController.resetCommands(gamePackage.text?.toString().orEmpty()))
    }

    private fun runAll(commands: List<String>) {
        applyButton.isEnabled = false
        resetButton.isEnabled = false
        io.execute {
            val sb = StringBuilder()
            for (cmd in commands) {
                val r = ShizukuShell.exec(cmd)
                sb.append(if (r.ok) "✓ " else "✗ ").append(cmd).append('\n')
                val detail = listOf(r.stdout, r.stderr).filter { it.isNotEmpty() }
                    .joinToString(" | ")
                if (detail.isNotEmpty()) sb.append("   ").append(detail).append('\n')
            }
            ui.post {
                appendLog(sb.toString().trimEnd())
                applyButton.isEnabled = true
                resetButton.isEnabled = true
            }
        }
    }

    // ---- RAM booster -------------------------------------------------------

    private fun saveBoostSettings() {
        val seconds = (boostInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: BoostService.DEFAULT_INTERVAL)
            .coerceIn(BoostService.MIN_INTERVAL, BoostService.MAX_INTERVAL)
        prefs.edit()
            .putInt(BoostService.KEY_INTERVAL, seconds)
            .putBoolean(BoostService.KEY_AGGRESSIVE, aggressive.isChecked)
            .putString(BoostService.KEY_KEEP, keepList.text?.toString().orEmpty())
            .putString(BoostService.KEY_GAME, gamePackage.text?.toString()?.trim().orEmpty())
            .apply()
    }

    private fun onBoostNow() {
        if (!ShizukuShell.ready()) {
            toast("Shizuku isn't ready yet")
            return
        }
        saveBoostSettings()
        val keep = RamBooster.parseKeep(
            keepList.text?.toString().orEmpty() + "," + gamePackage.text?.toString().orEmpty()
        )
        val cfg = RamBooster.Config(aggressive.isChecked, keep)
        boostNowButton.isEnabled = false
        io.execute {
            val outcome = RamBooster.boost(applicationContext, cfg)
            ui.post {
                boostNowButton.isEnabled = true
                val line = if (outcome.ok) "✓ Boost: ${outcome.message}" else "✗ Boost: ${outcome.message}"
                appendLog(
                    if (outcome.stopped.isEmpty()) line
                    else line + "\n   closed: " + outcome.stopped.joinToString(", ")
                )
                refreshBoostState()
            }
        }
    }

    private fun onToggleAutoBoost() {
        if (BoostService.running) {
            stopService(Intent(this, BoostService::class.java))
            BoostService.running = false
            refreshBoostState()
            return
        }
        if (!ShizukuShell.ready()) {
            toast("Shizuku isn't ready yet")
            return
        }
        saveBoostSettings()
        // Android 13+ needs permission to show the "booster is on" notification
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001
            )
        }
        ContextCompat.startForegroundService(this, Intent(this, BoostService::class.java))
        BoostService.running = true
        refreshBoostState()
    }

    private fun refreshBoostState() {
        val (free, total) = RamBooster.memory(this)
        ramInfo.text = "RAM free: $free MB of $total MB"
        if (BoostService.running) {
            autoBoostButton.text = "Stop auto boost"
            boostStatus.text = "Auto boost is ON · ${BoostService.passes} passes · " +
                "${BoostService.totalFreedMb} MB freed in total\n${BoostService.lastStatus}"
        } else {
            autoBoostButton.text = "Start auto boost"
            boostStatus.text = "Auto boost is off"
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private fun appendLog(text: String) {
        log.text = if (log.text.isNullOrEmpty()) text else "${log.text}\n$text"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- Shizuku -----------------------------------------------------------

    private fun refreshShizukuState() {
        ui.post {
            val running = try { Shizuku.pingBinder() } catch (t: Throwable) { false }
            when {
                !running -> {
                    shizukuStatus.text = "Shizuku: not running. Open the Shizuku app and start it."
                    grantButton.isEnabled = false
                }
                Shizuku.isPreV11() -> {
                    shizukuStatus.text = "Shizuku: too old, please update it."
                    grantButton.isEnabled = false
                }
                ShizukuShell.ready() -> {
                    shizukuStatus.text = "Shizuku: ready ✓"
                    grantButton.isEnabled = false
                }
                else -> {
                    shizukuStatus.text = "Shizuku: running, permission needed."
                    grantButton.isEnabled = true
                }
            }
        }
    }

    private fun requestShizuku() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("Permission was denied before. Allow FPS Setter inside the Shizuku app.")
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (t: Throwable) {
            toast("Shizuku isn't running")
        }
    }

    companion object {
        private const val REQUEST_CODE = 1001
    }
}
