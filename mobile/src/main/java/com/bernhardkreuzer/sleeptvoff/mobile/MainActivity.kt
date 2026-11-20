package com.bernhardkreuzer.sleeptvoff.mobile

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.wearable.Wearable
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var txtWatch: TextView
    private lateinit var heroActive: View
    private lateinit var heroIdle: View
    private lateinit var txtOffTime: TextView
    private lateinit var txtHeroSub: TextView
    private lateinit var progressTimer: ProgressBar
    private lateinit var txtHint: TextView
    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var txtDelay: TextView
    private lateinit var chips: List<TextView>
    private lateinit var txtPair: TextView
    private lateinit var editIp: EditText
    private lateinit var btnPair: Button
    private lateinit var btnTestOff: Button
    private lateinit var cardBattery: View
    private lateinit var txtLogEmpty: TextView
    private lateinit var logContainer: LinearLayout

    // Als Feld halten: SharedPreferences merkt sich Listener nur schwach.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refresh() }
    }

    /** Hält Restzeit und Fortschrittsbalken aktuell, solange die App sichtbar ist. */
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtWatch = findViewById(R.id.txtWatch)
        heroActive = findViewById(R.id.heroActive)
        heroIdle = findViewById(R.id.heroIdle)
        txtOffTime = findViewById(R.id.txtOffTime)
        txtHeroSub = findViewById(R.id.txtHeroSub)
        progressTimer = findViewById(R.id.progressTimer)
        txtHint = findViewById(R.id.txtHint)
        switchEnabled = findViewById(R.id.switchEnabled)
        txtDelay = findViewById(R.id.txtDelay)
        txtPair = findViewById(R.id.txtPair)
        editIp = findViewById(R.id.editIp)
        btnPair = findViewById(R.id.btnPair)
        btnTestOff = findViewById(R.id.btnTestOff)
        cardBattery = findViewById(R.id.cardBattery)
        txtLogEmpty = findViewById(R.id.txtLogEmpty)
        logContainer = findViewById(R.id.logContainer)
        chips = CHIP_IDS.map { findViewById<TextView>(it) }

        switchEnabled.isChecked = Prefs.enabled(this)
        editIp.setText(Prefs.tvIp(this))

        switchEnabled.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
            if (!checked) TimerScheduler.cancel(this)
            refresh()
        }
        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener { setDelay(CHIP_MINUTES[index]) }
        }
        findViewById<ImageButton>(R.id.btnMinus).setOnClickListener {
            setDelay(Prefs.delayMin(this) - DELAY_STEP)
        }
        findViewById<ImageButton>(R.id.btnPlus).setOnClickListener {
            setDelay(Prefs.delayMin(this) + DELAY_STEP)
        }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            if (TimerScheduler.cancel(this)) Prefs.log(this, "Timer von Hand abgebrochen")
            refresh()
        }
        btnPair.setOnClickListener { pair() }
        btnTestOff.setOnClickListener { testOff() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
    }

    override fun onResume() {
        super.onResume()
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        refresh()
        refreshWatch()
        ui.postDelayed(ticker, TICK_MS)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        saveIp()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** Die Wartezeit gilt ab dem nächsten Einschlafen, ein laufender Timer bleibt unverändert. */
    private fun setDelay(minutes: Int) {
        Prefs.setDelayMin(this, minutes.coerceIn(DELAY_MIN, DELAY_MAX))
        refresh()
    }

    private fun saveIp() {
        Prefs.setTvIp(this, editIp.text.toString().trim())
    }

    private fun pair() {
        saveIp()
        val ip = Prefs.tvIp(this)
        if (ip.isBlank()) {
            toast("Bitte zuerst die IP-Adresse des Fernsehers eintragen")
            return
        }
        btnPair.isEnabled = false
        val app = applicationContext
        io.execute {
            val (session, result) = HisenseTv.beginPairing(app, ip)
            runOnUiThread {
                if (session != null && !isFinishing) {
                    askPin(session)
                } else {
                    // Fehler oder App inzwischen geschlossen
                    if (session != null) io.execute { session.close() }
                    Prefs.log(app, "Kopplung: ${result.message}")
                    btnPair.isEnabled = true
                    toast(result.message)
                    refresh()
                }
            }
        }
    }

    /** Der Fernseher zeigt jetzt eine PIN an, die hier eingegeben wird. */
    private fun askPin(session: HisenseTv.PairingSession) {
        val view = layoutInflater.inflate(R.layout.dialog_pin, null)
        val editPin = view.findViewById<EditText>(R.id.editPin)
        AlertDialog.Builder(this)
            .setTitle("PIN vom Fernseher")
            .setMessage("Der Fernseher zeigt jetzt eine PIN an. Bitte hier eingeben.")
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("Koppeln") { _, _ ->
                finishPairing(session, editPin.text.toString())
            }
            .setNegativeButton("Abbrechen") { _, _ ->
                io.execute { session.close() }
                btnPair.isEnabled = true
            }
            .show()
    }

    private fun finishPairing(session: HisenseTv.PairingSession, pin: String) {
        val app = applicationContext
        io.execute {
            val result = try {
                session.submitPin(pin)
            } finally {
                session.close()
            }
            Prefs.log(app, "Kopplung: ${result.message}")
            runOnUiThread {
                btnPair.isEnabled = true
                toast(result.message)
                refresh()
            }
        }
    }

    private fun testOff() {
        saveIp()
        btnTestOff.isEnabled = false
        val app = applicationContext
        io.execute {
            val result = TvOff.execute(app, "Manueller Test", force = true)
            runOnUiThread {
                btnTestOff.isEnabled = true
                toast(result.message)
                refresh()
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val pendingAt = Prefs.pendingAt(this)
        val timerActive = pendingAt > now

        heroActive.visibility = if (timerActive) View.VISIBLE else View.GONE
        heroIdle.visibility = if (timerActive) View.GONE else View.VISIBLE
        if (timerActive) {
            val sleepStart = Prefs.lastSleepStart(this)
            val minutesLeft = ((pendingAt - now + 59_999L) / 60_000L).toInt()
            txtOffTime.text = Prefs.clock(pendingAt)
            txtHeroSub.text = "Eingeschlafen um ${Prefs.clock(sleepStart)} · noch $minutesLeft Min"
            val span = (pendingAt - sleepStart).coerceAtLeast(1L)
            val elapsed = (now - sleepStart).coerceIn(0L, span)
            progressTimer.progress = (elapsed * 100L / span).toInt()
        }

        val delay = Prefs.delayMin(this)
        txtDelay.text = delay.toString()
        chips.forEachIndexed { index, chip -> chip.isSelected = CHIP_MINUTES[index] == delay }

        val paired = Prefs.tvPaired(this)
        txtPair.text = if (paired) "Gekoppelt" else "Nicht gekoppelt"
        btnPair.text = if (paired) "Neu koppeln" else "Koppeln"

        val batteryOk = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        cardBattery.visibility = if (batteryOk) View.GONE else View.VISIBLE

        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        txtHint.visibility = if (exactOk) View.GONE else View.VISIBLE

        renderLog()
    }

    /** Protokollzeilen haben die Form "dd.MM. HH:mm:ss  Titel: Details". */
    private fun renderLog() {
        val lines = Prefs.logText(this).lines().filter { it.isNotBlank() }.take(MAX_LOG_ROWS)
        txtLogEmpty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        logContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        lines.forEachIndexed { index, line ->
            val stamp = line.substringBefore("  ")
            val message = line.substringAfter("  ", line)
            val details = message.substringAfter(": ", "")

            val row = inflater.inflate(R.layout.item_log, logContainer, false)
            val time = row.findViewById<TextView>(R.id.txtLogTime)
            val sub = row.findViewById<TextView>(R.id.txtLogSub)
            time.text = if (stamp.length >= 12) stamp.substring(7, 12) else ""
            if (index == 0) time.setTextColor(getColor(R.color.accent))
            row.findViewById<TextView>(R.id.txtLogTitle).text = message.substringBefore(": ")
            sub.text = details
            sub.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
            logContainer.addView(row)
        }
    }

    private fun refreshWatch() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                txtWatch.text = if (nodes.isEmpty()) "Keine Watch" else "Watch verbunden"
            }
            .addOnFailureListener {
                txtWatch.text = "Watch unbekannt"
            }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TICK_MS = 15_000L
        const val DELAY_STEP = 1
        const val DELAY_MIN = 1
        const val DELAY_MAX = 240
        const val MAX_LOG_ROWS = 8
        val CHIP_MINUTES = listOf(1, 2, 5, 10)
        val CHIP_IDS = listOf(R.id.chip1, R.id.chip2, R.id.chip5, R.id.chip10)
    }
}
