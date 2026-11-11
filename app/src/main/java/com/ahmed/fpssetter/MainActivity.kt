package com.ahmed.fpssetter

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
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
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import rikka.shizuku.Shizuku
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var display: Display

    private lateinit var shizukuStatus: TextView
    private lateinit var grantButton: Button
    private lateinit var statScreen: TextView
    private lateinit var statGame: TextView
    private lateinit var statGameLabel: TextView
    private lateinit var statRam: TextView
    private lateinit var startButton: Button
    private lateinit var boostStatus: TextView
    private lateinit var boostDetails: TextView
    private lateinit var gamesList: TextView
    private lateinit var chooseGames: Button
    private lateinit var inputSwitch: MaterialSwitch
    private lateinit var perfSwitch: MaterialSwitch
    private lateinit var saverSwitch: MaterialSwitch
    private lateinit var dndSwitch: MaterialSwitch
    private lateinit var overlaySwitch: MaterialSwitch
    private lateinit var scaleChips: ChipGroup
    private lateinit var uncapSwitch: MaterialSwitch
    private lateinit var animSwitch: MaterialSwitch
    private lateinit var netSwitch: MaterialSwitch
    private lateinit var keepSummary: TextView
    private lateinit var chooseKeep: Button
    private lateinit var ramChips: ChipGroup
    private lateinit var ramHint: TextView
    private lateinit var ramSliderLabel: TextView
    private lateinit var ramSlider: Slider
    private lateinit var boostNowButton: Button
    private lateinit var resultLine: TextView
    private lateinit var details: TextView

    /** Result of actually running a test command through Shizuku. */
    private var shizukuTest = ""
    private var testing = false

    // ---- Shizuku listeners -------------------------------------------------

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshShizukuState() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        shizukuTest = ""
        refreshShizukuState()
    }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizukuState() }

    // ---- Live numbers (updated once a second) ------------------------------

    private var frames = 0
    private var windowStart = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (windowStart == 0L) windowStart = frameTimeNanos
            frames++
            val elapsed = frameTimeNanos - windowStart
            if (elapsed >= 1_000_000_000L) {
                statScreen.text = (frames * 1_000_000_000.0 / elapsed).roundToInt().toString()
                frames = 0
                windowStart = frameTimeNanos
                refreshLive()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---- Lifecycle ---------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Cfg.prefs(this)
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        display = dm.getDisplay(Display.DEFAULT_DISPLAY)

        shizukuStatus = findViewById(R.id.shizukuStatus)
        grantButton = findViewById(R.id.grantButton)
        statScreen = findViewById(R.id.statScreen)
        statGame = findViewById(R.id.statGame)
        statGameLabel = findViewById(R.id.statGameLabel)
        statRam = findViewById(R.id.statRam)
        startButton = findViewById(R.id.startButton)
        boostStatus = findViewById(R.id.boostStatus)
        boostDetails = findViewById(R.id.boostDetails)
        gamesList = findViewById(R.id.gamesList)
        chooseGames = findViewById(R.id.chooseGames)
        inputSwitch = findViewById(R.id.inputSwitch)
        perfSwitch = findViewById(R.id.perfSwitch)
        saverSwitch = findViewById(R.id.saverSwitch)
        dndSwitch = findViewById(R.id.dndSwitch)
        overlaySwitch = findViewById(R.id.overlaySwitch)
        scaleChips = findViewById(R.id.scaleChips)
        uncapSwitch = findViewById(R.id.uncapSwitch)
        animSwitch = findViewById(R.id.animSwitch)
        netSwitch = findViewById(R.id.netSwitch)
        keepSummary = findViewById(R.id.keepSummary)
        chooseKeep = findViewById(R.id.chooseKeep)
        ramChips = findViewById(R.id.ramChips)
        ramHint = findViewById(R.id.ramHint)
        ramSliderLabel = findViewById(R.id.ramSliderLabel)
        ramSlider = findViewById(R.id.ramSlider)
        boostNowButton = findViewById(R.id.boostNowButton)
        resultLine = findViewById(R.id.resultLine)
        details = findViewById(R.id.details)

        setUpSettings()

        grantButton.setOnClickListener { requestShizuku() }
        startButton.setOnClickListener { onStartStop() }
        chooseGames.setOnClickListener { showGamePicker() }
        chooseKeep.setOnClickListener { showKeepPicker() }
        boostNowButton.setOnClickListener { onBoostNow() }
        boostStatus.setOnClickListener { toggle(boostDetails) }
        resultLine.setOnClickListener { toggle(details) }

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refreshShizukuState()
        refreshGamesList()
    }

    override fun onResume() {
        super.onResume()
        frames = 0
        windowStart = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
        refreshShizukuState()
        refreshLive()
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        io.shutdown()
    }

    private fun toggle(view: View) {
        view.visibility = if (view.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ---- Settings ----------------------------------------------------------

    /** Fills a chip group with choices and saves the picked value. */
    private fun fillChips(
        group: ChipGroup,
        choices: List<Pair<String, Int>>,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        group.removeAllViews()
        val values = mutableMapOf<Int, Int>()
        var checkedId = View.NO_ID
        var firstId = View.NO_ID
        for ((label, value) in choices) {
            val chip = layoutInflater.inflate(R.layout.chip_choice, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = label
            values[chip.id] = value
            group.addView(chip)
            if (firstId == View.NO_ID) firstId = chip.id
            if (value == selected) checkedId = chip.id
        }
        // Always have one ticked, even if the saved value is no longer offered
        group.check(if (checkedId != View.NO_ID) checkedId else firstId)
        group.setOnCheckedStateChangeListener { _, ids ->
            ids.firstOrNull()?.let { id -> values[id]?.let(onPick) }
        }
    }

    private fun bindSwitch(view: MaterialSwitch, key: String, default: Boolean) {
        view.isChecked = prefs.getBoolean(key, default)
        view.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    private fun setUpSettings() {
        val max = Tweaks.maxHz(display)
        inputSwitch.text = "Keep the screen at $max Hz"

        bindSwitch(inputSwitch, Cfg.INPUT, true)
        bindSwitch(perfSwitch, Cfg.PERF, true)
        bindSwitch(saverSwitch, Cfg.SAVER_OFF, true)
        bindSwitch(dndSwitch, Cfg.DND, false)
        bindSwitch(overlaySwitch, Cfg.OVERLAY, true)
        bindSwitch(uncapSwitch, Cfg.UNCAP, true)
        bindSwitch(animSwitch, Cfg.ANIM, false)
        bindSwitch(netSwitch, Cfg.NET_BLOCK, false)

        fillChips(
            scaleChips,
            listOf("Full" to 100, "85%" to 85, "70%" to 70, "50%" to 50),
            prefs.getInt(Cfg.SCALE, 100)
        ) { prefs.edit().putInt(Cfg.SCALE, it).apply() }

        fillChips(
            ramChips,
            listOf(
                "Off" to Cfg.RAM_OFF, "Normal" to Cfg.RAM_NORMAL,
                "Aggressive" to Cfg.RAM_AGGRESSIVE, "Extreme" to Cfg.RAM_EXTREME
            ),
            prefs.getInt(Cfg.RAM_MODE, Cfg.RAM_NORMAL)
        ) {
            prefs.edit().putInt(Cfg.RAM_MODE, it).apply()
            showRamHint(it)
        }
        showRamHint(prefs.getInt(Cfg.RAM_MODE, Cfg.RAM_NORMAL))

        val seconds = (prefs.getInt(Cfg.RAM_INTERVAL, 30).coerceIn(10, 120) / 5) * 5
        ramSlider.value = seconds.toFloat()
        ramSliderLabel.text = "Clear every $seconds seconds"
        ramSlider.addOnChangeListener { _, value, _ ->
            val s = value.roundToInt()
            ramSliderLabel.text = "Clear every $s seconds"
            prefs.edit().putInt(Cfg.RAM_INTERVAL, s).apply()
        }

        refreshKeepSummary()
    }

    private fun showRamHint(mode: Int) {
        ramHint.text = when (mode) {
            Cfg.RAM_OFF -> "The cleaner won't run."
            Cfg.RAM_NORMAL -> "Closes apps that are only parked in the background. Safe."
            Cfg.RAM_AGGRESSIVE -> "Also closes running apps you installed. " +
                "Closed apps go quiet until you open them again."
            else -> "Also closes apps that came with the phone, like the store, browser, " +
                "YouTube, Bixby and Facebook services. The phone, messages, clock, " +
                "keyboard, home screen, settings and Android's own parts are never closed."
        }
    }

    // ---- Games and protected apps ------------------------------------------

    private fun showGamePicker() {
        chooseGames.isEnabled = false
        io.execute {
            val apps = AppPicker.load(applicationContext, includeSystem = false)
            ui.post {
                chooseGames.isEnabled = true
                if (isFinishing || isDestroyed) return@post
                AppPicker.show(
                    this, "Choose your games", apps, Cfg.games(prefs), gamesFirst = true
                ) { picked ->
                    // Drop apps that were uninstalled since last time
                    val valid = picked.filter { p -> apps.any { it.pkg == p } }.toSet()
                    prefs.edit().putStringSet(Cfg.GAMES, valid).apply()
                    refreshGamesList()
                }
            }
        }
    }

    private fun showKeepPicker() {
        chooseKeep.isEnabled = false
        io.execute {
            val apps = AppPicker.load(applicationContext, includeSystem = true)
            ui.post {
                chooseKeep.isEnabled = true
                if (isFinishing || isDestroyed) return@post
                AppPicker.show(
                    this, "Apps to never close or block", apps, Cfg.keep(prefs), gamesFirst = false
                ) { picked ->
                    prefs.edit().putStringSet(Cfg.KEEP, picked).apply()
                    refreshKeepSummary()
                }
            }
        }
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }

    private fun refreshKeepSummary() {
        val keep = Cfg.keep(prefs)
        keepSummary.text =
            if (keep.isEmpty()) "None yet. Add apps like WhatsApp that must keep working."
            else keep.map { appName(it) }.sorted().joinToString(", ")
    }

    private fun refreshGamesList() {
        val games = Cfg.games(prefs)
        gamesList.text =
            if (games.isEmpty()) "None chosen. Boosting still works; games you add here get extra help while they're open."
            else games.map { appName(it) }.sorted().joinToString("\n") { "•  $it" }
    }

    // ---- Start / stop ------------------------------------------------------

    private fun onStartStop() {
        if (GameService.running) {
            stopService(Intent(this, GameService::class.java))
            GameService.running = false
            boostDetails.visibility = View.GONE
            refreshLive()
            toast("Boosting stopped. Your settings are being put back.")
            return
        }
        if (!ShizukuShell.ready()) {
            toast("Shizuku isn't ready yet")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, GameService::class.java))
        GameService.running = true
        refreshLive()
    }

    private fun refreshLive() {
        val (free, _) = RamBooster.memory(this)
        statRam.text = String.format(Locale.US, "%.1f", free / 1024.0)

        val on = GameService.running
        startButton.text = if (on) "Stop boosting" else "Start boosting"

        val shown = GameService.shownFps
        val rendered = GameService.renderedFps
        if (on && shown >= 0) {
            statGame.text = shown.toString()
            statGameLabel.text =
                if (rendered > shown + 2) "GAME FPS · GPU $rendered" else "GAME FPS"
        } else {
            statGame.text = "--"
            statGameLabel.text = "GAME FPS"
        }

        if (!on) {
            boostStatus.text = "Not running. Choose your settings below, then press Start boosting."
            return
        }
        val report = GameService.report
        boostStatus.text = when {
            GameService.status.isEmpty() -> "Starting…"
            report.isEmpty() -> GameService.status
            else -> GameService.status + "\nTap to see what's switched on"
        }
        boostDetails.text = buildString {
            if (GameService.verified.isNotEmpty()) append("✓ ${GameService.verified}\n")
            for (d in report.filter { it.ok }) append("✓ ${d.label}\n")
            val failed = report.filter { !it.ok }
            if (failed.isNotEmpty()) {
                append("\nNot available on this phone:\n")
                for (d in failed) append("–  ${d.label}\n")
            }
        }.trimEnd()
    }

    private fun onBoostNow() {
        if (!ShizukuShell.ready()) {
            toast("Shizuku isn't ready yet")
            return
        }
        val keep = Cfg.keep(prefs) + Cfg.games(prefs)
        // "Off" only switches the timer off; the button still does a normal clear
        val mode = prefs.getInt(Cfg.RAM_MODE, Cfg.RAM_NORMAL).coerceAtLeast(Cfg.RAM_NORMAL)
        boostNowButton.isEnabled = false
        io.execute {
            val outcome = RamBooster.boost(applicationContext, RamBooster.Config(mode, keep))
            ui.post {
                boostNowButton.isEnabled = true
                resultLine.text =
                    if (outcome.ok) "✓ ${outcome.message}   (tap for details)" else "Couldn't clear RAM   (tap for details)"
                resultLine.visibility = View.VISIBLE
                details.text =
                    if (!outcome.ok) outcome.message
                    else if (outcome.stopped.isEmpty()) "Cached background apps were cleared."
                    else "Closed:\n" + outcome.stopped.joinToString("\n") { "•  ${appName(it)}" }
                details.visibility = View.GONE
                refreshLive()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- Shizuku -----------------------------------------------------------

    private fun refreshShizukuState() {
        ui.post {
            val running = try { Shizuku.pingBinder() } catch (t: Throwable) { false }
            val ready = ShizukuShell.ready()
            shizukuStatus.text = when {
                !running -> "Shizuku isn't running. Open the Shizuku app and start it."
                !ready -> "Shizuku is running but hasn't given this app access yet."
                shizukuTest.isNotEmpty() -> shizukuTest
                else -> "Shizuku connected. Testing…"
            }
            val good = ready && !shizukuTest.startsWith("Shizuku is connected but")
            shizukuStatus.setTextColor(
                ContextCompat.getColor(this, if (good) R.color.accent else R.color.warn)
            )
            grantButton.visibility = if (running && !ready) View.VISIBLE else View.GONE
            if (ready && shizukuTest.isEmpty()) testShizuku()
        }
    }

    /** Runs a real command through Shizuku so "connected" is proven, not assumed. */
    private fun testShizuku() {
        if (testing) return
        testing = true
        io.execute {
            val r = ShizukuShell.exec("id -u")
            val text = when {
                r.ok && r.stdout.trim() == "0" -> "Shizuku connected and tested ✓ (root access)"
                r.ok && r.stdout.trim() == "2000" -> "Shizuku connected and tested ✓ (shell access)"
                else -> "Shizuku is connected but the test command failed: " +
                    r.stderr.ifEmpty { "no reply" }
            }
            ui.post {
                testing = false
                shizukuTest = text
                refreshShizukuState()
            }
        }
    }

    private fun requestShizuku() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("Access was denied before. Allow this app inside the Shizuku app.")
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
