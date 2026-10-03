package com.braava.local.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Discovers iRobot robots on the local network.
 *
 * Protocol: send the ASCII string `irobotmcs` by UDP broadcast to port 5678;
 * every robot answers on the same port with a small JSON blob.
 */
object RobotDiscovery {

    const val PORT = 5678
    private const val MESSAGE = "irobotmcs"
    private const val BROADCASTS = 5

    data class Found(
        val ip: String,
        val hostname: String,
        val blid: String,
        val mac: String?,
        val sku: String?,
        val robotName: String?,
    )

    suspend fun discover(
        context: Context,
        timeoutMs: Long = 6000,
        perRobotMs: Long = 1200,
    ): List<Found> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("braava-discovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        try {
            perform(timeoutMs, perRobotMs)
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun perform(timeoutMs: Long, perRobotMs: Long): List<Found> {
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            try {
                socket.bind(InetSocketAddress(PORT))
            } catch (_: Exception) {
                socket.bind(InetSocketAddress(0))
            }
            socket.broadcast = true
            socket.soTimeout = perRobotMs.toInt()

            val target = InetAddress.getByName("255.255.255.255")
            val payload = MESSAGE.toByteArray(Charsets.UTF_8)
            repeat(BROADCASTS) {
                socket.send(DatagramPacket(payload, payload.size, target, PORT))
            }

            val found = LinkedHashMap<String, Found>()
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val parsed = parse(text, packet.address?.hostAddress) ?: continue
                found[parsed.ip] = parsed
            }
            return found.values.toList()
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parse(text: String, sourceIp: String?): Found? {
        if (text == MESSAGE) return null
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val hostname = json.optString("hostname").takeIf { it.isNotBlank() } ?: return null
        val parts = hostname.split("-", limit = 2)
        if (parts.size != 2 || parts[1].isBlank()) return null
        if (parts[0].lowercase() !in setOf("roomba", "irobot")) return null

        val ip = json.optString("ip").takeIf { it.isNotBlank() } ?: sourceIp ?: return null
        return Found(
            ip = ip,
            hostname = hostname,
            blid = parts[1],
            mac = json.optString("mac").takeIf { it.isNotBlank() },
            sku = json.optString("sku").takeIf { it.isNotBlank() },
            robotName = json.optString("robotname").takeIf { it.isNotBlank() },
        )
    }
}
