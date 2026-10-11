package com.braava.local.data

import android.content.Context
import com.braava.local.data.model.CloudMap
import com.braava.local.data.model.CloudRegion
import com.braava.local.data.model.CloudRoom
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device cache of cloud maps, so rooms/coverage can be viewed without
 * logging into iRobot every time. Cloud credentials are never stored.
 */
class CloudMapStore(context: Context) {

    private val file = File(context.filesDir, "cloud_maps.json")

    fun load(blid: String): List<CloudMap> {
        val root = readRoot() ?: return emptyList()
        val array = root.optJSONArray(blid) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parseMap)
        }
    }

    fun save(blid: String, maps: List<CloudMap>) {
        val root = readRoot() ?: JSONObject()
        val array = JSONArray()
        maps.forEach { array.put(writeMap(it)) }
        root.put(blid, array)
        runCatching { file.writeText(root.toString()) }
    }

    private fun readRoot(): JSONObject? {
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun parseMap(obj: JSONObject): CloudMap? {
        val pmapId = obj.optString("pmapId")
        val versionId = obj.optString("versionId")
        if (pmapId.isBlank() || versionId.isBlank()) return null
        val regions = obj.optJSONArray("regions")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let {
                    CloudRegion(it.optString("id"), it.optString("name"))
                }
            }
        } ?: emptyList()
        return CloudMap(
            pmapId = pmapId,
            versionId = versionId,
            name = obj.optString("name").ifBlank { obj.optString("label") },
            created = obj.optString("created"),
            regions = regions,
            points = parsePoints(obj.optJSONArray("points")),
            polygons = parsePolygons(obj.optJSONArray("polygons")),
            rooms = parseRooms(obj.optJSONArray("rooms")),
            walls = parsePolygons(obj.optJSONArray("walls")),
            clutter = parsePolygons(obj.optJSONArray("clutter")),
            dock = parsePoint(obj.optJSONArray("dock")),
            startPose = parsePoint(obj.optJSONArray("startPose")),
            rawUmf = obj.optString("rawUmf"),
        )
    }

    private fun writeMap(map: CloudMap): JSONObject = JSONObject().apply {
        put("pmapId", map.pmapId)
        put("versionId", map.versionId)
        put("name", map.name)
        put("created", map.created)
        put("rawUmf", map.rawUmf)
        put("dock", map.dock?.let { JSONArray().put(it.first.toDouble()).put(it.second.toDouble()) })
        put("startPose", map.startPose?.let { JSONArray().put(it.first.toDouble()).put(it.second.toDouble()) })
        put("rooms", JSONArray().apply {
            map.rooms.forEach { room ->
                put(JSONObject().apply {
                    put("regionId", room.regionId)
                    put("suggestedType", room.suggestedType)
                    put("polygon", JSONArray().apply {
                        room.polygon.forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) }
                    })
                })
            }
        })
        put("walls", JSONArray().apply {
            map.walls.forEach { ring ->
                put(JSONArray().apply {
                    ring.forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) }
                })
            }
        })
        put("clutter", JSONArray().apply {
            map.clutter.forEach { ring ->
                put(JSONArray().apply {
                    ring.forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) }
                })
            }
        })
        put("regions", JSONArray().apply {
            map.regions.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        })
        put("points", JSONArray().apply {
            map.points.forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) }
        })
        put("polygons", JSONArray().apply {
            map.polygons.forEach { ring ->
                put(JSONArray().apply {
                    ring.forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) }
                })
            }
        })
    }

    private fun parsePoints(array: JSONArray?): List<Pair<Float, Float>> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val point = array.optJSONArray(i) ?: return@mapNotNull null
            if (point.length() < 2) return@mapNotNull null
            point.optDouble(0).toFloat() to point.optDouble(1).toFloat()
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

    private fun parseRooms(array: JSONArray?): List<CloudRoom> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val regionId = obj.optString("regionId")
            val polygon = parsePoints(obj.optJSONArray("polygon"))
            if (regionId.isBlank() || polygon.size < 3) {
                null
            } else {
                CloudRoom(
                    regionId = regionId,
                    polygon = polygon,
                    suggestedType = obj.optString("suggestedType").takeIf { it.isNotBlank() },
                )
            }
        }
    }

    private fun parsePoint(array: JSONArray?): Pair<Float, Float>? {
        if (array == null || array.length() < 2) return null
        return array.optDouble(0).toFloat() to array.optDouble(1).toFloat()
    }
}
