package com.braava.local.data

import android.content.Context
import android.content.SharedPreferences

/** User-given names for maps and rooms, kept locally (cloud is unaffected). */
class NameStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("braava_names", Context.MODE_PRIVATE)

    fun mapNames(): Map<String, String> = prefs.all
        .filterKeys { it.startsWith("m:") }
        .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix("m:") to it } }
        .toMap()

    fun roomNames(): Map<String, String> = prefs.all
        .filterKeys { it.startsWith("r:") }
        .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix("r:") to it } }
        .toMap()

    fun setMapName(pmapId: String, name: String) {
        prefs.edit().putString("m:$pmapId", name).apply()
    }

    fun setRoomName(pmapId: String, regionId: String, name: String) {
        prefs.edit().putString("r:$pmapId|$regionId", name).apply()
    }
}
