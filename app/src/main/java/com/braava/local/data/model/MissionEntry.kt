package com.braava.local.data.model

/** One run from the cloud mission history. */
data class MissionEntry(
    val nMssn: Int,
    val startTime: Long?,
    val durationMin: Int?,
    val sqft: Int?,
    val result: String,
    val ok: Boolean,
    val initiator: String?,
    val pmapId: String?,
    val pmapvId: String?,
    val regionIds: List<String> = emptyList(),
    val points: List<Pair<Float, Float>> = emptyList(),
    val polygons: List<List<Pair<Float, Float>>> = emptyList(),
) {
    val hasGeometry: Boolean get() = points.isNotEmpty() || polygons.isNotEmpty()
}
