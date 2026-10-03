package com.braava.local.data.mqtt

import com.braava.local.data.RobotConfig
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Local MQTT connection to one robot.
 *
 * Transport is MQTT 3.1.1 over TLS on port 8883. The client id and username
 * are both the robot's BLID; the password is its local password. Everything
 * the robot reports is published under `#`, and commands go to `cmd`.
 */
class RoombaMqttClient(
    private val config: RobotConfig,
    private val listener: Listener,
) {

    interface Listener {
        fun onConnected()
        fun onConnectionLost(cause: Throwable?)
        fun onConnectFailed(error: Throwable)
        fun onMessage(topic: String, payload: String)
    }

    @Volatile
    private var client: MqttAsyncClient? = null

    val isConnected: Boolean
        get() = client?.isConnected == true

    fun connect() {
        if (client != null) return

        val mqtt = MqttAsyncClient(
            "ssl://${config.ip}:$PORT",
            config.blid,
            MemoryPersistence(),
        )
        mqtt.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                listener.onConnected()
                runCatching { mqtt.subscribe("#", QOS) }
            }

            override fun connectionLost(cause: Throwable?) {
                listener.onConnectionLost(cause)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                listener.onMessage(topic, String(message.payload, Charsets.UTF_8))
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        val options = MqttConnectOptions().apply {
            isCleanSession = false
            userName = config.blid
            password = config.password.toCharArray()
            keepAliveInterval = 30
            connectionTimeout = 6
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            isAutomaticReconnect = true
            maxReconnectDelay = 60_000
            socketFactory = PermissiveTls.socketFactory()
        }

        client = mqtt
        try {
            mqtt.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    listener.onConnectFailed(
                        exception
                            ?: MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt()),
                    )
                }
            })
        } catch (e: MqttException) {
            listener.onConnectFailed(e)
        }
    }

    fun publish(topic: String, payload: String, onResult: (Throwable?) -> Unit = {}) {
        val mqtt = client
        if (mqtt == null || !mqtt.isConnected) {
            onResult(MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED.toInt()))
            return
        }
        val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply { qos = QOS }
        try {
            mqtt.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = onResult(null)

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    onResult(exception ?: MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt()))
                }
            })
        } catch (e: MqttException) {
            onResult(e)
        }
    }

    fun disconnect() {
        val mqtt = client ?: return
        client = null
        runCatching { if (mqtt.isConnected) mqtt.disconnect() }
        runCatching { mqtt.close() }
    }

    private companion object {
        const val PORT = 8883

        // dorita980 publishes commands at QoS 0; the robot's broker does not
        // acknowledge QoS 1 publishes, which makes them look delivered but
        // never acted upon.
        const val QOS = 0
    }
}
