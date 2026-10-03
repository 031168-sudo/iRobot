package com.braava.local.data.cloud

import com.braava.local.data.mqtt.PermissiveTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

/**
 * Reads the robot's local password directly over TLS, without the cloud.
 *
 * Put the robot on its dock, press and hold HOME until it plays a tone and
 * the Wi-Fi LED flashes, then call this. Some models answer
 * "unsupported" and serve the password only from the cloud.
 */
object LocalPasswordFetcher {

    private val REQUEST = byteArrayOf(
        0xF0.toByte(), 0x05, 0xEF.toByte(), 0xCC.toByte(), 0x3B, 0x29, 0x00,
    )
    private val UNSUPPORTED = byteArrayOf(
        0xF0.toByte(), 0x05, 0xEF.toByte(), 0xCC.toByte(), 0x3B, 0x29, 0x03,
    )
    private const val PORT = 8883
    private const val HEADER = 2
    private const val INITIAL_LENGTH = 35

    suspend fun fetch(ip: String, timeoutMs: Int = 10_000): String? = withContext(Dispatchers.IO) {
        val factory = PermissiveTls.socketFactory()
        val socket = factory.createSocket() as? javax.net.ssl.SSLSocket ?: return@withContext null
        try {
            socket.connect(InetSocketAddress(ip, PORT), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.startHandshake()

            socket.outputStream.write(REQUEST)
            socket.outputStream.flush()

            var data = ByteArray(0)
            var expectedLength = INITIAL_LENGTH
            val buffer = ByteArray(1024)
            while (data.size < expectedLength + HEADER) {
                val read = socket.inputStream.read(buffer)
                if (read <= 0) break
                data += buffer.copyOf(read)
                if (data.contentEquals(UNSUPPORTED)) return@withContext null
                if (data.size >= HEADER) {
                    expectedLength = data[1].toInt() and 0xFF
                }
            }
            if (data.size < 7) return@withContext null
            String(data.copyOfRange(7, data.size), Charsets.UTF_8).trimEnd('\u0000')
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }
}
