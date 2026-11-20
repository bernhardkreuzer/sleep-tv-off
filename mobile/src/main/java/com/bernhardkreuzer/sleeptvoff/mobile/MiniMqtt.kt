package com.bernhardkreuzer.sleeptvoff.mobile

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * Sehr kleiner MQTT-3.1.1-Client, gerade genug für den Fernseher:
 * anmelden, Themen abonnieren, Nachrichten mit QoS 0 senden und empfangen.
 *
 * Bewusst selbst geschrieben statt einer Bibliothek: keine zusätzliche
 * Abhängigkeit, und der Code läuft unverändert auch in einem normalen
 * JVM-Test gegen einen nachgebauten Fernseher.
 */
class MiniMqtt(private val socket: Socket) : Closeable {

    data class Message(val topic: String, val payload: String)

    /** Eingehende Nachrichten in Empfangsreihenfolge. */
    val messages = LinkedBlockingQueue<Message>()

    @Volatile
    var isOpen = false
        private set

    private val out = socket.getOutputStream()
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private var nextPacketId = 1

    /**
     * Meldet sich an und wartet auf die Antwort des Fernsehers.
     * @return 0 bei Erfolg, sonst der Ablehnungscode (4 = falsche Zugangsdaten,
     *         5 = nicht berechtigt), -1 wenn die Verbindung ohne Antwort endet.
     */
    fun connect(clientId: String, username: String, password: String, timeoutMs: Int): Int {
        val body = ByteArrayOutputStream()
        writeString(body, "MQTT")
        body.write(4)                 // Protokollversion 3.1.1
        body.write(0xC2)              // Benutzername + Passwort + Clean Session
        body.write(KEEP_ALIVE_SEC shr 8)
        body.write(KEEP_ALIVE_SEC and 0xFF)
        writeString(body, clientId)
        writeString(body, username)
        writeString(body, password)

        return try {
            socket.soTimeout = timeoutMs
            send(0x10, body.toByteArray())
            val type = input.readUnsignedByte()
            val data = ByteArray(readLength())
            input.readFully(data)
            if (type shr 4 != 2 || data.size < 2) return -1
            val code = data[1].toInt() and 0xFF
            if (code == 0) {
                socket.soTimeout = 0
                isOpen = true
                startThreads()
            }
            code
        } catch (e: IOException) {
            -1
        }
    }

    fun subscribe(topic: String) {
        val body = ByteArrayOutputStream()
        val id = nextPacketId++
        body.write(id shr 8)
        body.write(id and 0xFF)
        writeString(body, topic)
        body.write(0)                 // QoS 0
        send(0x82, body.toByteArray())
    }

    fun publish(topic: String, payload: String) {
        val body = ByteArrayOutputStream()
        writeString(body, topic)
        body.write(payload.toByteArray(Charsets.UTF_8))
        send(0x30, body.toByteArray())
    }

    override fun close() {
        val wasOpen = isOpen
        isOpen = false
        try {
            if (wasOpen) send(0xE0, ByteArray(0))
        } catch (e: IOException) {
            // Verbindung war schon weg
        }
        try {
            socket.close()
        } catch (e: IOException) {
            // egal
        }
    }

    private fun startThreads() {
        Thread({ readLoop() }, "mqtt-read").apply { isDaemon = true }.start()
        Thread({ pingLoop() }, "mqtt-ping").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        try {
            while (isOpen) {
                val header = input.readUnsignedByte()
                val data = ByteArray(readLength())
                input.readFully(data)
                if (header shr 4 == 3) handlePublish(header, data)
                // SUBACK, PINGRESP und alles andere braucht keine Reaktion.
            }
        } catch (e: IOException) {
            // Verbindung beendet
        } finally {
            isOpen = false
        }
    }

    private fun handlePublish(header: Int, data: ByteArray) {
        if (data.size < 2) return
        val topicLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        var offset = 2 + topicLength
        if (offset > data.size) return
        val topic = String(data, 2, topicLength, Charsets.UTF_8)

        val qos = (header shr 1) and 0x03
        if (qos > 0) {
            if (offset + 2 > data.size) return
            // Der Fernseher erwartet bei QoS 1 eine Quittung.
            if (qos == 1) send(0x40, byteArrayOf(data[offset], data[offset + 1]))
            offset += 2
        }
        val payload = String(data, offset, data.size - offset, Charsets.UTF_8)
        messages.offer(Message(topic, payload))
    }

    private fun pingLoop() {
        try {
            while (isOpen) {
                Thread.sleep(KEEP_ALIVE_SEC * 1000L / 3)
                if (isOpen) send(0xC0, ByteArray(0))
            }
        } catch (e: Exception) {
            // Verbindung beendet oder Thread unterbrochen
        }
    }

    private fun send(header: Int, body: ByteArray) {
        val packet = ByteArrayOutputStream()
        packet.write(header)
        var length = body.size
        do {
            var digit = length % 128
            length /= 128
            if (length > 0) digit = digit or 0x80
            packet.write(digit)
        } while (length > 0)
        packet.write(body)
        synchronized(out) {
            out.write(packet.toByteArray())
            out.flush()
        }
    }

    private fun readLength(): Int {
        var multiplier = 1
        var value = 0
        while (true) {
            val digit = input.readUnsignedByte()
            value += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) return value
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) throw IOException("Ungültige Paketlänge")
        }
    }

    private fun writeString(target: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        target.write(bytes.size shr 8)
        target.write(bytes.size and 0xFF)
        target.write(bytes)
    }

    private companion object {
        const val KEEP_ALIVE_SEC = 60
    }
}
