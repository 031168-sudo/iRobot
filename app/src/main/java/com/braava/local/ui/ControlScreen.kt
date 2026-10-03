package com.braava.local.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.braava.local.data.ConnectionStatus
import com.braava.local.data.RobotConfig
import com.braava.local.data.model.CloudMap
import com.braava.local.data.model.MissionEntry
import com.braava.local.data.model.RobotHealth
import com.braava.local.data.model.RobotState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(
    viewModel: MainViewModel,
    config: RobotConfig,
    modifier: Modifier = Modifier,
    onOpenManager: () -> Unit = {},
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val trail by viewModel.trail.collectAsStateWithLifecycle()
    val sensorDeltas by viewModel.sensorDeltas.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var healthOpen by remember { mutableStateOf(false) }
    var cloudMapOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(config.name ?: "Braava jet m6")
                        Text(
                            "${statusLabel(status)} • ${config.ip}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenManager) {
                        Icon(Icons.Filled.Settings, contentDescription = "Роботы")
                    }
                    IconButton(onClick = {
                        viewModel.disconnect()
                        viewModel.connect()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Переподключить")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Карта из облака") },
                            onClick = {
                                menuOpen = false
                                viewModel.openCloudMaps()
                                cloudMapOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("История уборок") },
                            onClick = {
                                menuOpen = false
                                viewModel.openHistory()
                                historyOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Здоровье") },
                            onClick = {
                                menuOpen = false
                                healthOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Журнал обмена") },
                            onClick = {
                                menuOpen = false
                                logOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Диагностика подключения") },
                            onClick = {
                                menuOpen = false
                                viewModel.runDiagnostics()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Забыть робота") },
                            onClick = {
                                menuOpen = false
                                viewModel.forget()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (status is ConnectionStatus.Connecting || status is ConnectionStatus.Disconnected) {
                Text(
                    "Подключение к ${config.ip}…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (status is ConnectionStatus.Error) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        (status as ConnectionStatus.Error).message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            ProblemCard(state)
            StatusCard(state, sensorDeltas)
            Spacer(Modifier.height(16.dp))
            PoseCard(state, trail)
            Spacer(Modifier.height(16.dp))

            Text("Управление", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Убрать", Icons.Filled.PlayArrow, Modifier.weight(1f)) {
                    viewModel.command("start")
                }
                ActionButton("Пауза", Icons.Filled.Pause, Modifier.weight(1f)) {
                    viewModel.command("pause")
                }
                ActionButton("Далее", Icons.Filled.SkipNext, Modifier.weight(1f)) {
                    viewModel.command("resume")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Стоп", Icons.Filled.Stop, Modifier.weight(1f)) {
                    viewModel.command("stop")
                }
                ActionButton("Домой", Icons.Filled.Home, Modifier.weight(1f)) {
                    viewModel.command("dock")
                }
                ActionButton("Найти", Icons.Filled.NotificationsActive, Modifier.weight(1f)) {
                    viewModel.command("find")
                }
            }

            Spacer(Modifier.height(20.dp))
            PreferencesCard(viewModel, state)

            Spacer(Modifier.height(20.dp))
            RawState(state)
            Spacer(Modifier.height(24.dp))
        }
    }

    diagnostics?.let { lines ->
        AlertDialog(
            onDismissRequest = { viewModel.clearDiagnostics() },
            confirmButton = {
                TextButton(onClick = { viewModel.clearDiagnostics() }) { Text("Закрыть") }
            },
            title = { Text("Диагностика подключения") },
            text = {
                Column {
                    lines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
        )
    }

    if (logOpen) {
        AlertDialog(
            onDismissRequest = { logOpen = false },
            confirmButton = {
                TextButton(onClick = { logOpen = false }) { Text("Закрыть") }
            },
            title = { Text("Журнал обмена") },
            text = {
                val scroll = rememberScrollState()
                LaunchedEffect(events.size) { scroll.scrollTo(scroll.maxValue) }
                Column(
                    Modifier
                        .height(420.dp)
                        .verticalScroll(scroll),
                ) {
                    if (events.isEmpty()) {
                        Text("Пока пусто", style = MaterialTheme.typography.bodySmall)
                    }
                    events.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
        )
    }

    if (healthOpen) {
        AlertDialog(
            onDismissRequest = { healthOpen = false },
            confirmButton = {
                TextButton(onClick = { healthOpen = false }) { Text("Закрыть") }
            },
            title = { Text("Здоровье") },
            text = { HealthContent(state?.health) },
        )
    }

    if (cloudMapOpen) {
        MapsDialogs(viewModel) {
            cloudMapOpen = false
            viewModel.resetCloudMap()
        }
    }

    if (historyOpen) {
        HistoryDialogs(viewModel) {
            historyOpen = false
            viewModel.resetHistory()
        }
    }
}

@Composable
private fun HealthContent(health: RobotHealth?) {
    if (health == null) {
        Text("Робот пока не прислал статистику", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val scroll = rememberScrollState()
    Column(
        Modifier
            .height(460.dp)
            .verticalScroll(scroll),
    ) {
        HealthSection(
            "Батарея",
            listOf(
                "Тип" to health.batteryType,
                "Производитель" to health.batteryId,
                "Дата выпуска" to health.batteryMfg,
                "Циклов зарядки" to health.chargeCycles?.toString(),
                "Оценка ёмкости" to health.estCapacityMah?.let { "$it мА·ч" },
                "Средняя зарядка" to health.avgChargeMin?.let { "$it мин" },
                "Часов на базе" to health.hoursOnDock?.toString(),
            ),
        )
        HealthSection(
            "Пробег",
            listOf(
                "Включён всего" to health.powerOnHours?.let { "$it ч" },
                "Время уборки" to runTimeLabel(health.runHours, health.runMinutes),
                "Площадь всего" to health.totalSqft?.let { "$it кв. фут" },
                "Застреваний" to health.stuck?.toString(),
                "Паник" to health.panics?.toString(),
                "Стопоров колеса" to health.wheelStalls?.toString(),
                "Датчик края Л" to health.cliffFront?.toString(),
                "Датчик края П" to health.cliffRight?.toString(),
            ),
        )
        HealthSection(
            "Миссии",
            listOf(
                "Всего" to health.missionsTotal?.toString(),
                "Успешно" to health.missionsOk?.toString(),
                "С ошибкой" to health.missionsFailed?.toString(),
                "Отменено" to health.missionsCancelled?.toString(),
                "Средняя миссия" to health.avgMissionMins?.let { "$it мин" },
                "Средний цикл" to health.avgCycleMins?.let { "$it мин" },
            ),
        )
        HealthSection(
            "Зарядки",
            listOf(
                "Успешных" to health.chargeOk?.toString(),
                "Ошибок" to health.chargeErrors?.toString(),
                "Сбоев контакта" to health.chargeChatters?.toString(),
                "Соскоков" to health.chargeKnockoffs?.toString(),
                "Быстрых отключений" to health.chargeLithF?.toString(),
            ),
        )
        HealthSection(
            "Кнопки",
            listOf(
                "Запусков (Clean)" to health.buttonClean?.toString(),
                "Возвратов (Dock)" to health.buttonDock?.toString(),
                "Точечных (Spot)" to health.buttonSpot?.toString(),
                "Нажатий бампера" to health.bumperPresses?.toString(),
                "Срабатываний подъёма" to health.wheelDrops?.toString(),
            ),
        )
        HealthSection(
            "Перезагрузки и сбои",
            listOf(
                "Навигация" to health.rebootNav?.toString(),
                "Ходовая часть" to health.rebootMobility?.toString(),
                "Безопасность" to health.rebootSafety?.toString(),
                "Загрузка карты" to health.rebootMapLoad?.toString(),
            ),
        )
        HealthSection(
            "Навигация",
            listOf(
                "ID миссии" to health.navMissionId,
                "Ориентиров" to health.navLandmarks?.toString(),
                "Хороших ориентиров" to health.navGoodLandmarks?.toString(),
                "Gain" to health.navGain?.toString(),
                "Expo" to health.navExpo?.toString(),
                "Статус карты" to health.navMapStatus,
            ),
        )
        HealthSection(
            "Устройство",
            listOf(
                "Серийный номер" to health.serial,
                "MAC Wi-Fi" to health.wlanMac,
                "Карт сохранено" to health.mapCount?.toString(),
            ),
        )
    }
}

@Composable
private fun HealthSection(title: String, rows: List<Pair<String, String?>>) {
    val present = rows.filter { it.second != null }
    if (present.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    present.forEach { (label, value) ->
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                label,
                Modifier.width(150.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun runTimeLabel(hours: Int?, minutes: Int?): String? {
    if (hours == null && minutes == null) return null
    val h = hours ?: 0
    val m = minutes ?: 0
    return if (h > 0) "$h ч $m мин" else "$m мин"
}

@Composable
private fun PoseCard(state: RobotState?, trail: List<Pair<Int, Int>>) {
    val x = state?.poseX
    val y = state?.poseY
    val theta = state?.poseTheta
    if (x == null && y == null && trail.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Позиция", style = MaterialTheme.typography.titleMedium)
            if (x != null && y != null) {
                InfoRow("Координаты", "$x, $y мм")
            }
            if (theta != null) {
                InfoRow("Курс", "$theta° (${compass(theta)})")
            }
            if (trail.size >= 2) {
                Spacer(Modifier.height(8.dp))
                TrailPlot(trail)
            } else if (x != null && y != null) {
                Text(
                    "Трек появится по мере движения",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ElapsedRow(state: RobotState?) {
    val phase = state?.phase
    val running = phase == "run" || phase == "resume"
    val paused = phase == "pause" || phase == "stop"
    val minutes = state?.missionMinutes
    val start = state?.missionStartEpoch

    if (running && start != null) {
        var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis() / 1000
                kotlinx.coroutines.delay(1000)
            }
        }
        val seconds = (now - start).coerceAtLeast(0)
        InfoRow("Прошло", "%d:%02d".format(seconds / 60, seconds % 60))
    } else if ((running || paused) && minutes != null && minutes > 0) {
        InfoRow("Время уборки", "$minutes мин")
    }
}

@Composable
private fun TrailPlot(trail: List<Pair<Int, Int>>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
    ) {
        drawRect(color = bgColor)
        val minX = trail.minOf { it.first }.toFloat()
        val maxX = trail.maxOf { it.first }.toFloat()
        val minY = trail.minOf { it.second }.toFloat()
        val maxY = trail.maxOf { it.second }.toFloat()
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val pad = 18f
        val scale = minOf(
            (size.width - 2 * pad) / spanX,
            (size.height - 2 * pad) / spanY,
        )
        fun toOffset(point: Pair<Int, Int>): Offset = Offset(
            pad + (point.first - minX) * scale,
            size.height - pad - (point.second - minY) * scale,
        )
        for (i in 1 until trail.size) {
            drawLine(
                color = lineColor,
                start = toOffset(trail[i - 1]),
                end = toOffset(trail[i]),
                strokeWidth = 4f,
            )
        }
        drawCircle(color = lineColor, radius = 9f, center = toOffset(trail.last()))
    }
}

private fun compass(theta: Int): String {
    val dirs = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
    val normalized = ((theta % 360) + 360) % 360
    val index = (((normalized + 22) / 45) % 8).coerceIn(0, 7)
    return dirs[index]
}

@Composable
private fun ProblemCard(state: RobotState?) {
    val errorCode = state?.errorCode
    val hasError = errorCode != null && errorCode != 0
    val readiness = state?.readiness
    if (!hasError && readiness == null) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (hasError) {
                Text(
                    "Ошибка $errorCode: ${state?.errorMessage ?: "неизвестно"}",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (readiness != null) {
                if (hasError) Spacer(Modifier.height(6.dp))
                Text(
                    "Не может начать: $readiness",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: RobotState?, sensorDeltas: List<Pair<String, Int>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                phaseLabel(state?.phase),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            val battery = state?.batteryPct
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Заряд", Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                Text(
                    battery?.let { "$it%" } ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(48.dp),
                )
                if (battery != null) {
                    LinearProgressIndicator(
                        progress = { battery / 100f },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            state?.cycle?.let { cycle ->
                if (cycle != "none") InfoRow("Режим", cycleLabel(cycle))
            }
            ElapsedRow(state)
            state?.missionArea?.let { area ->
                if (area > 0) InfoRow("Площадь", "$area кв. фут")
            }
            state?.padLabel?.let { pad ->
                InfoRow("Салфетка", pad)
            }
            when {
                state?.tankPresent == false -> InfoRow("Бак", "не установлен")
                state?.tankPresent == true || state?.tankLevel != null -> {
                    val hasWater = (state?.tankLevel ?: 0) > 0
                    InfoRow("Вода в баке", if (hasWater) "есть" else "пусто")
                }
            }
            state?.binFull?.let { full ->
                InfoRow("Контейнер", if (full) "ПОЛОН" else "ок")
            }
            state?.lidOpen?.let { open ->
                if (open) InfoRow("Крышка", "открыта")
            }
            state?.signalBars?.let { bars ->
                val dbm = state.rssi?.let { " ($it дБм)" }.orEmpty()
                InfoRow("Wi-Fi", "$bars/5$dbm")
            }
            state?.initiator?.let { who ->
                if (who != "none") InfoRow("Запустил", initiatorLabel(who))
            }
            if (sensorDeltas.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Датчики за миссию",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                sensorDeltas.forEach { (label, value) ->
                    InfoRow(label, value.toString())
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Spacer(Modifier.height(6.dp))
    Row {
        Text(
            label,
            Modifier.width(140.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PreferencesCard(viewModel: MainViewModel, state: RobotState?) {
    val showEdge = state?.edgeSupported == true
    val showBinPause = state?.binPause != null
    val showPasses = state?.multiPassSupported == true ||
        state?.noAutoPasses != null || state?.twoPass != null
    val showWetness = state?.padWetness != null
    val showLed = state?.chargeLed != null
    val showOverlap = state?.rankOverlap != null

    if (!showEdge && !showBinPause && !showPasses && !showWetness && !showLed && !showOverlap) {
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Настройки уборки", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (showEdge) {
                SwitchRow(
                    label = "Убирать по периметру",
                    checked = state?.openOnly == false,
                ) { checked ->
                    viewModel.setPreferences(mapOf("openOnly" to !checked))
                }
            }

            if (showBinPause) {
                SwitchRow(
                    label = "Всегда доводить до конца",
                    checked = state?.binPause == false,
                ) { checked ->
                    viewModel.setPreferences(mapOf("binPause" to !checked))
                }
            }

            if (showPasses) {
                Spacer(Modifier.height(8.dp))
                Text("Проходы", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassButton(
                        label = "Один",
                        selected = state?.noAutoPasses == true && state?.twoPass == false,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setPreferences(
                            mapOf("noAutoPasses" to true, "twoPass" to false),
                        )
                    }
                    PassButton(
                        label = "Два",
                        selected = state?.noAutoPasses == true && state?.twoPass == true,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setPreferences(
                            mapOf("noAutoPasses" to true, "twoPass" to true),
                        )
                    }
                    PassButton(
                        label = "Авто",
                        selected = state?.noAutoPasses == false,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setPreferences(
                            mapOf("noAutoPasses" to false, "twoPass" to false),
                        )
                    }
                }
            }

            if (showWetness) {
                Spacer(Modifier.height(12.dp))
                Text("Количество жидкости", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassButton(
                        label = "Эко",
                        selected = state?.padWetness == 1,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setPadWetness(1)
                    }
                    PassButton(
                        label = "Стандарт",
                        selected = state?.padWetness == 2,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setPadWetness(2)
                    }
                    PassButton(
                        label = "Ультра",
                        selected = state?.padWetness == 3,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setPadWetness(3)
                    }
                }
            }

            if (showLed) {
                Spacer(Modifier.height(12.dp))
                Text("Индикаторы состояния", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassButton(
                        label = "Заряд+док",
                        selected = state?.chargeLed == 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setChargeLed(0)
                    }
                    PassButton(
                        label = "Док",
                        selected = state?.chargeLed == 1,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setChargeLed(1)
                    }
                    PassButton(
                        label = "Выкл",
                        selected = state?.chargeLed == 2,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setChargeLed(2)
                    }
                }
            }

            if (showOverlap) {
                Spacer(Modifier.height(12.dp))
                Text("Перекрытие траектории мытья", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassButton(
                        label = "Малое",
                        selected = state?.rankOverlap == 25,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setRankOverlap(25)
                    }
                    PassButton(
                        label = "Среднее",
                        selected = state?.rankOverlap == 67,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setRankOverlap(67)
                    }
                    PassButton(
                        label = "Большое",
                        selected = state?.rankOverlap == 85,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.setRankOverlap(85)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PassButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val contentPadding =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = contentPadding,
        ) {
            Text(
                label,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = contentPadding,
        ) {
            Text(
                label,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun RawState(state: RobotState?) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Скрыть телеметрию" else "Показать телеметрию")
    }
    AnimatedVisibility(visible = expanded) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    state?.raw ?: "Нет данных",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Unspecified,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clipboard = context
                            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("telemetry", telemetryText(state)),
                        )
                        copied = true
                    }) {
                        Text("Скопировать")
                    }
                    OutlinedButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "BraavaLocal: телеметрия")
                            putExtra(Intent.EXTRA_TEXT, telemetryText(state))
                        }
                        context.startActivity(Intent.createChooser(intent, "Отправить телеметрию"))
                    }) {
                        Text("Поделиться")
                    }
                }
                if (copied) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Скопировано в буфер обмена",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun telemetryText(state: RobotState?): String {
    if (state == null) return "Нет данных"
    return buildString {
        appendLine("BraavaLocal telemetry")
        appendLine("name=${state.name} sku=${state.sku} sw=${state.softwareVer}")
        appendLine("phase=${state.phase} cycle=${state.cycle} error=${state.errorCode} notReady=${state.notReady}")
        appendLine()
        append(state.raw)
    }
}

private fun statusLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Connected -> "Подключено по Wi-Fi"
    ConnectionStatus.Connecting -> "Подключение…"
    ConnectionStatus.Disconnected -> "Не подключено"
    is ConnectionStatus.Error -> status.message
}

private fun cycleLabel(cycle: String): String = when (cycle) {
    "clean" -> "Уборка"
    "spot" -> "Точечная"
    "quick" -> "Быстрая"
    "evac" -> "Опустошение"
    "dock" -> "Стыковка"
    "train" -> "Обучение"
    "tidy" -> "Приведение в порядок"
    else -> cycle
}

private fun initiatorLabel(who: String): String = when (who) {
    "schedule" -> "Расписание"
    "rmtApp" -> "Приложение iRobot"
    "localApp" -> "Это приложение"
    "manual" -> "Кнопка на роботе"
    "dockBtn" -> "Кнопка на базе"
    "alexa" -> "Alexa"
    "cloud" -> "Облако"
    "demand" -> "Уборка по загрязнению"
    else -> who
}

private fun phaseLabel(phase: String?): String = when (phase) {
    null, "" -> "Неизвестно"
    "charge" -> "На базе, заряжается"
    "new" -> "Новая миссия"
    "run", "resume" -> "Убирает"
    "hmMidMsn", "recharge" -> "Подзаряжается"
    "stuck" -> "Застрял"
    "hmUsrDock", "dock" -> "Едет на базу"
    "dockend" -> "Встаёт на базу"
    "cancelled" -> "Отменено"
    "stop" -> "Остановлен"
    "pause" -> "Пауза"
    "hmPostMsn" -> "Завершает уборку"
    "evac" -> "Опустошает контейнер"
    "chargingerror" -> "База отключена"
    "refill" -> "Пополняет бак"
    "spot" -> "Точечная уборка"
    "completed" -> "Миссия завершена"
    else -> phase
}
