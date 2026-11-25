package com.braava.local.data

import android.content.Context
import org.json.JSONObject

/** Local names for Xiaomi room segments (the cloud does not expose them). */
class XiaomiRoomNameStore(context: Context) {

    private val prefs = context.getSharedPreferences("xiaomi_room_names", Context.MODE_PRIVATE)

    fun load(robot: String): Map<Int, String> {
        val raw = prefs.getString(robot, null) ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = linkedMapOf<Int, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toIntOrNull() ?: continue
            val name = json.optString(key).trim()
            if (name.isNotEmpty()) out[id] = name
        }
        return out
    }

    fun save(robot: String, names: Map<Int, String>) {
        val json = JSONObject()
        names.forEach { (id, name) ->
            if (name.isNotBlank()) json.put(id.toString(), name.trim())
        }
        prefs.edit().putString(robot, json.toString()).apply()
    }
}
