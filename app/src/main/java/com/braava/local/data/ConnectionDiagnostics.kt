package com.braava.local.data

import com.braava.local.data.mqtt.PermissiveTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/** Step-by-step network check against the robot's local MQTT port. */
object ConnectionDiagnostics {

    private const val PORT = 8883

    suspend fun run(ip: String, timeoutMs: Int = 8000): List<String> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<String>()
            out += "Адрес: $ip:$PORT"

            // TLS first. A bare TCP connect+close can occupy the robot's
            // single-connection broker and make the following TLS attempt
            // look refused, so the meaningful test runs before it.
            repeat(2) { attempt ->
                val result = tryTls(ip, timeoutMs)
                out += if (result == null) {
                    "TLS: OK"
                } else {
                    "TLS (попытка ${attempt + 1}): ${describe(result)}"
                }
                if (result == null) {
                    out += "Соединение принимается роботом."
                    return@withContext out
                }
                if (attempt == 0) delay(800)
            }

            val tcp = tryTcp(ip, timeoutMs)
            out += if (tcp == null) "TCP: OK (порт открыт)" else "TCP: ${describe(tcp)}"
            out
        }

    /** Returns null on success, the exception otherwise. */
    private fun tryTls(ip: String, timeoutMs: Int): Exception? {
        return try {
            val factory = PermissiveTls.socketFactory()
            val socket = factory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(ip, PORT), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.startHandshake()
            socket.close()
            null
        } catch (e: Exception) {
            e
        }
    }

    private fun tryTcp(ip: String, timeoutMs: Int): Exception? {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, PORT), timeoutMs)
            socket.close()
            null
        } catch (e: Exception) {
            e
        }
    }

    private fun describe(error: Throwable): String {
        val messages = generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
            .distinct()
            .toList()
        val head = error.javaClass.simpleName
        return if (messages.isEmpty()) head else "$head: ${messages.joinToString(" → ")}"
    }
}
