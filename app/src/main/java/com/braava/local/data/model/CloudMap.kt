package com.braava.local.data.model

data class CloudRegion(
    val id: String,
    val name: String,
)

/** A room polygon (in mm) plus an optional iRobot-suggested type. */
data class CloudRoom(
    val regionId: String,
    val polygon: List<Pair<Float, Float>>,
    val suggestedType: String? = null,
)

/** A cloud map: metadata, coverage geometry and its rooms. */
data class CloudMap(
    val pmapId: String,
    val versionId: String,
    val name: String,
    val created: String,
    val regions: List<CloudRegion> = emptyList(),
    val points: List<Pair<Float, Float>> = emptyList(),
    val polygons: List<List<Pair<Float, Float>>> = emptyList(),
    val rooms: List<CloudRoom> = emptyList(),
    val walls: List<List<Pair<Float, Float>>> = emptyList(),
    val clutter: List<List<Pair<Float, Float>>> = emptyList(),
    val dock: Pair<Float, Float>? = null,
    val startPose: Pair<Float, Float>? = null,
    val rawUmf: String = "",
) {
    val hasGeometry: Boolean get() = points.isNotEmpty() || polygons.isNotEmpty()
}
