package com.bernhardkreuzer.sleeptvoff.mobile

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Einstellungen und Protokoll, alles in SharedPreferences. */
object Prefs {
    private const val FILE = "sleeptvoff"
    private const val MAX_LOG_LINES = 40
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS
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
            .remove("tvClientId")
            .remove("tvUsername")
            .remove("tvAccessToken")
            .remove("tvRefreshToken")
            .remove("tvAccessExpiresAt")
            .remove("tvRefreshExpiresAt")
            .apply()
    }

    // --- Kopplung mit dem Fernseher ------------------------------------------

    /** Was die App braucht, um sich später ohne PIN wieder anzumelden. */
    class TvSession(
        val clientId: String,
        val username: String,
        val accessToken: String,
        val refreshToken: String,
        val accessExpiresAt: Long,
        val refreshExpiresAt: Long
    )

    fun tvSession(context: Context): TvSession? {
        val prefs = sp(context)
        val clientId = prefs.getString("tvClientId", null)
        val username = prefs.getString("tvUsername", null)
        val accessToken = prefs.getString("tvAccessToken", null)
        if (clientId.isNullOrEmpty() || username.isNullOrEmpty() || accessToken.isNullOrEmpty()) return null
        return TvSession(
            clientId,
            username,
            accessToken,
            prefs.getString("tvRefreshToken", null) ?: "",
            prefs.getLong("tvAccessExpiresAt", 0L),
            prefs.getLong("tvRefreshExpiresAt", 0L)
        )
    }

    fun setTvSession(context: Context, clientId: String, username: String, tokens: VidaaClient.Tokens) {
        val now = System.currentTimeMillis()
        // Eine Stunde Sicherheitsabstand, falls die Uhren leicht abweichen.
        sp(context).edit()
            .putString("tvClientId", clientId)
            .putString("tvUsername", username)
            .putString("tvAccessToken", tokens.accessToken)
            .putString("tvRefreshToken", tokens.refreshToken)
            .putLong("tvAccessExpiresAt", now + tokens.accessDays * DAY_MS - HOUR_MS)
            .putLong("tvRefreshExpiresAt", now + tokens.refreshDays * DAY_MS - HOUR_MS)
            .commit()
    }

    fun tvPaired(context: Context): Boolean = tvSession(context) != null

    /**
     * Eigene Geräte-ID im Format einer MAC-Adresse, einmal zufällig erzeugt.
     * Wird nur verwendet, wenn der Fernseher seine eigene MAC-Adresse nicht verrät.
     */
    fun clientUuid(context: Context): String {
        sp(context).getString("clientUuid", null)?.let { return it }
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        // Als "lokal vergebene" Adresse kennzeichnen, damit sie mit keinem echten Gerät kollidiert.
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        val uuid = bytes.joinToString(":") { "%02x".format(it) }
        sp(context).edit().putString("clientUuid", uuid).apply()
        return uuid
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
