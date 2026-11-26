package com.hinnka.mycamera.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.camera.TopOffPanPreset
import com.hinnka.mycamera.camera.TopOffPanWaypoint
import com.hinnka.mycamera.viewmodel.CameraViewModel
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun TopOffPanPresetSettings(
    viewModel: CameraViewModel,
    activeArrayWidth: Int,
    activeArrayHeight: Int,
    sensorOrientationDegrees: Int,
) {
    val joystickSpeed by viewModel.topOffJoystickSpeed.collectAsState()
    val showLatency by viewModel.topOffShowLatency.collectAsState()
    val latencyMs by viewModel.topOffPanLatencyMs.collectAsState()
    val savedPresets by viewModel.topOffPanPresets.collectAsState()

    var speedUi by remember(joystickSpeed) { mutableFloatStateOf(joystickSpeed) }
    var presetName by remember { mutableStateOf("") }
    var points by remember { mutableStateOf<List<TopOffPanWaypoint>>(emptyList()) }
    var selectedPointIndex by remember { mutableIntStateOf(-1) }
    var segmentDurationMs by remember { mutableFloatStateOf(1_500f) }
    var editingPresetId by remember { mutableStateOf<String?>(null) }

    val sensorWidth = activeArrayWidth.takeIf { it > 0 } ?: 4_000
    val sensorHeight = activeArrayHeight.takeIf { it > 0 } ?: 3_000
    val axesSwapped = Math.floorMod(sensorOrientationDegrees, 180) == 90
    val displayWidth = if (axesSwapped) sensorHeight else sensorWidth
    val displayHeight = if (axesSwapped) sensorWidth else sensorHeight
    val displayAspect = (displayWidth.toFloat() / displayHeight.toFloat()).coerceIn(0.2f, 2.5f)

    fun updateSelectedPoint(transform: (TopOffPanWaypoint) -> TopOffPanWaypoint) {
        val index = selectedPointIndex
        if (index !in points.indices) return
        points = points.toMutableList().also { list ->
            list[index] = transform(list[index]).normalized()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Joystick speed · ${String.format("%.2f", speedUi)}×",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Slider(
            value = speedUi,
            onValueChange = { speedUi = it },
            onValueChangeFinished = { viewModel.setTopOffJoystickSpeed(speedUi) },
            valueRange = 0.25f..4f,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show control latency", color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    "Displays the latest input-to-camera-request processing time, including 0.0 ms.",
                    color = Color.White.copy(alpha = 0.64f),
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = showLatency,
                onCheckedChange = viewModel::setTopOffShowLatency,
            )
        }
        Text(
            text = "Last control latency: ${String.format("%.1f", latencyMs)} ms",
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 12.sp,
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = Color.White.copy(alpha = 0.12f),
        )

        Text("Custom pan path", color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(
            text = if (activeArrayWidth > 0 && activeArrayHeight > 0) {
                "Active sensor area: ${activeArrayWidth} × ${activeArrayHeight} px · orientation ${sensorOrientationDegrees}°"
            } else {
                "Camera sensor metadata unavailable; showing a 4:3 editing area."
            },
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
        )
        Text(
            "Tap inside the sensor box to add waypoints. Add two or more points to create a path.",
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(displayAspect)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF101B22))
                .border(1.dp, Color.White.copy(alpha = 0.44f), RoundedCornerShape(14.dp))
                .pointerInput(displayWidth, displayHeight, points.size) {
                    detectTapGestures { position ->
                        if (points.size >= TopOffPanPreset.MAX_POINTS) return@detectTapGestures
                        val x = (position.x / size.width).coerceIn(0f, 1f)
                        val y = (position.y / size.height).coerceIn(0f, 1f)
                        points = points + TopOffPanWaypoint(
                            panX = x * 2f - 1f,
                            panY = y * 2f - 1f,
                            zoomRatio = 2f,
                        )
                        selectedPointIndex = points.lastIndex
                    }
                },
        ) {
            val gridColor = Color.White.copy(alpha = 0.16f)
            for (index in 1..3) {
                val x = size.width * index / 4f
                val y = size.height * index / 4f
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }

            val centers = points.map { point ->
                Offset(
                    x = ((point.panX + 1f) * 0.5f) * size.width,
                    y = ((point.panY + 1f) * 0.5f) * size.height,
                )
            }
            centers.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = Color(0xFFFFC46B),
                    start = start,
                    end = end,
                    strokeWidth = 3.dp.toPx(),
                )
            }
            centers.forEachIndexed { index, center ->
                drawCircle(
                    color = if (index == selectedPointIndex) Color(0xFFFFC46B) else Color.White,
                    radius = if (index == selectedPointIndex) 10.dp.toPx() else 7.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    color = Color(0xFF182129),
                    radius = 3.dp.toPx(),
                    center = center,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${points.size} / ${TopOffPanPreset.MAX_POINTS} points", color = Color.White)
            TextButton(
                onClick = {
                    points = emptyList()
                    selectedPointIndex = -1
                },
            ) { Text("Clear points") }
        }
        if (points.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                points.forEachIndexed { index, _ ->
                    TextButton(onClick = { selectedPointIndex = index }) {
                        Text(
                            text = if (index == selectedPointIndex) "● ${index + 1}" else "${index + 1}",
                            color = if (index == selectedPointIndex) Color(0xFFFFC46B) else Color.White,
                        )
                    }
                }
            }
        }
        if (selectedPointIndex in points.indices) {
            val point = points[selectedPointIndex]
            Text(
                text = "Point ${selectedPointIndex + 1} · pan (${String.format("%.2f", point.panX)}, ${String.format("%.2f", point.panY)})",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 11.sp,
            )
            Text(
                text = "Crop zoom · ${String.format("%.1f", point.zoomRatio)}×",
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
            Slider(
                value = point.zoomRatio,
                onValueChange = { zoom ->
                    updateSelectedPoint { it.copy(zoomRatio = zoom) }
                },
                valueRange = 1f..10f,
            )
            TextButton(
                onClick = {
                    points = points.filterIndexed { index, _ -> index != selectedPointIndex }
                    selectedPointIndex = (selectedPointIndex - 1).coerceAtLeast(0)
                },
            ) { Text("Remove selected point") }
        }

        Text(
            text = "Time between points · ${segmentDurationMs.roundToInt()} ms",
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = segmentDurationMs,
            onValueChange = { segmentDurationMs = it },
            valueRange = 0f..10_000f,
        )
        Text(
            text = "0 ms jumps instantly. Higher values create slower camera movement.",
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 11.sp,
        )

        OutlinedTextField(
            value = presetName,
            onValueChange = { presetName = it.take(48) },
            label = { Text("Preset name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    val preset = TopOffPanPreset(
                        id = editingPresetId ?: UUID.randomUUID().toString(),
                        name = presetName.trim().ifBlank { "Custom pan" },
                        points = points,
                        segmentDurationMs = segmentDurationMs.roundToInt().toLong(),
                    ).normalized()
                    viewModel.saveTopOffPanPreset(preset)
                    editingPresetId = preset.id
                    presetName = preset.name
                },
                enabled = points.size >= 2 && presetName.length <= 48,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (editingPresetId == null) "Save preset" else "Update preset")
            }
            TextButton(
                onClick = {
                    editingPresetId = null
                    presetName = ""
                    points = emptyList()
                    selectedPointIndex = -1
                    segmentDurationMs = 1_500f
                },
            ) { Text("New") }
        }

        if (savedPresets.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = Color.White.copy(alpha = 0.12f),
            )
            Text("Saved custom presets", color = Color.White, fontWeight = FontWeight.SemiBold)
            savedPresets.forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(preset.name, color = Color.White, fontWeight = FontWeight.Medium)
                        Text(
                            "${preset.points.size} points · ${preset.segmentDurationMs} ms / segment",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.sp,
                        )
                    }
                    TextButton(
                        onClick = {
                            editingPresetId = preset.id
                            presetName = preset.name
                            points = preset.points
                            selectedPointIndex = 0
                            segmentDurationMs = preset.segmentDurationMs.toFloat()
                        },
                    ) { Text("Edit") }
                    TextButton(onClick = { viewModel.deleteTopOffPanPreset(preset.id) }) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}
