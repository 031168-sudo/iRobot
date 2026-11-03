package com.braava.local.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Discovers iRobot robots on the local network.
 *
 * Protocol: send the ASCII string `irobotmcs` by UDP broadcast to port 5678;
 * every robot answers on the same port with a small JSON blob.
 *
 * Broadcast is unreliable on modern Android/APs, so there is also a TCP sweep
 * of the local /24 on the robot's MQTT port (8883) as a fallback.
 */
object RobotDiscovery {

    const val PORT = 5678
    const val MQTT_PORT = 8883
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
            perform(context, timeoutMs, perRobotMs)
        } finally {
            runCatching { lock?.release() }
        }
    }

    /**
     * Sweeps the phone's /24 and returns IPs that accept TCP on [port].
     * This finds the robot even when UDP broadcast is filtered.
     */
    suspend fun scanSubnet(
        context: Context,
        port: Int = MQTT_PORT,
        timeoutMs: Int = 250,
        concurrency: Int = 32,
    ): List<String> = withContext(Dispatchers.IO) {
        val lan = localLan(context) ?: return@withContext emptyList()
        val octets = lan.address.address
        val prefix = "${octets[0].toInt() and 0xFF}.${octets[1].toInt() and 0xFF}." +
            "${octets[2].toInt() and 0xFF}"
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val permits = Semaphore(concurrency)
        coroutineScope {
            for (host in 1..254) {
                launch {
                    permits.withPermit {
                        val ip = "$prefix.$host"
                        if (isTcpOpen(ip, port, timeoutMs)) results += ip
                    }
                }
            }
        }
        results.sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    /**
     * Asks one specific address for its discovery JSON (unicast `irobotmcs`).
     * Used to confirm that a scanned host really is the expected robot.
     */
    suspend fun probe(context: Context, ip: String, timeoutMs: Int = 800): Found? =
        withContext(Dispatchers.IO) {
            val socket = DatagramSocket()
            socket.soTimeout = timeoutMs
            try {
                val payload = MESSAGE.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(ip), PORT))
                val buffer = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        return@withContext null
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    parse(text, packet.address?.hostAddress)?.let { return@withContext it }
                }
                null
            } catch (_: Exception) {
                null
            } finally {
                runCatching { socket.close() }
            }
        }

    private fun perform(context: Context, timeoutMs: Long, perRobotMs: Long): List<Found> {
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

            val payload = MESSAGE.toByteArray(Charsets.UTF_8)
            for (target in broadcastTargets(context)) {
                repeat(BROADCASTS) {
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, target, PORT))
                    }
                }
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

    /** Broadcast to 255.255.255.255 and to the local /24 directed broadcast. */
    private fun broadcastTargets(context: Context): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching { targets += InetAddress.getByName("255.255.255.255") }
        localLan(context)?.let { lan ->
            val b = lan.address.address
            val subnet = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}." +
                "${b[2].toInt() and 0xFF}.255"
            runCatching { targets += InetAddress.getByName(subnet) }
        }
        return targets.toList()
    }

    private data class Lan(val address: Inet4Address, val prefix: Int)

    /** The phone's private IPv4 on Wi-Fi/Ethernet, preferring real LANs. */
    private fun localLan(context: Context): Lan? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val ranked = cm.allNetworks.sortedBy { network ->
            val caps = cm.getNetworkCapabilities(network)
            when {
                caps == null -> 3
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 3
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 3
                else -> 2
            }
        }
        for (network in ranked) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                continue
            }
            val link = cm.getLinkProperties(network) ?: continue
            for (entry in link.linkAddresses) {
                val address = entry.address
                if (address is Inet4Address && !address.isLoopbackAddress && address.isPrivate()) {
                    return Lan(address, entry.prefixLength)
                }
            }
        }
        return null
    }

    private fun Inet4Address.isPrivate(): Boolean {
        val b = address
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun isTcpOpen(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }

    private fun parse(text: String, sourceIp: String?): Found? {
        if (text == MESSAGE) return null
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val hostname = json.optString("hostname").takeIf { it.isNotBlank() } ?: return null
        val blid = json.optString("blid").takeIf { it.isNotBlank() }
        val hostnameId = hostname.split("-", limit = 2).takeIf { it.size == 2 }?.get(1)
        val identifier = blid ?: hostnameId?.takeIf { it.isNotBlank() } ?: return null
        if (hostname.substringBefore("-").lowercase() !in setOf("roomba", "irobot")) return null

        val ip = json.optString("ip").takeIf { it.isNotBlank() } ?: sourceIp ?: return null
        return Found(
            ip = ip,
            hostname = hostname,
            blid = identifier,
            mac = json.optString("mac").takeIf { it.isNotBlank() },
            sku = json.optString("sku").takeIf { it.isNotBlank() },
            robotName = json.optString("robotname").takeIf { it.isNotBlank() },
        )
    }
}
