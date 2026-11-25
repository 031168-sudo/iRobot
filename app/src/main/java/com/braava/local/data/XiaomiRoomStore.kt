package com.braava.local.data

import android.content.Context

/** Caches miIO room ids per robot so they are available when it sleeps. */
class XiaomiRoomStore(context: Context) {

    private val prefs = context.getSharedPreferences("xiaomi_rooms", Context.MODE_PRIVATE)

    fun load(key: String): List<Int> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .distinct()
            .sorted()
    }

    fun save(key: String, rooms: List<Int>) {
        prefs.edit().putString(key, rooms.joinToString(",")).apply()
    }
}
