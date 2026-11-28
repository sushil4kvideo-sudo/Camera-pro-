package com.hinnka.mycamera.camera

import org.json.JSONArray
import org.json.JSONObject

/** Dedicated log category opened when ultra-wide Position Lock cannot start. */
const val POSITION_LOCK_ULTRA_WIDE_DIAGNOSTIC_TAG = "PositionLockUltraWide"

/** Gyroscope-driven pan behaviours available from the Top-Off preset picker. */
enum class GyroPanMode(val description: String) {
    POSITION_LOCK("Compensates phone yaw and pitch to keep framing fixed."),
    ANTI_SHAKE("Rejects quick hand tremors while allowing slow intentional movement.")
}

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
    val gyroMode: GyroPanMode? = null,
    val aiSubjectTracking: Boolean = false,
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
        const val BUILTIN_GYRO_POSITION_LOCK_ID = "builtin_gyro_position_lock"
        const val BUILTIN_AI_SUBJECT_TRACKING_ID = "builtin_ai_subject_tracking"

        private fun gyroPreset(id: String, name: String, mode: GyroPanMode) = TopOffPanPreset(
            id = id,
            name = name,
            points = listOf(TopOffPanWaypoint(0f, 0f, 2f), TopOffPanWaypoint(0f, 0f, 2f)),
            segmentDurationMs = 0L,
            gyroMode = mode,
        )

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
            gyroPreset(BUILTIN_GYRO_POSITION_LOCK_ID, "Gyro Position Lock", GyroPanMode.POSITION_LOCK),
            TopOffPanPreset(
                id = BUILTIN_AI_SUBJECT_TRACKING_ID,
                name = "AI Subject Tracking",
                points = listOf(
                    TopOffPanWaypoint(0f, 0f, 2f),
                    TopOffPanWaypoint(0f, 0f, 2f),
                ),
                segmentDurationMs = 0L,
                aiSubjectTracking = true,
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
                    .put("gyroMode", preset.gyroMode?.name ?: JSONObject.NULL)
                    .put("aiSubjectTracking", preset.aiSubjectTracking)
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
                    val presetId = item.optString("id", "custom-$index")
                    // Drop the removed built-in and any saved preset that used its gyro mode.
                    if (presetId == "builtin_gyro_anti_shake" ||
                        item.optString("gyroMode", "").equals("ANTI_SHAKE", ignoreCase = true)
                    ) continue
                    if (presetId in setOf(
                            "builtin_gyro_horizon_lock",
                            "builtin_gyro_smooth_follow",
                            "builtin_gyro_axis_balance",
                        )
                    ) continue
                    val pointsJson = item.optJSONArray("points") ?: JSONArray()
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
                    val gyroMode = runCatching {
                        GyroPanMode.valueOf(item.optString("gyroMode", ""))
                    }.getOrNull()
                    if (points.size < 2 && gyroMode == null) continue
                    val safePoints = if (points.size >= 2) points else listOf(
                        TopOffPanWaypoint(0f, 0f, 2f),
                        TopOffPanWaypoint(0f, 0f, 2f),
                    )
                    add(
                        TopOffPanPreset(
                            id = presetId,
                            name = item.optString("name", "Custom pan"),
                            points = safePoints,
                            segmentDurationMs = item.optLong("segmentDurationMs", 1_000L),
                            gyroMode = gyroMode,
                            aiSubjectTracking = item.optBoolean("aiSubjectTracking", false),
                        ).normalized()
                    )
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }
}
