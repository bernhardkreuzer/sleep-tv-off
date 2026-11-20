package com.bernhardkreuzer.sleeptvoff.mobile

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Spricht mit dem eingebauten MQTT-Dienst eines VIDAA-Fernsehers (Port 36669, TLS).
 * Reiner JVM-Code ohne Android-Abhängigkeiten. Alle Methoden blockieren.
 *
 * Ablauf der Kopplung:
 *  1. [startPairing] lässt den Fernseher eine vierstellige PIN anzeigen.
 *  2. [submitPin] schickt die PIN und holt danach ein Zugangs- und ein Erneuerungstoken.
 * Spätere Verbindungen melden sich mit dem Zugangstoken als Passwort an.
 */
class VidaaClient private constructor(
    private val mqtt: MiniMqtt,
    val clientId: String,
    val username: String
) : Closeable {

    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val accessDays: Int,
        val refreshDays: Int
    )

    sealed class Connect {
        class Ok(val client: VidaaClient) : Connect()

        /** Der Fernseher hat die Anmeldung abgelehnt (MQTT-Code, -1 = ohne Antwort getrennt). */
        class Refused(val code: Int) : Connect()
    }

    sealed class PinResult {
        class Paired(val tokens: Tokens) : PinResult()
        object WrongPin : PinResult()
        object NoAnswer : PinResult()
    }

    fun startPairing() {
        mqtt.publish(
            topic("ui_service", "actions/vidaa_app_connect"),
            "{\"app_version\":2,\"connect_result\":0,\"device_type\":\"Mobile App\"}"
        )
    }

    fun submitPin(pin: Int, timeoutMs: Long): PinResult {
        mqtt.messages.clear()
        // Die PIN muss als Zahl gesendet werden, nicht als Text.
        mqtt.publish(topic("ui_service", "actions/authenticationcode"), "{\"authNum\":$pin}")

        val answer = await(timeoutMs) { "authentication" in it.topic && Json.long(it.payload, "result") != null }
            ?: return PinResult.NoAnswer
        if (Json.long(answer.payload, "result") != 1L) return PinResult.WrongPin

        val tokens = requestTokens("", timeoutMs) ?: return PinResult.NoAnswer
        return PinResult.Paired(tokens)
    }

    /** Holt mit dem Erneuerungstoken ein frisches Tokenpaar. */
    fun refresh(refreshToken: String, timeoutMs: Long): Tokens? = requestTokens(refreshToken, timeoutMs)

    /**
     * Fragt den Zustand ab.
     * @return z. B. "livetv" oder "app", [STATE_STANDBY] wenn der Fernseher aus ist
     *         (Schnellstart-Standby), null wenn keine Antwort kam.
     */
    fun state(timeoutMs: Long): String? {
        mqtt.publish(topic("ui_service", "actions/gettvstate"), "")
        var latest = await(timeoutMs) { it.topic == TOPIC_STATE } ?: return null
        // Kurz nachlauschen: Eine ältere gespeicherte Meldung kann vor der aktuellen eintreffen.
        while (true) {
            latest = await(300) { it.topic == TOPIC_STATE } ?: break
        }
        return Json.string(latest.payload, "statetype") ?: "unbekannt"
    }

    fun sendKey(key: String) {
        mqtt.publish(topic("remote_service", "actions/sendkey"), key)
    }

    override fun close() = mqtt.close()

    private fun requestTokens(refreshToken: String, timeoutMs: Long): Tokens? {
        mqtt.publish(
            topic("platform_service", "data/gettoken"),
            "{\"refreshtoken\":\"${Json.escape(refreshToken)}\"}"
        )
        if (refreshToken.isEmpty()) {
            // Erstkopplung: PIN-Fenster am Fernseher schließen.
            mqtt.publish(topic("ui_service", "actions/authenticationcodeclose"), "")
        }
        val answer = await(timeoutMs) {
            "tokenissuance" in it.topic && Json.string(it.payload, "accesstoken") != null
        } ?: return null

        return Tokens(
            accessToken = Json.string(answer.payload, "accesstoken") ?: return null,
            refreshToken = Json.string(answer.payload, "refreshtoken") ?: refreshToken,
            accessDays = (Json.long(answer.payload, "accesstoken_duration_day") ?: 7L).toInt(),
            refreshDays = (Json.long(answer.payload, "refreshtoken_duration_day") ?: 30L).toInt()
        )
    }

    private fun subscribeAll() {
        mqtt.subscribe(TOPIC_STATE)
        mqtt.subscribe("/remoteapp/mobile/$clientId/ui_service/data/authentication")
        mqtt.subscribe("/remoteapp/mobile/$clientId/ui_service/data/authenticationcode")
        mqtt.subscribe("/remoteapp/mobile/$clientId/platform_service/data/tokenissuance")
    }

    private fun topic(service: String, action: String) = "/remoteapp/tv/$service/$clientId/$action"

    private fun await(timeoutMs: Long, match: (MiniMqtt.Message) -> Boolean): MiniMqtt.Message? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) return null
            val message = mqtt.messages.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (match(message)) return message
        }
    }

    companion object {
        const val PORT = 36669
        const val STATE_STANDBY = "fake_sleep_0"
        const val KEY_POWER = "KEY_POWER"
        private const val TOPIC_STATE = "/remoteapp/mobile/broadcast/ui_service/state"

        /**
         * Baut die TLS-Verbindung auf und meldet sich an.
         * @throws java.io.IOException wenn der Fernseher gar nicht erreichbar ist
         */
        fun connect(
            factory: SSLSocketFactory,
            host: String,
            clientId: String,
            username: String,
            password: String,
            timeoutMs: Int,
            port: Int = PORT
        ): Connect {
            val socket = factory.createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.startHandshake()
            } catch (e: Exception) {
                closeQuietly(socket)
                throw e
            }

            val mqtt = MiniMqtt(socket)
            val code = mqtt.connect(clientId, username, password, timeoutMs)
            if (code != 0) {
                mqtt.close()
                return Connect.Refused(code)
            }
            val client = VidaaClient(mqtt, clientId, username)
            client.subscribeAll()
            return Connect.Ok(client)
        }

        /**
         * TLS mit Client-Zertifikat: Der Fernseher lässt nur Verbindungen zu, die
         * sich mit dem Zertifikat der offiziellen VIDAA-App ausweisen.
         *
         * Das Zertifikat des Fernsehers selbst ist selbstsigniert und wird nicht
         * geprüft. Diese Fabrik darf deshalb nur für die vom Nutzer eingetragene
         * TV-Adresse im eigenen WLAN verwendet werden, nie für Internet-Verbindungen.
         */
        fun socketFactory(certificatePem: String, privateKeyPem: String): SSLSocketFactory {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificatePem.toByteArray(Charsets.US_ASCII)))
            val keyBytes = Base64.getMimeDecoder().decode(
                privateKeyPem.lines().filterNot { it.startsWith("-----") }.joinToString("\n")
            )
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))

            val password = CharArray(0)
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setKeyEntry("vidaa", privateKey, password, arrayOf(certificate))
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagers.init(keyStore, password)

            val trustTv = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(keyManagers.keyManagers, arrayOf(trustTv), SecureRandom())
            return context.socketFactory
        }

        private fun closeQuietly(socket: Socket) {
            try {
                socket.close()
            } catch (e: Exception) {
                // egal
            }
        }
    }

    /** Minimales Auslesen einzelner JSON-Felder, reicht für die flachen Antworten des Fernsehers. */
    internal object Json {
        fun string(json: String, key: String): String? =
            Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(json)?.groupValues?.get(1)

        fun long(json: String, key: String): Long? =
            Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"?(-?\\d+)\"?")
                .find(json)?.groupValues?.get(1)?.toLongOrNull()

        fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
