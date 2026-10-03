package com.braava.local.data.model

import org.json.JSONObject

/** Lifetime counters, battery identity and device info from the shadow. */
data class RobotHealth(
    val batteryType: String? = null,
    val batteryId: String? = null,
    val batteryMfg: String? = null,
    val chargeCycles: Int? = null,
    val estCapacityMah: Int? = null,
    val avgChargeMin: Int? = null,
    val hoursOnDock: Int? = null,

    val powerOnHours: Int? = null,
    val runHours: Int? = null,
    val runMinutes: Int? = null,
    val totalSqft: Int? = null,

    val missionsTotal: Int? = null,
    val missionsOk: Int? = null,
    val missionsFailed: Int? = null,
    val missionsCancelled: Int? = null,
    val avgMissionMins: Int? = null,
    val avgCycleMins: Int? = null,

    val chargeOk: Int? = null,
    val chargeErrors: Int? = null,
    val chargeChatters: Int? = null,
    val chargeKnockoffs: Int? = null,
    val chargeLithF: Int? = null,

    val stuck: Int? = null,
    val panics: Int? = null,
    val wheelStalls: Int? = null,
    val cliffFront: Int? = null,
    val cliffRight: Int? = null,

    val buttonClean: Int? = null,
    val buttonDock: Int? = null,
    val buttonSpot: Int? = null,
    val bumperPresses: Int? = null,
    val wheelDrops: Int? = null,

    val rebootNav: Int? = null,
    val rebootMobility: Int? = null,
    val rebootSafety: Int? = null,
    val rebootMapLoad: Int? = null,

    val navMissionId: String? = null,
    val navLandmarks: Int? = null,
    val navGoodLandmarks: Int? = null,
    val navGain: Int? = null,
    val navExpo: Int? = null,
    val navMapStatus: String? = null,

    val mapCount: Int? = null,
    val serial: String? = null,
    val wlanMac: String? = null,
)

object RobotHealthParser {

    fun parse(reported: JSONObject): RobotHealth? {
        val run = reported.optJSONObject("bbrun")
        val runtime = reported.optJSONObject("runtimeStats")
        val chg3 = reported.optJSONObject("bbchg3")
        val chg = reported.optJSONObject("bbchg")
        val mssn = reported.optJSONObject("bbmssn")
        val rst = reported.optJSONObject("bbrstinfo")
        val sys = reported.optJSONObject("bbsys")
        val bat = reported.optJSONObject("batInfo")
        val nav = reported.optJSONObject("mssnNavStats")
        val bbnav = reported.optJSONObject("bbnav")
        val sw = reported.optJSONObject("bbswitch")
        val hw = reported.optJSONObject("hwPartsRev")
        val pmaps = reported.optJSONArray("pmaps")

        if (run == null && runtime == null && chg3 == null && chg == null &&
            mssn == null && bat == null
        ) {
            return null
        }

        return RobotHealth(
            batteryType = reported.nonBlank("batteryType"),
            batteryId = bat?.nonBlank("mName"),
            batteryMfg = bat?.nonBlank("mDate"),
            chargeCycles = bat?.intOrNull("cCount"),
            estCapacityMah = chg3?.intOrNull("estCap"),
            avgChargeMin = chg3?.intOrNull("avgMin"),
            hoursOnDock = chg3?.intOrNull("hOnDock"),

            powerOnHours = sys?.intOrNull("hr"),
            runHours = runtime?.intOrNull("hr"),
            runMinutes = runtime?.intOrNull("min"),
            totalSqft = runtime?.intOrNull("sqft"),

            missionsTotal = mssn?.intOrNull("nMssn"),
            missionsOk = mssn?.intOrNull("nMssnOk"),
            missionsFailed = mssn?.intOrNull("nMssnF"),
            missionsCancelled = mssn?.intOrNull("nMssnC"),
            avgMissionMins = mssn?.intOrNull("aMssnM"),
            avgCycleMins = mssn?.intOrNull("aCycleM"),

            chargeOk = chg?.intOrNull("nChgOk"),
            chargeErrors = chg?.intOrNull("nChgErr"),
            chargeChatters = chg?.intOrNull("nChatters"),
            chargeKnockoffs = chg?.intOrNull("nKnockoffs"),
            chargeLithF = chg?.intOrNull("nLithF"),

            stuck = run?.intOrNull("nStuck"),
            panics = run?.intOrNull("nPanics"),
            wheelStalls = run?.intOrNull("nWStll"),
            cliffFront = run?.intOrNull("nCliffsF"),
            cliffRight = run?.intOrNull("nCliffsR"),

            buttonClean = sw?.intOrNull("nClean"),
            buttonDock = sw?.intOrNull("nDock"),
            buttonSpot = sw?.intOrNull("nSpot"),
            bumperPresses = sw?.intOrNull("nBumper"),
            wheelDrops = sw?.intOrNull("nDrops"),

            rebootNav = rst?.intOrNull("nNavRst"),
            rebootMobility = rst?.intOrNull("nMobRst"),
            rebootSafety = rst?.intOrNull("nSafRst"),
            rebootMapLoad = rst?.intOrNull("nMapLoadRst"),

            navMissionId = nav?.nonBlank("missionId"),
            navLandmarks = nav?.intOrNull("lmk"),
            navGoodLandmarks = bbnav?.intOrNull("nGoodLmrks") ?: nav?.intOrNull("gLmk"),
            navGain = bbnav?.intOrNull("aGain"),
            navExpo = bbnav?.intOrNull("aExpo"),
            navMapStatus = nav?.nonBlank("mpSt"),

            mapCount = pmaps?.length(),
            serial = hw?.nonBlank("navSerialNo"),
            wlanMac = hw?.nonBlank("wlan0HwAddr"),
        )
    }
}

private fun JSONObject.nonBlank(key: String): String? =
    optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null
