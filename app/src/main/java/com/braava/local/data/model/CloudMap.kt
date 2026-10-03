package com.braava.local.data.model

data class CloudRegion(
    val id: String,
    val name: String,
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
) {
    val hasGeometry: Boolean get() = points.isNotEmpty() || polygons.isNotEmpty()
}
