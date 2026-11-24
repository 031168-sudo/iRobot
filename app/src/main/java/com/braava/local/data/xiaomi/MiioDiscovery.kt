package com.braava.local.data.xiaomi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finds miIO devices on the local network by broadcasting the plaintext hello
 * packet to UDP 54321. Every device answers with its device id, so a robot that
 * changed its DHCP address can be located without the cloud.
 */
object MiioDiscovery {

    data class Found(val ip: String, val deviceId: String)

    suspend fun discover(context: Context, timeoutMs: Long = 4000): List<Found> =
        withContext(Dispatchers.IO) {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 700
            val hello = ByteArray(32).apply {
                this[0] = 0x21.toByte()
                this[1] = 0x31.toByte()
                this[2] = 0x00.toByte()
                this[3] = 0x20.toByte()
                for (i in 4 until 32) this[i] = 0xFF.toByte()
            }
            val targets = linkedSetOf("255.255.255.255")
            localBroadcast(context)?.let { targets += it }
            try {
                for (target in targets) {
                    repeat(3) {
                        runCatching {
                            socket.send(
                                DatagramPacket(
                                    hello,
                                    hello.size,
                                    InetAddress.getByName(target),
                                    54321,
                                ),
                            )
                        }
                    }
                }
                val found = linkedMapOf<String, Found>()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(256)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        continue
                    }
                    if (packet.length < 16) continue
                    val ip = packet.address?.hostAddress ?: continue
                    val id = ByteBuffer.wrap(packet.data, 8, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int
                    found[ip] = Found(ip, "%08x".format(id))
                }
                found.values.toList()
            } finally {
                runCatching { socket.close() }
            }
        }

    private fun localBroadcast(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return null
        }
        val link = cm.getLinkProperties(network) ?: return null
        val address = link.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress } ?: return null
        val bytes = address.address
        return "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}." +
            "${bytes[2].toInt() and 0xFF}.255"
    }
}
