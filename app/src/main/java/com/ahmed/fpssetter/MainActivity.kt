package com.ahmed.fpssetter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
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
    private lateinit var autoMode: MaterialSwitch
    private lateinit var autoStatus: TextView
    private lateinit var gamesList: TextView
    private lateinit var chooseGames: Button
    private lateinit var rateChips: ChipGroup
    private lateinit var lockSwitch: MaterialSwitch
    private lateinit var uncapSwitch: MaterialSwitch
    private lateinit var perfSwitch: MaterialSwitch
    private lateinit var saverSwitch: MaterialSwitch
    private lateinit var dndSwitch: MaterialSwitch
    private lateinit var overlaySwitch: MaterialSwitch
    private lateinit var scaleChips: ChipGroup
    private lateinit var ramChips: ChipGroup
    private lateinit var ramHint: TextView
    private lateinit var ramSliderLabel: TextView
    private lateinit var ramSlider: Slider
    private lateinit var keepList: TextInputEditText
    private lateinit var boostNowButton: Button
    private lateinit var applyButton: Button
    private lateinit var undoButton: Button
    private lateinit var resultLine: TextView
    private lateinit var details: TextView

    /** True while the code (not the user) is flipping the auto mode switch. */
    private var settingSwitch = false

    private data class AppEntry(val pkg: String, val label: String, val isGame: Boolean)

    // ---- Shizuku listeners -------------------------------------------------

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshShizukuState() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshShizukuState() }
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
        autoMode = findViewById(R.id.autoMode)
        autoStatus = findViewById(R.id.autoStatus)
        gamesList = findViewById(R.id.gamesList)
        chooseGames = findViewById(R.id.chooseGames)
        rateChips = findViewById(R.id.rateChips)
        lockSwitch = findViewById(R.id.lockSwitch)
        uncapSwitch = findViewById(R.id.uncapSwitch)
        perfSwitch = findViewById(R.id.perfSwitch)
        saverSwitch = findViewById(R.id.saverSwitch)
        dndSwitch = findViewById(R.id.dndSwitch)
        overlaySwitch = findViewById(R.id.overlaySwitch)
        scaleChips = findViewById(R.id.scaleChips)
        ramChips = findViewById(R.id.ramChips)
        ramHint = findViewById(R.id.ramHint)
        ramSliderLabel = findViewById(R.id.ramSliderLabel)
        ramSlider = findViewById(R.id.ramSlider)
        keepList = findViewById(R.id.keepList)
        boostNowButton = findViewById(R.id.boostNowButton)
        applyButton = findViewById(R.id.applyButton)
        undoButton = findViewById(R.id.undoButton)
        resultLine = findViewById(R.id.resultLine)
        details = findViewById(R.id.details)

        setUpSettings()

        grantButton.setOnClickListener { requestShizuku() }
        chooseGames.setOnClickListener { showGamePicker() }
        boostNowButton.setOnClickListener { onBoostNow() }
        applyButton.setOnClickListener { runTweaks(apply = true) }
        undoButton.setOnClickListener { runTweaks(apply = false) }
        resultLine.setOnClickListener {
            details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        autoMode.setOnCheckedChangeListener { _, checked ->
            if (!settingSwitch) onAutoModeToggled(checked)
        }

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
        prefs.edit().putString(Cfg.KEEP, keepList.text?.toString().orEmpty()).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        io.shutdown()
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
        for ((label, value) in choices) {
            val chip = layoutInflater.inflate(R.layout.chip_choice, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = label
            values[chip.id] = value
            group.addView(chip)
            if (value == selected) group.check(chip.id)
        }
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
        // Refresh rate
        val rates = Tweaks.rates(display)
        val savedHz = prefs.getInt(Cfg.HZ, 0)
        val rateChoices = listOf("Max" to 0) + rates.map { "$it Hz" to it }
        fillChips(rateChips, rateChoices, if (savedHz in rates) savedHz else 0) {
            prefs.edit().putInt(Cfg.HZ, it).apply()
        }

        bindSwitch(lockSwitch, Cfg.LOCK, true)
        bindSwitch(uncapSwitch, Cfg.UNCAP, true)
        bindSwitch(perfSwitch, Cfg.PERF, true)
        bindSwitch(saverSwitch, Cfg.SAVER_OFF, true)
        bindSwitch(dndSwitch, Cfg.DND, false)
        bindSwitch(overlaySwitch, Cfg.OVERLAY, true)

        // Render resolution
        fillChips(
            scaleChips,
            listOf("Full" to 100, "85%" to 85, "70%" to 70, "50%" to 50),
            prefs.getInt(Cfg.SCALE, 100)
        ) { prefs.edit().putInt(Cfg.SCALE, it).apply() }

        // RAM cleaner
        fillChips(
            ramChips,
            listOf("Off" to Cfg.RAM_OFF, "Normal" to Cfg.RAM_NORMAL, "Aggressive" to Cfg.RAM_AGGRESSIVE),
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

        keepList.setText(prefs.getString(Cfg.KEEP, ""))
    }

    private fun showRamHint(mode: Int) {
        ramHint.text = when (mode) {
            Cfg.RAM_OFF -> "The cleaner won't run."
            Cfg.RAM_NORMAL -> "Closes apps that are only parked in the background. Safe."
            else -> "Also force-closes running apps you installed. System apps, your " +
                "keyboard, launcher, phone and SMS apps and anything playing audio are " +
                "never closed. Closed apps go quiet until you open them again."
        }
    }

    // ---- Games -------------------------------------------------------------

    private fun installedApps(): List<AppEntry> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { info ->
                @Suppress("DEPRECATION")
                val isGame = info.category == ApplicationInfo.CATEGORY_GAME ||
                    (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                AppEntry(info.packageName, packageManager.getApplicationLabel(info).toString(), isGame)
            }
            .sortedWith(compareByDescending<AppEntry> { it.isGame }.thenBy { it.label.lowercase(Locale.ROOT) })
    }

    private fun showGamePicker() {
        val apps = installedApps()
        if (apps.isEmpty()) {
            toast("No apps found")
            return
        }
        val saved = Cfg.games(prefs)
        // First time: tick the apps Android already knows are games
        val picked = (if (saved.isEmpty()) apps.filter { it.isGame }.map { it.pkg } else saved)
            .toMutableSet()
        val labels = apps.map { if (it.isGame) "${it.label}  ·  game" else it.label }.toTypedArray()
        val checked = apps.map { it.pkg in picked }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Choose your games")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                if (isChecked) picked += apps[index].pkg else picked -= apps[index].pkg
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ ->
                // Drop apps that were uninstalled since last time
                val valid = picked.filter { p -> apps.any { it.pkg == p } }.toSet()
                prefs.edit().putStringSet(Cfg.GAMES, valid).apply()
                refreshGamesList()
            }
            .show()
    }

    private fun refreshGamesList() {
        val games = Cfg.games(prefs)
        if (games.isEmpty()) {
            gamesList.text = "None chosen yet. Detected games are ticked for you when you open the list."
            return
        }
        val names = games.map { pkg ->
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (t: Throwable) {
                pkg
            }
        }.sorted()
        gamesList.text = names.joinToString("\n") { "•  $it" }
    }

    // ---- Auto game mode ----------------------------------------------------

    private fun onAutoModeToggled(on: Boolean) {
        if (!on) {
            stopService(Intent(this, GameService::class.java))
            GameService.running = false
            refreshLive()
            return
        }
        val problem = when {
            !ShizukuShell.ready() -> "Shizuku isn't ready yet"
            Cfg.games(prefs).isEmpty() -> "Choose at least one game first"
            else -> null
        }
        if (problem != null) {
            toast(problem)
            setAutoSwitch(false)
            return
        }
        // Android 13+ needs permission to show the "auto game mode is on" notification
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001
            )
        }
        ContextCompat.startForegroundService(this, Intent(this, GameService::class.java))
        GameService.running = true
        refreshLive()
    }

    private fun setAutoSwitch(on: Boolean) {
        if (autoMode.isChecked == on) return
        settingSwitch = true
        autoMode.isChecked = on
        settingSwitch = false
    }

    private fun refreshLive() {
        val (free, _) = RamBooster.memory(this)
        statRam.text = String.format(Locale.US, "%.1f", free / 1024.0)

        val on = GameService.running
        setAutoSwitch(on)

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

        autoStatus.text = when {
            !on -> "Off. Turn it on and the app boosts by itself whenever one of your " +
                "games opens, then puts everything back when you leave."
            GameService.status.isEmpty() -> "Starting…"
            else -> GameService.status +
                if (GameService.freedMb > 0) "\n${GameService.freedMb} MB of RAM cleared so far" else ""
        }
    }

    // ---- Manual buttons ----------------------------------------------------

    private fun runTweaks(apply: Boolean) {
        if (!ShizukuShell.ready()) {
            toast("Shizuku isn't ready yet")
            return
        }
        val games = Cfg.games(prefs)
        val steps = if (apply) Tweaks.start(display, prefs, games) else Tweaks.stop(prefs, games)
        applyButton.isEnabled = false
        undoButton.isEnabled = false
        io.execute {
            val done = Tweaks.run(steps)
            ui.post {
                applyButton.isEnabled = true
                undoButton.isEnabled = true
                val worked = done.count { it.ok }
                val headline = when {
                    worked == done.size && apply -> "✓ All ${done.size} tweaks are on"
                    worked == done.size -> "✓ Everything is back to normal"
                    else -> "$worked of ${done.size} worked on this phone"
                }
                showResult(
                    headline,
                    done.joinToString("\n") { d ->
                        (if (d.ok) "✓ " else "✗ ") + d.step.label +
                            if (!d.ok && d.detail.isNotEmpty()) "\n     ${d.detail}" else ""
                    }
                )
                if (apply && games.isEmpty()) {
                    toast("Screen tweaks applied. Choose games to boost them too.")
                }
            }
        }
    }

    private fun onBoostNow() {
        if (!ShizukuShell.ready()) {
            toast("Shizuku isn't ready yet")
            return
        }
        val keepText = keepList.text?.toString().orEmpty()
        prefs.edit().putString(Cfg.KEEP, keepText).apply()
        val keep = RamBooster.parseKeep(keepText) + Cfg.games(prefs)
        val aggressive = prefs.getInt(Cfg.RAM_MODE, Cfg.RAM_NORMAL) == Cfg.RAM_AGGRESSIVE
        boostNowButton.isEnabled = false
        io.execute {
            val outcome = RamBooster.boost(applicationContext, RamBooster.Config(aggressive, keep))
            ui.post {
                boostNowButton.isEnabled = true
                showResult(
                    if (outcome.ok) "✓ ${outcome.message}" else "Couldn't clear RAM",
                    if (!outcome.ok) outcome.message
                    else if (outcome.stopped.isEmpty()) "Cached background apps were cleared."
                    else "Closed:\n" + outcome.stopped.joinToString("\n") { "•  $it" }
                )
                refreshLive()
            }
        }
    }

    /** One friendly line; the full list stays hidden until it is tapped. */
    private fun showResult(headline: String, more: String) {
        resultLine.text = "$headline   (tap for details)"
        resultLine.visibility = View.VISIBLE
        details.text = more
        details.visibility = View.GONE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- Shizuku -----------------------------------------------------------

    private fun refreshShizukuState() {
        ui.post {
            val running = try { Shizuku.pingBinder() } catch (t: Throwable) { false }
            val ready = ShizukuShell.ready()
            shizukuStatus.text = when {
                !running -> "Shizuku isn't running. Open the Shizuku app and start it."
                ready -> "Shizuku connected ✓"
                else -> "Shizuku is running but hasn't given this app access yet."
            }
            shizukuStatus.setTextColor(
                ContextCompat.getColor(this, if (ready) R.color.accent else R.color.warn)
            )
            grantButton.visibility = if (running && !ready) View.VISIBLE else View.GONE
        }
    }

    private fun requestShizuku() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("Access was denied before. Allow FPS Setter inside the Shizuku app.")
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
