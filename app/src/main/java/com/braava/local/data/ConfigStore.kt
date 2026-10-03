package com.braava.local.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Persists the saved robots and which one is currently selected. */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("braava_config", Context.MODE_PRIVATE)

    fun loadRobots(): List<RobotConfig> {
        val raw = prefs.getString(KEY_ROBOTS, null)
        if (raw != null) {
            return parseRobots(raw)
        }
        // Migrate the pre-multi-robot install: a single robot was stored as
        // flat keys. Recover it once, then persist in the new format.
        val blid = prefs.getString(KEY_LEGACY_BLID, null)
        val password = prefs.getString(KEY_LEGACY_PASSWORD, null)
        val ip = prefs.getString(KEY_LEGACY_IP, null)
        if (!blid.isNullOrBlank() && !password.isNullOrBlank() && !ip.isNullOrBlank()) {
            val robot = RobotConfig(
                blid = blid,
                password = password,
                ip = ip,
                name = prefs.getString(KEY_LEGACY_NAME, null),
                sku = prefs.getString(KEY_LEGACY_SKU, null),
            )
            saveRobots(listOf(robot))
            saveSelected(blid)
            return listOf(robot)
        }
        return emptyList()
    }

    private fun parseRobots(raw: String): List<RobotConfig> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val blid = obj.optString("blid")
            val password = obj.optString("password")
            val ip = obj.optString("ip")
            if (blid.isBlank() || password.isBlank() || ip.isBlank()) {
                null
            } else {
                RobotConfig(
                    blid = blid,
                    password = password,
                    ip = ip,
                    name = obj.optString("name").takeIf { it.isNotBlank() },
                    sku = obj.optString("sku").takeIf { it.isNotBlank() },
                )
            }
        }
    }.getOrDefault(emptyList())

    fun saveRobots(robots: List<RobotConfig>) {
        val array = JSONArray()
        robots.forEach { robot ->
            array.put(
                JSONObject()
                    .put("blid", robot.blid)
                    .put("password", robot.password)
                    .put("ip", robot.ip)
                    .put("name", robot.name)
                    .put("sku", robot.sku),
            )
        }
        prefs.edit().putString(KEY_ROBOTS, array.toString()).apply()
    }

    fun loadSelected(): String? = prefs.getString(KEY_SELECTED, null)

    fun saveSelected(blid: String?) {
        prefs.edit().putString(KEY_SELECTED, blid).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ROBOTS = "robots"
        const val KEY_SELECTED = "selected"

        // Keys used by the single-robot version, kept for one-time migration.
        const val KEY_LEGACY_BLID = "blid"
        const val KEY_LEGACY_PASSWORD = "password"
        const val KEY_LEGACY_IP = "ip"
        const val KEY_LEGACY_NAME = "name"
        const val KEY_LEGACY_SKU = "sku"
    }
}
