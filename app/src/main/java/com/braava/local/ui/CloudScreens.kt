package com.braava.local.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.braava.local.data.model.CloudMap
import com.braava.local.data.model.MissionEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatSqftToM2(sqft: Int?): String? =
    sqft?.let { "%.1f м²".format(it * 0.092903) }

private fun formatEpoch(epoch: Long?): String {
    if (epoch == null || epoch <= 0) return "—"
    val millis = if (epoch < 100_000_000_000L) epoch * 1000 else epoch
    return runCatching {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(millis))
    }.getOrElse { "—" }
}

@Composable
private fun LoginForm(
    busy: Boolean,
    submitLabel: String,
    onSubmit: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("E-mail") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Пароль") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onSubmit(username, password) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(submitLabel)
    }
}

@Composable
fun CoveragePlot(
    points: List<Pair<Float, Float>>,
    polygons: List<List<Pair<Float, Float>>>,
    height: androidx.compose.ui.unit.Dp = 280.dp,
) {
    val pointColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val outlineColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        drawRect(color = background)
        val all = points + polygons.flatten()
        if (all.isEmpty()) return@Canvas
        val minX = all.minOf { it.first }
        val maxX = all.maxOf { it.first }
        val minY = all.minOf { it.second }
        val maxY = all.maxOf { it.second }
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val pad = 16f
        val scale = minOf(
            (size.width - 2 * pad) / spanX,
            (size.height - 2 * pad) / spanY,
        )
        fun toOffset(point: Pair<Float, Float>): Offset = Offset(
            pad + (point.first - minX) * scale,
            size.height - pad - (point.second - minY) * scale,
        )
        polygons.forEach { ring ->
            val path = Path()
            ring.forEachIndexed { index, point ->
                val offset = toOffset(point)
                if (index == 0) path.moveTo(offset.x, offset.y)
                else path.lineTo(offset.x, offset.y)
            }
            path.close()
            drawPath(path, color = fillColor)
            drawPath(path, color = outlineColor, style = Stroke(width = 2f))
        }
        points.forEach { point ->
            val offset = toOffset(point)
            drawRect(
                color = pointColor,
                topLeft = Offset(offset.x - 2f, offset.y - 2f),
                size = Size(4f, 4f),
            )
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Переименовать") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

// ---------------------------------------------------------------- maps

@Composable
fun MapsDialogs(viewModel: MainViewModel, onClose: () -> Unit) {
    val maps by viewModel.cloudMaps.collectAsStateWithLifecycle()
    val selected by viewModel.selectedCloudMap.collectAsStateWithLifecycle()
    val busy by viewModel.cloudMapBusy.collectAsStateWithLifecycle()
    val status by viewModel.cloudMapStatus.collectAsStateWithLifecycle()
    viewModel.mapNames.collectAsStateWithLifecycle()
    viewModel.history.collectAsStateWithLifecycle()

    var showLogin by remember { mutableStateOf(maps.isEmpty()) }
    var selectedRooms by remember(selected?.versionId) { mutableStateOf(emptySet<String>()) }
    var renameMapTarget by remember { mutableStateOf<CloudMap?>(null) }
    var renameRoomTarget by remember { mutableStateOf<Pair<CloudMap, String>?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
        title = { Text("Карты") },
        text = {
            Column(
                Modifier
                    .height(460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (maps.isNotEmpty() && !showLogin) {
                    TextButton(onClick = { showLogin = true }) { Text("Обновить из облака") }
                }
                if (showLogin || maps.isEmpty()) {
                    LoginForm(
                        busy = busy,
                        submitLabel = if (maps.isEmpty()) "Загрузить карты" else "Обновить",
                        onSubmit = { u, p -> viewModel.refreshCloudMaps(u, p); showLogin = false },
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
                status?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                if (maps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    maps.forEach { map ->
                        Card(
                            onClick = { viewModel.selectCloudMap(map) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (map.versionId == selected?.versionId) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        viewModel.mapTitle(map),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        map.created.ifBlank { "—" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { renameMapTarget = map }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Переименовать")
                                }
                            }
                        }
                    }
                }
            }
        },
    )

    if (selected != null) {
        MapDetailDialog(
            viewModel = viewModel,
            map = selected!!,
            selectedRooms = selectedRooms,
            onToggleRoom = { id ->
                selectedRooms = if (id in selectedRooms) selectedRooms - id else selectedRooms + id
            },
            onRenameRoom = { regionId -> renameRoomTarget = selected!! to regionId },
            onClose = { viewModel.clearSelectedCloudMap() },
        )
    }

    renameMapTarget?.let { target ->
        RenameDialog(
            initial = viewModel.mapTitle(target),
            onDismiss = { renameMapTarget = null },
            onConfirm = {
                viewModel.renameMap(target.pmapId, it)
                renameMapTarget = null
            },
        )
    }
    renameRoomTarget?.let { (map, regionId) ->
        val region = map.regions.firstOrNull { it.id == regionId }
        RenameDialog(
            initial = region?.let { viewModel.roomTitle(map.pmapId, it) } ?: "",
            onDismiss = { renameRoomTarget = null },
            onConfirm = {
                viewModel.renameRoom(map.pmapId, regionId, it)
                renameRoomTarget = null
            },
        )
    }
}

@Composable
private fun MapDetailDialog(
    viewModel: MainViewModel,
    map: CloudMap,
    selectedRooms: Set<String>,
    onToggleRoom: (String) -> Unit,
    onRenameRoom: (String) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Назад") } },
        title = { Text(viewModel.mapTitle(map)) },
        text = {
            Column(
                Modifier
                    .height(430.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val (displayPoints, displayPolygons) = viewModel.mapGeometryForDisplay(map)
                if (displayPoints.isNotEmpty() || displayPolygons.isNotEmpty()) {
                    CoveragePlot(displayPoints, displayPolygons, height = 200.dp)
                } else {
                    Text(
                        "Геометрия карты не загружена. Нажмите «Обновить» в списке карт.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.command("start") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Убрать всё (обычный старт)")
                }

                if (map.regions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Комнаты",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { viewModel.cleanRooms(selectedRooms.toList()) },
                            enabled = selectedRooms.isNotEmpty(),
                        ) {
                            Text("Убрать выбранные")
                        }
                    }
                    map.regions.forEach { region ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = region.id in selectedRooms,
                                onCheckedChange = { onToggleRoom(region.id) },
                            )
                            Text(
                                viewModel.roomTitle(map.pmapId, region),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onRenameRoom(region.id) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Переименовать")
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Облако не вернуло комнаты для этой карты.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

// ---------------------------------------------------------------- history

@Composable
fun HistoryDialogs(viewModel: MainViewModel, onClose: () -> Unit) {
    val entries by viewModel.history.collectAsStateWithLifecycle()
    val selected by viewModel.selectedMission.collectAsStateWithLifecycle()
    val busy by viewModel.cloudMapBusy.collectAsStateWithLifecycle()
    val status by viewModel.historyStatus.collectAsStateWithLifecycle()
    viewModel.mapNames.collectAsStateWithLifecycle()
    viewModel.roomNames.collectAsStateWithLifecycle()

    var showLogin by remember { mutableStateOf(entries.isEmpty()) }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
        title = { Text("История уборок") },
        text = {
            Column(
                Modifier
                    .height(460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (entries.isNotEmpty() && !showLogin) {
                    TextButton(onClick = { showLogin = true }) { Text("Обновить из облака") }
                }
                if (showLogin || entries.isEmpty()) {
                    LoginForm(
                        busy = busy,
                        submitLabel = if (entries.isEmpty()) "Загрузить историю" else "Обновить",
                        onSubmit = { u, p -> viewModel.refreshHistory(u, p); showLogin = false },
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
                status?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                if (entries.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    entries.forEach { entry ->
                        Card(
                            onClick = { viewModel.selectMission(entry) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (entry.nMssn == selected?.nMssn) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    formatEpoch(entry.startTime),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    buildString {
                                        append(entry.result)
                                        entry.durationMin?.let { append("  •  $it мин") }
                                        formatSqftToM2(entry.sqft)?.let { append("  •  $it") }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val mapName = viewModel.historyMapName(entry.pmapId)
                                val rooms = viewModel.historyRoomNames(entry.pmapId, entry.regionIds)
                                if (mapName != null || rooms.isNotEmpty()) {
                                    Text(
                                        buildString {
                                            mapName?.let { append("Карта: $it") }
                                            if (rooms.isNotEmpty()) {
                                                if (isNotEmpty()) append("  •  ")
                                                append(rooms.joinToString(", "))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )

    if (selected != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearSelectedMission() },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSelectedMission() }) { Text("Назад") }
            },
            title = { Text(formatEpoch(selected!!.startTime)) },
            text = {
                Column(
                    Modifier
                        .height(430.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        buildString {
                            append(selected!!.result)
                            selected!!.durationMin?.let { append("  •  $it мин") }
                            formatSqftToM2(selected!!.sqft)?.let { append("  •  $it") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val mapName = viewModel.historyMapName(selected!!.pmapId)
                    val rooms = viewModel.historyRoomNames(selected!!.pmapId, selected!!.regionIds)
                    if (mapName != null || rooms.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            buildString {
                                mapName?.let { append("Карта: $it") }
                                if (rooms.isNotEmpty()) {
                                    if (isNotEmpty()) append("  •  ")
                                    append(rooms.joinToString(", "))
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (selected!!.hasGeometry) {
                        CoveragePlot(selected!!.points, selected!!.polygons)
                    } else {
                        Text(
                            "Карта этой уборки не загружена. Обновите из облака.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
        )
    }
}
