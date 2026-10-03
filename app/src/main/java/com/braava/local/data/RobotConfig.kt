package com.braava.local.data

/**
 * Locally stored credentials + address for one robot.
 *
 * The password is the robot's local MQTT password (from the iRobot cloud once,
 * or read directly from the robot in listening mode).
 */
data class RobotConfig(
    val blid: String,
    val password: String,
    val ip: String,
    val name: String? = null,
    val sku: String? = null,
)
