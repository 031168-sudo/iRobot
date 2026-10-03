package com.braava.local.data.model

import org.json.JSONObject

/** Derived, display-friendly snapshot of the robot's reported state. */
data class RobotState(
    val name: String? = null,
    val sku: String? = null,
    val softwareVer: String? = null,
    val batteryPct: Int? = null,
    val phase: String? = null,
    val cycle: String? = null,
    val initiator: String? = null,
    val missionNumber: Int? = null,
    val missionMinutes: Int? = null,
    val missionArea: Int? = null,
    val missionStartEpoch: Long? = null,
    val poseX: Int? = null,
    val poseY: Int? = null,
    val poseTheta: Int? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
    val notReady: Int? = null,
    val readiness: String? = null,
    val binPresent: Boolean? = null,
    val binFull: Boolean? = null,
    val tankPresent: Boolean? = null,
    val tankLevel: Int? = null,
    val mopReady: Boolean? = null,
    val detectedPad: String? = null,
    val padLabel: String? = null,
    val padWetness: Int? = null,
    val chargeLed: Int? = null,
    val rankOverlap: Int? = null,
    val lidOpen: Boolean? = null,
    val signalBars: Int? = null,
    val rssi: Int? = null,
    val dockKnown: Boolean? = null,
    val dockState: String? = null,
    val openOnly: Boolean? = null,
    val noAutoPasses: Boolean? = null,
    val twoPass: Boolean? = null,
    val binPause: Boolean? = null,
    val edgeSupported: Boolean? = null,
    val multiPassSupported: Boolean? = null,
    val capabilities: Set<String> = emptySet(),
    val health: RobotHealth? = null,
    val raw: String = "",
)

object RobotStateParser {

    /** Merge a JSON delta into [target], recursing into nested objects. */
    fun merge(target: JSONObject, patch: JSONObject) {
        for (key in patch.keys()) {
            val incoming = patch.get(key)
            val existing = target.opt(key)
            if (incoming is JSONObject && existing is JSONObject) {
                merge(existing, incoming)
            } else {
                target.put(key, incoming)
            }
        }
    }

    fun parse(master: JSONObject): RobotState {
        val reported = master.optJSONObject("state")
            ?.optJSONObject("reported")
            ?: JSONObject()

        val mission = reported.optJSONObject("cleanMissionStatus")
        val bin = reported.optJSONObject("bin")
        val tank = reported.optJSONObject("tank")
        val cap = reported.optJSONObject("cap")
        val signal = reported.optJSONObject("signal")
        val dock = reported.optJSONObject("dock")

        val errorCode = mission?.intOrNull("error")
        val notReady = mission?.intOrNull("notReady")
        val pad = reported.nonBlank("detectedPad")
        val mopReady = reported.optJSONObject("mopReady")?.boolOrNull("ready")
        val padWetness = reported.optJSONObject("padWetness")
            ?.let { it.intOrNull("reusable") ?: it.intOrNull("disposable") }
        val posePoint = reported.optJSONObject("pose")?.optJSONObject("point")

        return RobotState(
            name = reported.nonBlank("name"),
            sku = reported.nonBlank("sku"),
            softwareVer = reported.nonBlank("softwareVer"),
            batteryPct = reported.intOrNull("batPct"),
            phase = mission?.nonBlank("phase"),
            cycle = mission?.nonBlank("cycle"),
            initiator = mission?.nonBlank("initiator"),
            missionNumber = mission?.intOrNull("nMssn"),
            missionMinutes = mission?.intOrNull("mssnM"),
            missionArea = mission?.intOrNull("sqft"),
            missionStartEpoch = mission?.longOrNull("mssnStrtTm"),
            poseX = posePoint?.intOrNull("x"),
            poseY = posePoint?.intOrNull("y"),
            poseTheta = reported.optJSONObject("pose")?.intOrNull("theta"),
            errorCode = errorCode,
            errorMessage = RobotErrors.message(errorCode),
            notReady = notReady,
            readiness = RobotReadiness.label(notReady),
            binPresent = bin?.boolOrNull("present"),
            binFull = bin?.boolOrNull("full"),
            tankPresent = reported.boolOrNull("tankPresent") ?: tank?.boolOrNull("present"),
            tankLevel = reported.intOrNull("tankLvl") ?: tank?.intOrNull("level"),
            mopReady = mopReady,
            detectedPad = pad,
            padLabel = RobotReadiness.padLabel(pad),
            padWetness = padWetness,
            chargeLed = reported.intOrNull("chrgLrPtrn"),
            rankOverlap = reported.intOrNull("rankOverlap"),
            lidOpen = reported.boolOrNull("lidOpen"),
            signalBars = signal?.intOrNull("wlBars")
                ?: signal?.intOrNull("rssi")?.let { rssiToBars(it) },
            rssi = signal?.intOrNull("rssi"),
            dockKnown = dock?.boolOrNull("known"),
            dockState = dock?.let { it.nonBlank("state") ?: it.intOrNull("state")?.toString() },
            openOnly = reported.boolOrNull("openOnly"),
            noAutoPasses = reported.boolOrNull("noAutoPasses"),
            twoPass = reported.boolOrNull("twoPass"),
            binPause = reported.boolOrNull("binPause"),
            edgeSupported = cap?.capabilityOn("edge"),
            multiPassSupported = cap?.capabilityOn("multiPass"),
            capabilities = cap?.keys()?.asSequence()?.toSet() ?: emptySet(),
            health = RobotHealthParser.parse(reported),
            raw = reported.toString(2),
        )
    }
}

private fun JSONObject.nonBlank(key: String): String? =
    optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.boolOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

private fun JSONObject.capabilityOn(key: String): Boolean? =
    if (has(key) && !isNull(key)) optInt(key) > 0 else null

private fun rssiToBars(rssi: Int): Int = when {
    rssi >= -55 -> 5
    rssi >= -65 -> 4
    rssi >= -70 -> 3
    rssi >= -80 -> 2
    else -> 1
}
