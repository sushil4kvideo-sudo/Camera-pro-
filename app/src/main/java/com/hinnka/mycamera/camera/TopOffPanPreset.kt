package com.hinnka.mycamera.camera

import org.json.JSONArray
import org.json.JSONObject

/** A pan waypoint in viewfinder-normalized coordinates and its requested crop magnification. */
data class TopOffPanWaypoint(
    val panX: Float,
    val panY: Float,
    val zoomRatio: Float,
) {
    fun normalized(): TopOffPanWaypoint = copy(
        panX = panX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f,
        panY = panY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f,
        zoomRatio = zoomRatio.takeIf { it.isFinite() }?.coerceIn(1f, TopOffPanPreset.MAX_ZOOM_RATIO) ?: 2f,
    )
}

/**
 * A user-defined Top-Off pan path. segmentDurationMs is the travel time from each
 * waypoint to the next; zero means jump immediately to the next waypoint.
 */
data class TopOffPanPreset(
    val id: String,
    val name: String,
    val points: List<TopOffPanWaypoint>,
    val segmentDurationMs: Long = 1_000L,
) {
    fun normalized(): TopOffPanPreset = copy(
        id = id.trim().ifBlank { "custom-pan" }.take(80),
        name = name.trim().ifBlank { "Custom pan" }.take(48),
        points = points.take(MAX_POINTS).map { it.normalized() },
        segmentDurationMs = segmentDurationMs.coerceIn(0L, 10_000L),
    )

    companion object {
        const val MAX_POINTS = 32
        const val MAX_ZOOM_RATIO = 10f
        const val BUILTIN_LEFT_RIGHT_ID = "builtin_left_right"
        const val BUILTIN_UP_DOWN_ID = "builtin_up_down"

        fun builtInPresets(): List<TopOffPanPreset> = listOf(
            TopOffPanPreset(
                id = BUILTIN_LEFT_RIGHT_ID,
                name = "Left ↔ Right",
                points = listOf(
                    TopOffPanWaypoint(-0.85f, 0f, 2f),
                    TopOffPanWaypoint(0.85f, 0f, 2f),
                ),
                segmentDurationMs = 1_500L,
            ),
            TopOffPanPreset(
                id = BUILTIN_UP_DOWN_ID,
                name = "Up ↕ Down",
                points = listOf(
                    TopOffPanWaypoint(0f, -0.85f, 2f),
                    TopOffPanWaypoint(0f, 0.85f, 2f),
                ),
                segmentDurationMs = 1_500L,
            ),
        )
    }
}

object TopOffPanPresetCodec {
    fun encode(presets: List<TopOffPanPreset>): String {
        val array = JSONArray()
        presets.take(64).forEach { item ->
            val preset = item.normalized()
            val points = JSONArray()
            preset.points.forEach { point ->
                points.put(
                    JSONObject()
                        .put("panX", point.panX.toDouble())
                        .put("panY", point.panY.toDouble())
                        .put("zoomRatio", point.zoomRatio.toDouble())
                )
            }
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("segmentDurationMs", preset.segmentDurationMs)
                    .put("points", points)
            )
        }
        return array.toString()
    }

    fun decode(value: String?): List<TopOffPanPreset> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length().coerceAtMost(64)) {
                    val item = array.optJSONObject(index) ?: continue
                    val pointsJson = item.optJSONArray("points") ?: continue
                    val points = buildList {
                        for (pointIndex in 0 until pointsJson.length().coerceAtMost(TopOffPanPreset.MAX_POINTS)) {
                            val point = pointsJson.optJSONObject(pointIndex) ?: continue
                            add(
                                TopOffPanWaypoint(
                                    panX = point.optDouble("panX", 0.0).toFloat(),
                                    panY = point.optDouble("panY", 0.0).toFloat(),
                                    zoomRatio = point.optDouble("zoomRatio", 2.0).toFloat(),
                                ).normalized()
                            )
                        }
                    }
                    if (points.size < 2) continue
                    add(
                        TopOffPanPreset(
                            id = item.optString("id", "custom-$index"),
                            name = item.optString("name", "Custom pan"),
                            points = points,
                            segmentDurationMs = item.optLong("segmentDurationMs", 1_000L),
                        ).normalized()
                    )
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }
}
