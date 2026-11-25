package com.braava.local.data.xiaomi

import android.net.Network
import com.braava.local.data.LocalNetwork
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal miIO client for Xiaomi/Roborock vacuums.
 *
 * Transport is UDP 54321. The handshake is plaintext and yields the device id
 * and stamp; every command is a JSON body encrypted with AES-128-CBC using a
 * key derived from the device token (32 hex characters).
 */
class MiioClient(
    private val ip: String,
    private val token: String,
    private val timeoutMs: Int = 4000,
    private val network: Network? = null,
) {

    class MiioException(message: String) : Exception(message)

    private var deviceId: Int = 0
    private var stamp: Int = 0
    private var nextId: Int = 1

    fun handshake() {
        val data = exchange(HELLO, timeoutMs)
        if (data.size < HEADER || data[0] != MAGIC_HI || data[1] != MAGIC_LO) {
            throw MiioException("Робот не отвечает на miIO (спит или другой IP)")
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        deviceId = buffer.getInt(8)
        stamp = buffer.getInt(12)
    }

    /** Sends one command and returns the full JSON response. */
    fun command(method: String, params: JSONArray = JSONArray()): JSONObject {
        if (deviceId == 0) handshake()
        val id = nextId++
        val body = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
        val encrypted = encrypt(body.toByteArray(Charsets.UTF_8))
        val length = HEADER + encrypted.size
        // The first 16 bytes are the header fields; the checksum (16 bytes)
        // follows them, so the fixed part of the packet is 32 bytes.
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putShort(MAGIC.toShort())
            .putShort(length.toShort())
            .putInt(0)
            .putInt(deviceId)
            .putInt(stamp)
            .array()
        val checksum = md5(header + tokenBytes() + encrypted)
        val packet = header + checksum + encrypted

        var lastError: Exception? = null
        repeat(2) {
            try {
                val data = exchange(packet, timeoutMs)
                if (data.size < HEADER) throw MiioException("Короткий ответ miIO")
                stamp = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getInt(12)
                val respLength = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                    .getShort(2).toInt() and 0xFFFF
                val end = minOf(respLength, data.size)
                if (end <= HEADER) throw MiioException("Пустой ответ miIO")
                val text = String(decrypt(data.copyOfRange(HEADER, end)), Charsets.UTF_8)
                    .trimEnd('\u0000')
                val json = JSONObject(text)
                val error = json.optJSONObject("error")
                if (error != null) {
                    throw MiioException(
                        "miIO: " + error.optString("message").ifBlank { error.toString() },
                    )
                }
                return json
            } catch (e: Exception) {
                lastError = e
                // A lost UDP packet is common on 2.4 GHz; try once more.
                if (e is MiioException && e.message?.startsWith("miIO:") == true) throw e
            }
        }
        throw lastError ?: MiioException("Робот не ответил на $method")
    }

    fun info(): JSONObject = command("miIO.info")

    fun status(): JSONArray =
        command("get_status").optJSONArray("result") ?: JSONArray()

    /** Rooms as returned by get_room_mapping: [[16, "..."], [17, "..."], ...]. */
    fun roomMapping(): JSONArray =
        command("get_room_mapping").optJSONArray("result") ?: JSONArray()

    fun start() = command("app_start")

    fun stop() = command("app_stop")

    fun pause() = command("app_pause")

    fun charge() = command("app_charge")

    fun findMe() = command("find_me")

    fun cleanSegments(segments: List<Int>, repeat: Int = 1) {
        if (segments.isEmpty()) throw MiioException("Не выбраны комнаты")
        val ids = JSONArray().apply { segments.forEach { put(it) } }
        val params = JSONArray().put(
            JSONObject().put("segments", ids).put("repeat", repeat),
        )
        command("app_segment_clean", params)
    }

    // ------------------------------------------------------------------

    private fun exchange(packet: ByteArray, timeout: Int): ByteArray {
        DatagramSocket().use { socket ->
            LocalNetwork.bind(socket, network)
            socket.soTimeout = timeout
            val target = InetAddress.getByName(ip)
            socket.send(DatagramPacket(packet, packet.size, target, PORT))
            val buffer = ByteArray(65535)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            return buffer.copyOf(response.length)
        }
    }

    /** The token is 32 hex characters; miIO uses its 16 raw bytes. */
    private fun tokenBytes(): ByteArray {
        val clean = token.trim()
        if (clean.length != 32) throw MiioException("Токен должен быть 32 hex-символа")
        return ByteArray(16) { index ->
            val high = Character.digit(clean[index * 2], 16)
            val low = Character.digit(clean[index * 2 + 1], 16)
            if (high < 0 || low < 0) throw MiioException("Токен содержит не hex-символы")
            ((high shl 4) or low).toByte()
        }
    }

    private fun key(): ByteArray = md5(tokenBytes())

    private fun iv(): ByteArray = md5(key() + tokenBytes())

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key(), "AES"),
            IvParameterSpec(iv()),
        )
        // miIO appends a NUL terminator before encrypting.
        return cipher.doFinal(data + byteArrayOf(0))
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key(), "AES"),
            IvParameterSpec(iv()),
        )
        return cipher.doFinal(data)
    }

    private companion object {
        const val PORT = 54321
        const val HEADER = 32
        const val MAGIC = 0x2131
        const val MAGIC_HI: Byte = 0x21
        const val MAGIC_LO: Byte = 0x31

        val HELLO = ByteArray(32).apply {
            this[0] = 0x21.toByte()
            this[1] = 0x31.toByte()
            this[2] = 0x00.toByte()
            this[3] = 0x20.toByte()
            for (i in 4 until 32) this[i] = 0xFF.toByte()
        }

        fun md5(data: ByteArray): ByteArray =
            MessageDigest.getInstance("MD5").digest(data)
    }
}
