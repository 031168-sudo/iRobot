package com.braava.local.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * On-device network check for the Xiaomi (miIO) screen.
 *
 * Shows the active transport (Wi-Fi/cellular/VPN), the phone's IP, whether the
 * Android 13+ "Nearby devices" permission is granted and the result of a real
 * UDP miIO handshake. On Android 16 LNP a missing grant shows up as EPERM.
 */
object NetworkDiagnostics {

    suspend fun run(context: Context, ip: String): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        lines += "Приложение: ${version(context)}"

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm?.getNetworkCapabilities(it) }
        val props = network?.let { cm?.getLinkProperties(it) }

        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("сотовая")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        }
        lines += "Сеть: ${transports.joinToString(", ").ifBlank { "нет" }}"

        val address = props?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
        lines += "IP телефона: ${address?.hostAddress ?: "—"}"
        lines += "VPN: ${if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) "включён" else "нет"}"
        lines += "Разрешение «Устройства рядом»: ${nearby(context)}"
        lines += udpTest(ip)
        lines
    }

    private fun nearby(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "не требуется"
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) "разрешено" else "НЕ разрешено"
    }

    private fun udpTest(ip: String): String {
        val hello = ByteArray(32).apply {
            this[0] = 0x21.toByte()
            this[1] = 0x31.toByte()
            this[2] = 0x00.toByte()
            this[3] = 0x20.toByte()
            for (i in 4 until 32) this[i] = 0xFF.toByte()
        }
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                socket.send(DatagramPacket(hello, hello.size, InetAddress.getByName(ip), 54321))
                val buffer = ByteArray(64)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                "UDP 54321: ответ ${response.length} байт"
            }
        } catch (e: Exception) {
            val hint = if (e.message?.contains("EPERM", ignoreCase = true) == true) {
                "  ← Android блокирует локальную сеть: разрешение Nearby devices, VPN или LNP"
            } else {
                ""
            }
            "UDP 54321: ${e.javaClass.simpleName}: ${e.message}$hint"
        }
    }

    private fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
