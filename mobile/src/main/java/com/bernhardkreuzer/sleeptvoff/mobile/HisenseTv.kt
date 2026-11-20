package com.bernhardkreuzer.sleeptvoff.mobile

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory

/**
 * Lokale Steuerung eines Hisense-Fernsehers mit VIDAA direkt im WLAN, ohne Cloud.
 *
 *  - Kopplung einmalig über eine PIN, die der Fernseher anzeigt.
 *  - Danach meldet sich die App mit einem Zugangstoken an (7 Tage gültig). Ist es
 *    abgelaufen, holt sie sich mit dem Erneuerungstoken (30 Tage) ein neues.
 *    Wird die App länger als 30 Tage nicht benutzt, muss neu gekoppelt werden.
 *
 * Alle Funktionen blockieren und dürfen nicht im UI-Thread laufen.
 */
object HisenseTv {

    data class Result(val ok: Boolean, val message: String)

    private const val DESCRIPTOR_PORT = 38400
    private const val TIMEOUT_MS = 3000

    @Volatile
    private var cachedFactory: SSLSocketFactory? = null

    /** Eine laufende Kopplung: Die Verbindung bleibt offen, bis die PIN eingegeben ist. */
    class PairingSession internal constructor(
        private val context: Context,
        private val client: VidaaClient
    ) {
        fun submitPin(pinText: String): Result {
            val pin = pinText.trim().toIntOrNull()
                ?: return Result(false, "Bitte die PIN vom Fernseher eingeben")
            return when (val outcome = client.submitPin(pin, 8000)) {
                is VidaaClient.PinResult.Paired -> {
                    Prefs.setTvSession(context, client.clientId, client.username, outcome.tokens)
                    Result(true, "Fernseher gekoppelt")
                }
                VidaaClient.PinResult.WrongPin -> Result(false, "PIN falsch, bitte neu koppeln")
                VidaaClient.PinResult.NoAnswer -> Result(false, "Keine Antwort vom Fernseher")
            }
        }

        fun close() = client.close()
    }

    /**
     * Verbindet sich und lässt den Fernseher die PIN anzeigen.
     * @return die offene Kopplung, oder null mit einer Fehlermeldung
     */
    fun beginPairing(context: Context, ip: String): Pair<PairingSession?, Result> {
        if (ip.isBlank()) return null to Result(false, "Keine IP-Adresse eingetragen")
        val app = context.applicationContext

        // Der Fernseher verrät selbst, welche Anmeldevariante er erwartet.
        val info = descriptor(ip)
        val uuids = listOfNotNull(info.mac, Prefs.clientUuid(app)).distinct()
        val methods = VidaaCredentials.order(VidaaCredentials.methodFor(info.protocol))

        var lastCode = 0
        for (uuid in uuids) {
            for (method in methods) {
                val creds = VidaaCredentials.generate(uuid, System.currentTimeMillis() / 1000, method)
                val attempt = try {
                    VidaaClient.connect(factory(app), ip, creds.clientId, creds.username, creds.password, TIMEOUT_MS)
                } catch (e: SSLException) {
                    return null to Result(false, "Verschlüsselte Verbindung abgelehnt: ${e.message}")
                } catch (e: Exception) {
                    return null to Result(
                        false,
                        "Fernseher unter $ip nicht erreichbar. Ist er eingeschaltet und im selben WLAN?"
                    )
                }
                when (attempt) {
                    is VidaaClient.Connect.Ok -> {
                        attempt.client.startPairing()
                        return PairingSession(app, attempt.client) to Result(true, "PIN wird angezeigt")
                    }
                    is VidaaClient.Connect.Refused -> lastCode = attempt.code
                }
            }
        }
        return null to Result(
            false,
            "Fernseher lehnt die Anmeldung ab (Code $lastCode). Stimmen Datum und Uhrzeit am Fernseher?"
        )
    }

    /**
     * Schaltet den Fernseher aus. KEY_POWER ist ein Umschalter, deshalb wird vorher
     * der Zustand abgefragt. Sonst würde ein ausgeschalteter Fernseher eingeschaltet.
     *
     * @param force true beim Knopf "Jetzt aus": auch senden, wenn der Zustand unbekannt ist
     */
    fun turnOff(context: Context, ip: String, force: Boolean): Result {
        if (ip.isBlank()) return Result(false, "Keine IP-Adresse eingetragen")
        val app = context.applicationContext
        val session = Prefs.tvSession(app) ?: return Result(false, "Fernseher ist nicht gekoppelt")

        val now = System.currentTimeMillis()
        val accessValid = now < session.accessExpiresAt
        val refreshValid = now < session.refreshExpiresAt
        if (!accessValid && !refreshValid) return Result(false, "Kopplung abgelaufen, bitte neu koppeln")

        val off = Result(true, "Fernseher nicht erreichbar, vermutlich schon aus")
        var usedRefresh = !accessValid
        var attempt = connect(app, ip, session, if (accessValid) session.accessToken else session.refreshToken)
            ?: return off
        if (attempt is VidaaClient.Connect.Refused && !usedRefresh && refreshValid) {
            usedRefresh = true
            attempt = connect(app, ip, session, session.refreshToken) ?: return off
        }
        val client = when (attempt) {
            is VidaaClient.Connect.Ok -> attempt.client
            is VidaaClient.Connect.Refused ->
                return Result(false, "Fernseher lehnt die Anmeldung ab (Code ${attempt.code}), bitte neu koppeln")
        }

        try {
            if (usedRefresh) {
                client.refresh(session.refreshToken, 3000)?.let {
                    Prefs.setTvSession(app, session.clientId, session.username, it)
                }
            }
            val state = client.state(2500)
            return when {
                state == VidaaClient.STATE_STANDBY -> Result(true, "Fernseher war schon aus")
                state == null && !force -> Result(false, "Zustand des Fernsehers unbekannt, nichts gesendet")
                else -> {
                    client.sendKey(VidaaClient.KEY_POWER)
                    // Dem Fernseher kurz Zeit geben, bevor die Verbindung schließt.
                    Thread.sleep(400)
                    Result(true, "Fernseher ausgeschaltet")
                }
            }
        } finally {
            client.close()
        }
    }

    /** @return null, wenn der Fernseher nicht erreichbar ist */
    private fun connect(
        context: Context,
        ip: String,
        session: Prefs.TvSession,
        password: String
    ): VidaaClient.Connect? {
        return try {
            VidaaClient.connect(factory(context), ip, session.clientId, session.username, password, TIMEOUT_MS)
        } catch (e: Exception) {
            null
        }
    }

    private fun factory(context: Context): SSLSocketFactory {
        cachedFactory?.let { return it }
        val resources = context.applicationContext.resources
        val certificate = resources.openRawResource(R.raw.vidaa_client_cert).bufferedReader().use { it.readText() }
        val privateKey = resources.openRawResource(R.raw.vidaa_client_key).bufferedReader().use { it.readText() }
        return VidaaClient.socketFactory(certificate, privateKey).also { cachedFactory = it }
    }

    private class Descriptor(val protocol: Int?, val mac: String?)

    /**
     * Liest die Gerätebeschreibung des Fernsehers. Darin stehen als Zeilen der
     * Form schlüssel=wert unter anderem die Protokollversion und die MAC-Adresse.
     */
    private fun descriptor(ip: String): Descriptor {
        return try {
            val connection = URL("http://$ip:$DESCRIPTOR_PORT/MediaServer/rendererdevicedesc.xml")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val text = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val protocol = Regex("transport_protocol\\s*[=:]\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
            val mac = listOf("mac", "macEthernet", "macWifi").firstNotNullOfOrNull { key ->
                Regex("(?<![A-Za-z])" + key + "\\s*=\\s*([0-9A-Fa-f:\\-]{12,17})")
                    .find(text)?.groupValues?.get(1)
            }
            Descriptor(protocol, mac)
        } catch (e: Exception) {
            Descriptor(null, null)
        }
    }
}
