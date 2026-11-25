package com.braava.local.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket

/**
 * Wi-Fi-only socket binding.
 *
 * When both Wi-Fi and mobile data (or a VPN) are active, Android may route a
 * plain UDP socket through the default network, which cannot reach the robot
 * on 192.168.x.x and can even fail with EPERM. Binding the socket to the Wi-Fi
 * network keeps miIO on the LAN.
 */
object LocalNetwork {

    fun wifi(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun bind(socket: DatagramSocket, network: Network?): Boolean {
        if (network == null) return false
        return runCatching {
            network.bindSocket(socket)
            true
        }.getOrDefault(false)
    }
}
