package com.braava.local.data.mqtt

import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * iRobot robots present self-signed certificates and old firmware needs
 * legacy renegotiation, so verification is deliberately disabled. This is
 * safe here because the connection is confined to the local network and the
 * robot is authenticated by its BLID/password.
 */
object PermissiveTls {

    fun socketFactory(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val context = SSLContext.getInstance("TLS")
        context.init(null, trustAll, SecureRandom())
        val delegate = context.socketFactory

        return object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

            // Paho's TCPNetworkModule calls the no-argument createSocket()
            // and connects afterwards, so this must be implemented too —
            // SSLSocketFactory's default throws "Unconnected sockets not
            // implemented".
            override fun createSocket(): Socket = configure(delegate.createSocket())

            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
                configure(delegate.createSocket(s, host, port, autoClose))

            override fun createSocket(host: String?, port: Int): Socket =
                configure(delegate.createSocket(host, port))

            override fun createSocket(
                host: String?,
                port: Int,
                localHost: java.net.InetAddress?,
                localPort: Int,
            ): Socket = configure(delegate.createSocket(host, port, localHost, localPort))

            override fun createSocket(host: java.net.InetAddress?, port: Int): Socket =
                configure(delegate.createSocket(host, port))

            override fun createSocket(
                address: java.net.InetAddress?,
                port: Int,
                localAddress: java.net.InetAddress?,
                localPort: Int,
            ): Socket = configure(delegate.createSocket(address, port, localAddress, localPort))
        }
    }

    private fun configure(socket: Socket): Socket {
        if (socket is SSLSocket) {
            runCatching {
                socket.sslParameters = socket.sslParameters.apply { serverNames = null }
            }
            runCatching {
                val supportedProtocols = socket.supportedProtocols.toSet()
                val protocols = listOf("TLSv1.2", "TLSv1.1", "TLSv1")
                    .filter { it in supportedProtocols }
                if (protocols.isNotEmpty()) {
                    socket.enabledProtocols = protocols.toTypedArray()
                }
            }
            runCatching {
                // RSA key exchange only. The robot also offers weak DHE
                // groups that Android rejects ("DH key too small"), and
                // letting it pick one breaks the handshake.
                val supported = socket.supportedCipherSuites.toSet()
                val chosen = RSA_CIPHERS.filter { it in supported }
                if (chosen.isNotEmpty()) {
                    socket.enabledCipherSuites = chosen.toTypedArray()
                }
            }
        }
        return socket
    }

    private val RSA_CIPHERS = listOf(
        "TLS_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_RSA_WITH_AES_256_CBC_SHA256",
        "TLS_RSA_WITH_AES_128_CBC_SHA",
        "TLS_RSA_WITH_AES_256_CBC_SHA",
    )
}
