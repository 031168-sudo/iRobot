package com.braava.local.data

/** Which local protocol a saved robot speaks. */
enum class RobotKind {
    /** iRobot: MQTT over TLS on 8883, BLID + local password. */
    IROBOT,

    /** Xiaomi/Roborock vacuum: miIO over UDP 54321, device id + token. */
    XIAOMI,
}
