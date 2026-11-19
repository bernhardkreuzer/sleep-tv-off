package com.bernhardkreuzer.sleeptvoff.mobile

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Lokale Steuerung eines Samsung-Fernsehers (Tizen, ca. ab Baujahr 2016)
 * direkt im WLAN, ohne Cloud und ohne ablaufende Zugangsdaten:
 *
 *  - Status:  GET https://IP:8002/api/v2/  (Feld device.PowerState)
 *  - Tasten:  wss://IP:8002/api/v2/channels/samsung.remote.control
 *
 * Beim ersten Verbinden zeigt der Fernseher eine Abfrage ("Zulassen").
 * Danach liefert er ein Token, mit dem spätere Verbindungen ohne Abfrage gehen.
 *
 * Alle Funktionen blockieren und dürfen nicht im UI-Thread laufen.
 */
object SamsungTv {
    private const val PORT = 8002
    private const val APP_NAME = "SleepTvOff"
    private const val KEY_POWER = "KEY_POWER"

    data class Result(val ok: Boolean, val message: String, val token: String? = null)

    enum class Power { ON, STANDBY, UNREACHABLE }

    /**
     * Der Fernseher verwendet auf Port 8002 ein selbstsigniertes Zertifikat,
     * eine normale Prüfung schlägt daher immer fehl. Dieser Client wird
     * ausschließlich für die vom Nutzer eingetragene TV-Adresse im eigenen
     * WLAN verwendet, nie für Verbindungen ins Internet.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustTv = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val client: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(trustTv), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustTv)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private val statusClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
    }

    /** Ist der Fernseher an? Ein ausgeschalteter TV antwortet meist gar nicht mehr. */
    fun powerState(ip: String): Power {
        return try {
            val request = Request.Builder().url("https://$ip:$PORT/api/v2/").build()
            statusClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val state = JSONObject(body).optJSONObject("device")?.optString("PowerState", "").orEmpty()
                // Ältere Modelle liefern kein PowerState: erreichbar heißt dort "an".
                if (state.equals("standby", ignoreCase = true)) Power.STANDBY else Power.ON
            }
        } catch (e: Exception) {
            Power.UNREACHABLE
        }
    }

    /** Erstkopplung: wartet, bis am Fernseher "Zulassen" gewählt wurde. */
    fun pair(ip: String): Result {
        if (ip.isBlank()) return Result(false, "Keine IP-Adresse eingetragen")
        if (powerState(ip) == Power.UNREACHABLE) {
            return Result(false, "Fernseher unter $ip nicht erreichbar. Ist er eingeschaltet und im selben WLAN?")
        }
        val result = session(ip, token = null, key = null, waitSec = 45)
        return if (result.ok) result.copy(message = "Fernseher gekoppelt") else result
    }

    /**
     * Schaltet den Fernseher aus. KEY_POWER ist ein Umschalter, deshalb wird
     * vorher geprüft, ob der TV überhaupt an ist. Sonst würde er eingeschaltet.
     */
    fun turnOff(ip: String, token: String): Result {
        if (ip.isBlank()) return Result(false, "Keine IP-Adresse eingetragen")
        return when (powerState(ip)) {
            Power.UNREACHABLE -> Result(true, "Fernseher nicht erreichbar, vermutlich schon aus")
            Power.STANDBY -> Result(true, "Fernseher war schon aus")
            Power.ON -> {
                val result = session(ip, token.ifBlank { null }, KEY_POWER, waitSec = 5)
                if (result.ok) result.copy(message = "Fernseher ausgeschaltet") else result
            }
        }
    }

    private fun session(ip: String, token: String?, key: String?, waitSec: Long): Result {
        val name = Base64.getEncoder().encodeToString(APP_NAME.toByteArray())
        var url = "wss://$ip:$PORT/api/v2/channels/samsung.remote.control?name=$name"
        if (!token.isNullOrBlank()) url += "&token=$token"

        val done = CountDownLatch(1)
        val outcome = AtomicReference<Result?>(null)

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return
                }
                when (json.optString("event")) {
                    "ms.channel.connect" -> {
                        val newToken = json.optJSONObject("data")
                            ?.optString("token", "").orEmpty().ifBlank { null }
                        if (key != null) webSocket.send(keyCommand(key))
                        outcome.compareAndSet(null, Result(true, "OK", newToken ?: token))
                        done.countDown()
                    }
                    "ms.channel.unauthorized" -> {
                        outcome.compareAndSet(
                            null,
                            Result(false, "Fernseher hat die Verbindung abgelehnt. Bitte neu koppeln.")
                        )
                        done.countDown()
                    }
                    "ms.channel.timeOut" -> {
                        outcome.compareAndSet(
                            null,
                            Result(false, "Abfrage am Fernseher wurde nicht bestätigt")
                        )
                        done.countDown()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                outcome.compareAndSet(
                    null,
                    Result(false, "Verbindung fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}")
                )
                done.countDown()
            }
        }

        val socket = try {
            client.newWebSocket(Request.Builder().url(url).build(), listener)
        } catch (e: Exception) {
            return Result(false, "Ungültige Adresse: $ip")
        }

        val finished = try {
            done.await(waitSec, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!finished) {
            socket.cancel()
            return Result(false, "Keine Antwort vom Fernseher. Kopplung prüfen oder neu koppeln.")
        }

        val result = outcome.get() ?: Result(false, "Unbekannter Fehler")
        if (result.ok && key != null) {
            // Dem Fernseher kurz Zeit geben, den Tastendruck zu verarbeiten.
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                // egal, wir schließen ohnehin
            }
        }
        socket.close(1000, null)
        return result
    }

    private fun keyCommand(key: String): String {
        val params = JSONObject()
            .put("Cmd", "Click")
            .put("DataOfCmd", key)
            .put("Option", "false")
            .put("TypeOfRemote", "SendRemoteKey")
        return JSONObject()
            .put("method", "ms.remote.control")
            .put("params", params)
            .toString()
    }
}
