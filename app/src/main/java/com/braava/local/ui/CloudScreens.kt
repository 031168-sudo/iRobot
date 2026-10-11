package com.braava.local.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
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

private data class MapBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
)

/** Ray-casting hit test for a room polygon. */
private fun pointInRing(x: Float, y: Float, ring: List<Pair<Float, Float>>): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val xi = ring[i].first
        val yi = ring[i].second
        val xj = ring[j].first
        val yj = ring[j].second
        if ((yi > y) != (yj > y) &&
            x < (xj - xi) * (y - yi) / (yj - yi) + xi
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/** A recognisable charging base, drawn top-down. */
private fun DrawScope.drawDock(center: Offset, color: androidx.compose.ui.graphics.Color) {
    val w = 36f
    val h = 26f
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - w / 2, center.y - h / 2),
        size = Size(w, h),
        cornerRadius = CornerRadius(7f, 7f),
    )
    drawRoundRect(
        color = androidx.compose.ui.graphics.Color.White,
        topLeft = Offset(center.x - w / 2 + 6f, center.y - h / 2 + 5f),
        size = Size(w - 12f, h - 10f),
        cornerRadius = CornerRadius(4f, 4f),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - 7f, center.y - 3f),
        size = Size(3.5f, 10f),
        cornerRadius = CornerRadius(1.5f, 1.5f),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x + 3.5f, center.y - 3f),
        size = Size(3.5f, 10f),
        cornerRadius = CornerRadius(1.5f, 1.5f),
    )
}

@Composable
fun CoveragePlot(
    points: List<Pair<Float, Float>>,
    polygons: List<List<Pair<Float, Float>>>,
    rooms: List<com.braava.local.data.model.CloudRoom> = emptyList(),
    walls: List<List<Pair<Float, Float>>> = emptyList(),
    clutter: List<List<Pair<Float, Float>>> = emptyList(),
    dock: Pair<Float, Float>? = null,
    start: Pair<Float, Float>? = null,
    roomColors: Map<String, androidx.compose.ui.graphics.Color> = emptyMap(),
    roomLabels: Map<String, String> = emptyMap(),
    selectedRoomIds: Set<String> = emptySet(),
    onRoomTap: ((String?) -> Unit)? = null,
    onRoomLongPress: ((String) -> Unit)? = null,
    height: androidx.compose.ui.unit.Dp = 280.dp,
) {
    val pointColor = MaterialTheme.colorScheme.primary
    val wallColor = androidx.compose.ui.graphics.Color(0xFF455A64)
    val background = MaterialTheme.colorScheme.surfaceVariant
    val dockColor = androidx.compose.ui.graphics.Color(0xFF2E7D32)
    val startColor = androidx.compose.ui.graphics.Color(0xFFE53935)
    val selectionColor = MaterialTheme.colorScheme.primary

    val bounds = remember(points, polygons, rooms, walls, clutter, dock, start) {
        val all = buildList {
            addAll(points)
            polygons.forEach { addAll(it) }
            rooms.forEach { addAll(it.polygon) }
            walls.forEach { addAll(it) }
            clutter.forEach { addAll(it) }
            dock?.let { add(it) }
            start?.let { add(it) }
        }
        if (all.isEmpty()) {
            null
        } else {
            MapBounds(
                minX = all.minOf { it.first },
                maxX = all.maxOf { it.first },
                minY = all.minOf { it.second },
                maxY = all.maxOf { it.second },
            )
        }
    }

    val currentOnTap by rememberUpdatedState(onRoomTap)
    val currentOnLongPress by rememberUpdatedState(onRoomLongPress)
    val interactive = bounds != null && rooms.isNotEmpty() &&
        (onRoomTap != null || onRoomLongPress != null)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (interactive) {
                    Modifier.pointerInput(bounds, rooms) {
                        val b = bounds ?: return@pointerInput
                        val pad = 16f
                        val spanX = (b.maxX - b.minX).coerceAtLeast(1f)
                        val spanY = (b.maxY - b.minY).coerceAtLeast(1f)
                        val scale = minOf(
                            (size.width - 2 * pad) / spanX,
                            (size.height - 2 * pad) / spanY,
                        )
                        fun hit(offset: Offset): com.braava.local.data.model.CloudRoom? {
                            val wx = b.minX + (offset.x - pad) / scale
                            val wy = b.minY + (size.height - pad - offset.y) / scale
                            return rooms.lastOrNull { pointInRing(wx, wy, it.polygon) }
                        }
                        detectTapGestures(
                            onTap = { offset ->
                                currentOnTap?.invoke(hit(offset)?.regionId)
                            },
                            onLongPress = { offset ->
                                hit(offset)?.let { currentOnLongPress?.invoke(it.regionId) }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        drawRect(color = background)
        val b = bounds ?: return@Canvas
        val spanX = (b.maxX - b.minX).coerceAtLeast(1f)
        val spanY = (b.maxY - b.minY).coerceAtLeast(1f)
        val pad = 16f
        val scale = minOf(
            (size.width - 2 * pad) / spanX,
            (size.height - 2 * pad) / spanY,
        )
        fun toOffset(point: Pair<Float, Float>): Offset = Offset(
            pad + (point.first - b.minX) * scale,
            size.height - pad - (point.second - b.minY) * scale,
        )
        fun buildPath(ring: List<Pair<Float, Float>>): Path {
            val path = Path()
            ring.forEachIndexed { index, point ->
                val offset = toOffset(point)
                if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
            }
            path.close()
            return path
        }

        // Cleaned-area outline (coverage_poly)
        polygons.forEach { ring ->
            val path = buildPath(ring)
            drawPath(path, color = pointColor.copy(alpha = 0.20f))
            drawPath(path, color = pointColor, style = Stroke(width = 1.5f))
        }

        // Walls
        walls.forEach { ring ->
            drawPath(buildPath(ring), color = wallColor, style = Stroke(width = 3f))
        }

        // Clutter / obstacles the robot avoided
        clutter.forEach { ring ->
            drawPath(buildPath(ring), color = androidx.compose.ui.graphics.Color(0xFF546E7A))
        }

        // Rooms, each with its own colour; selected rooms stand out.
        rooms.forEachIndexed { index, room ->
            val base = roomColors[room.regionId] ?: roomColor(index)
            val selected = room.regionId in selectedRoomIds
            val path = buildPath(room.polygon)
            drawPath(path, color = base.copy(alpha = if (selected) 0.55f else 0.22f))
            drawPath(
                path,
                color = if (selected) selectionColor else base,
                style = Stroke(width = if (selected) 5f else 2f),
            )
        }

        // Coverage points
        points.forEach { point ->
            val offset = toOffset(point)
            drawRect(
                color = pointColor,
                topLeft = Offset(offset.x - 1.5f, offset.y - 1.5f),
                size = Size(3f, 3f),
            )
        }

        // Room names drawn on the map, in the room's own colour
        if (rooms.isNotEmpty() && roomLabels.isNotEmpty()) {
            drawIntoCanvas { canvas ->
                val fill = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textSize = 30f
                }
                val halo = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textSize = 30f
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 6f
                    color = android.graphics.Color.argb(200, 20, 20, 20)
                }
                rooms.forEachIndexed { index, room ->
                    val label = roomLabels[room.regionId] ?: return@forEachIndexed
                    if (label.isBlank() || room.polygon.isEmpty()) return@forEachIndexed
                    val cx = room.polygon.sumOf { it.first.toDouble() }.toFloat() / room.polygon.size
                    val cy = room.polygon.sumOf { it.second.toDouble() }.toFloat() / room.polygon.size
                    val o = toOffset(cx to cy)
                    fill.color = (roomColors[room.regionId] ?: roomColor(index)).toArgb()
                    canvas.nativeCanvas.drawText(label, o.x, o.y, halo)
                    canvas.nativeCanvas.drawText(label, o.x, o.y, fill)
                }
            }
        }

        // Start marker (red) and charging base
        start?.let {
            val o = toOffset(it)
            drawCircle(color = startColor, radius = 7f, center = o)
            drawCircle(color = androidx.compose.ui.graphics.Color.White, radius = 3f, center = o)
        }
        dock?.let { drawDock(toOffset(it), dockColor) }
    }
}

private val ROOM_COLORS = listOf(
    androidx.compose.ui.graphics.Color(0xFF1E88E5),
    androidx.compose.ui.graphics.Color(0xFFE53935),
    androidx.compose.ui.graphics.Color(0xFF43A047),
    androidx.compose.ui.graphics.Color(0xFFFB8C00),
    androidx.compose.ui.graphics.Color(0xFF8E24AA),
    androidx.compose.ui.graphics.Color(0xFF00ACC1),
    androidx.compose.ui.graphics.Color(0xFF6D4C41),
    androidx.compose.ui.graphics.Color(0xFF3949AB),
)

fun roomColor(index: Int): androidx.compose.ui.graphics.Color =
    ROOM_COLORS[index % ROOM_COLORS.size]

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
            onClearRooms = { selectedRooms = emptySet() },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapDetailDialog(
    viewModel: MainViewModel,
    map: CloudMap,
    selectedRooms: Set<String>,
    onToggleRoom: (String) -> Unit,
    onClearRooms: () -> Unit,
    onRenameRoom: (String) -> Unit,
    onClose: () -> Unit,
) {
    val status by viewModel.cloudMapStatus.collectAsStateWithLifecycle()
    val busy by viewModel.cloudMapBusy.collectAsStateWithLifecycle()
    val roomColors = map.regions
        .mapIndexed { index, region -> region.id to roomColor(index) }
        .toMap()
    val roomLabels = map.regions.associate { it.id to viewModel.roomTitle(map.pmapId, it) }
    val (displayPoints, displayPolygons) = viewModel.mapGeometryForDisplay(map)
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
        title = { Text(viewModel.mapTitle(map)) },
        text = {
            Column(
                Modifier
                    .height(500.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (displayPoints.isNotEmpty() || displayPolygons.isNotEmpty() ||
                    map.rooms.isNotEmpty() || map.walls.isNotEmpty()
                ) {
                    CoveragePlot(
                        points = displayPoints,
                        polygons = displayPolygons,
                        rooms = map.rooms,
                        walls = map.walls,
                        clutter = map.clutter,
                        dock = map.dock,
                        start = map.startPose,
                        roomColors = roomColors,
                        roomLabels = roomLabels,
                        selectedRoomIds = selectedRooms,
                        onRoomTap = { id ->
                            if (id == null) onClearRooms() else onToggleRoom(id)
                        },
                        onRoomLongPress = { id -> onRenameRoom(id) },
                        height = 300.dp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Нажмите комнату, чтобы выбрать уборку. Долгое нажатие — переименовать.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Геометрия карты не загружена. Нажмите «Обновить из облака» в списке карт.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator()
                }
                status?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.cleanRooms(selectedRooms.toList()) },
                    enabled = selectedRooms.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (selectedRooms.isEmpty()) {
                            "Выберите комнату на карте"
                        } else {
                            "Убрать выбранные (${selectedRooms.size})"
                        },
                    )
                }
                TextButton(
                    onClick = { viewModel.command("start") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Убрать всё")
                }

                if (map.regions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Комнаты",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        map.regions.forEach { region ->
                            FilterChip(
                                selected = region.id in selectedRooms,
                                onClick = { onToggleRoom(region.id) },
                                label = {
                                    Text(
                                        viewModel.roomTitle(map.pmapId, region),
                                        color = roomColors[region.id]
                                            ?: MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                            )
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
    val plans by viewModel.cloudMaps.collectAsStateWithLifecycle()
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
                        val plan = plans.firstOrNull { it.pmapId == selected!!.pmapId }
                        CoveragePlot(
                            points = selected!!.points,
                            polygons = selected!!.polygons,
                            clutter = plan?.clutter ?: emptyList(),
                        )
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
