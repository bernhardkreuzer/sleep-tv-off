package com.bernhardkreuzer.sleeptvoff.mobile

import java.security.MessageDigest

/**
 * Zeitabhängige Anmeldedaten für den MQTT-Dienst eines Hisense-Fernsehers mit VIDAA.
 *
 * Der Fernseher erwartet Client-ID, Benutzername und Passwort, die aus einer
 * Geräte-ID und der aktuellen Uhrzeit berechnet werden. Das Verfahren stammt
 * aus der Open-Source-Bibliothek vidaa-control (MIT-Lizenz,
 * github.com/tombabolewski/vidaa-control) und ist hier nach Kotlin übertragen.
 *
 * Reine Rechenlogik ohne Android-Abhängigkeiten (siehe VidaaCredentialsTest).
 */
object VidaaCredentials {

    /** Je nach Firmware-Stand erwartet der Fernseher eine von drei Varianten. */
    enum class AuthMethod { MODERN, MIDDLE, LEGACY }

    data class Credentials(val clientId: String, val username: String, val password: String)

    private const val D = "\$"
    private const val PATTERN = "38D65DC30F45109A369A86FCE866A85B"
    private const val SUFFIX_MODERN = "h!i@s#\$v%i^d&a*a"
    private const val SUFFIX_LEGACY = "h*i&s%e!r^v0i1c9"
    private const val TIME_XOR = 0x569814772b03a968L
    private const val BRAND = "his"
    private const val OPERATION = "vidaacommon"

    fun generate(uuid: String, timestampSec: Long, method: AuthMethod): Credentials {
        val id = normalizeUuid(uuid)
        val race = md5(PATTERN + D + id).take(6)
        val clientId = id + D + BRAND + D + race + "_" + OPERATION + "_001"

        val userTime = if (method == AuthMethod.LEGACY) timestampSec else timestampSec xor TIME_XOR
        val username = BRAND + D + userTime

        val suffix = if (method == AuthMethod.MODERN) SUFFIX_MODERN else SUFFIX_LEGACY
        val remainder = timestampSec.toString().sumOf { it - '0' } % 10
        val valueHash = md5(BRAND + remainder + suffix).take(6)
        val password = md5(timestampSec.toString() + D + valueHash)

        return Credentials(clientId, username, password)
    }

    /** Die Variante hängt von der Protokollversion ab, die der Fernseher selbst meldet. */
    fun methodFor(protocolVersion: Int?): AuthMethod = when {
        protocolVersion == null -> AuthMethod.MODERN
        protocolVersion >= 3290 -> AuthMethod.MODERN
        protocolVersion >= 3000 -> AuthMethod.MIDDLE
        else -> AuthMethod.LEGACY
    }

    /** Reihenfolge zum Durchprobieren: zuerst die wahrscheinlichste, dann der Rest. */
    fun order(first: AuthMethod): List<AuthMethod> =
        listOf(first) + AuthMethod.values().filter { it != first }

    /** Zwölf Hex-Zeichen ohne Trenner werden zu AA:BB:CC:DD:EE:FF, Groß-/Kleinschreibung bleibt. */
    fun normalizeUuid(uuid: String): String {
        if (uuid.length == 12 && ':' !in uuid && '-' !in uuid) {
            return uuid.chunked(2).joinToString(":")
        }
        return uuid
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
}
