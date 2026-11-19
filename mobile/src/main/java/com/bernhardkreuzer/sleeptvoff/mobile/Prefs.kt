package com.bernhardkreuzer.sleeptvoff.mobile

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Einstellungen und Protokoll, alles in SharedPreferences. */
object Prefs {
    private const val FILE = "sleeptvoff"
    private const val MAX_LOG_LINES = 40
    const val DEFAULT_DELAY_MIN = 5

    fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Einstellungen -----------------------------------------------------

    fun enabled(context: Context): Boolean = sp(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("enabled", value).apply()
    }

    fun delayMin(context: Context): Int = sp(context).getInt("delayMin", DEFAULT_DELAY_MIN)

    fun setDelayMin(context: Context, value: Int) {
        sp(context).edit().putInt("delayMin", value).apply()
    }

    fun tvIp(context: Context): String = sp(context).getString("tvIp", null) ?: ""

    /** Neue IP bedeutet neuer Fernseher: Kopplung verwerfen. */
    fun setTvIp(context: Context, value: String) {
        if (value == tvIp(context)) return
        sp(context).edit()
            .putString("tvIp", value)
            .putString("tvToken", "")
            .putBoolean("tvPaired", false)
            .apply()
    }

    fun tvToken(context: Context): String = sp(context).getString("tvToken", null) ?: ""

    fun tvPaired(context: Context): Boolean = sp(context).getBoolean("tvPaired", false)

    fun setPairing(context: Context, token: String?) {
        sp(context).edit()
            .putString("tvToken", token ?: "")
            .putBoolean("tvPaired", true)
            .apply()
    }

    // --- Laufzeitzustand ---------------------------------------------------

    /** Zeitpunkt des geplanten Ausschaltens, 0 = kein Timer aktiv. */
    fun pendingAt(context: Context): Long = sp(context).getLong("pendingAt", 0L)

    fun setPendingAt(context: Context, value: Long) {
        sp(context).edit().putLong("pendingAt", value).apply()
    }

    /** Einschlafzeitpunkt des zuletzt verarbeiteten Ereignisses (gegen Duplikate). */
    fun lastSleepStart(context: Context): Long = sp(context).getLong("lastSleepStart", 0L)

    fun setLastSleepStart(context: Context, value: Long) {
        sp(context).edit().putLong("lastSleepStart", value).apply()
    }

    // --- Protokoll ---------------------------------------------------------

    fun logText(context: Context): String = sp(context).getString("log", null) ?: ""

    @Synchronized
    fun log(context: Context, message: String) {
        val stamp = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date())
        val lines = (listOf("$stamp  $message") + logText(context).lines())
            .filter { it.isNotBlank() }
            .take(MAX_LOG_LINES)
        // commit statt apply: Der Prozess kann direkt nach einem Hintergrund-Ereignis enden.
        sp(context).edit().putString("log", lines.joinToString("\n")).commit()
    }

    fun clock(timeMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(timeMs))
}
