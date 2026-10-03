package com.braava.local.data

import android.content.Context
import com.braava.local.data.model.MissionEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** On-device cache of cloud mission history (per robot). */
class MissionHistoryStore(context: Context) {

    private val file = File(context.filesDir, "mission_history.json")

    fun load(blid: String): List<MissionEntry> {
        val root = readRoot() ?: return emptyList()
        val array = root.optJSONArray(blid) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parse)
        }
    }

    fun save(blid: String, entries: List<MissionEntry>) {
        val root = readRoot() ?: JSONObject()
        val array = JSONArray()
        entries.forEach { array.put(write(it)) }
        root.put(blid, array)
        runCatching { file.writeText(root.toString()) }
    }

    private fun readRoot(): JSONObject? {
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun parse(obj: JSONObject): MissionEntry? {
        val nMssn = obj.optInt("nMssn", -1)
        if (nMssn < 0) return null
        return MissionEntry(
            nMssn = nMssn,
            startTime = obj.optLong("startTime").takeIf { it > 0 },
            durationMin = obj.optInt("durationMin").takeIf { it > 0 },
            sqft = obj.optInt("sqft").takeIf { it > 0 },
            result = obj.optString("result"),
            ok = obj.optBoolean("ok"),
            initiator = obj.optString("initiator").takeIf { it.isNotBlank() },
            pmapId = obj.optString("pmapId").takeIf { it.isNotBlank() },
            pmapvId = obj.optString("pmapvId").takeIf { it.isNotBlank() },
            regionIds = obj.optJSONArray("regionIds")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
            } ?: emptyList(),
            points = parsePoints(obj.optJSONArray("points")),
            polygons = parsePolygons(obj.optJSONArray("polygons")),
        )
    }

    private fun write(entry: MissionEntry): JSONObject = JSONObject().apply {
        put("nMssn", entry.nMssn)
        put("startTime", entry.startTime ?: 0)
        put("durationMin", entry.durationMin ?: 0)
        put("sqft", entry.sqft ?: 0)
        put("result", entry.result)
        put("ok", entry.ok)
        put("initiator", entry.initiator)
        put("pmapId", entry.pmapId)
        put("pmapvId", entry.pmapvId)
        put("regionIds", JSONArray().apply { entry.regionIds.forEach { put(it) } })
        put("points", JSONArray().apply {
            entry.points.forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) }
        })
        put("polygons", JSONArray().apply {
            entry.polygons.forEach { ring ->
                put(JSONArray().apply {
                    ring.forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) }
                })
            }
        })
    }

    private fun parsePoints(array: JSONArray?): List<Pair<Float, Float>> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val p = array.optJSONArray(i) ?: return@mapNotNull null
            if (p.length() < 2) return@mapNotNull null
            p.optDouble(0).toFloat() to p.optDouble(1).toFloat()
        }
    }

    private fun parsePolygons(array: JSONArray?): List<List<Pair<Float, Float>>> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val ring = array.optJSONArray(i) ?: return@mapNotNull null
            val points = parsePoints(ring)
            if (points.size < 3) null else points
        }
    }
}
