package com.braava.local.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.braava.local.data.NetworkDiagnostics
import com.braava.local.data.RobotConfig
import com.braava.local.data.XiaomiStatus
import com.braava.local.data.xiaomi.MiioDiscovery
import kotlinx.coroutines.launch

/**
 * Control screen for a Xiaomi/Roborock vacuum: local miIO only, no cloud.
 *
 * The map is not shown: it lives in Xiaomi's cloud. Status, basic commands and
 * per-room cleaning all work over the local network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XiaomiControlScreen(
    viewModel: MainViewModel,
    config: RobotConfig,
    modifier: Modifier = Modifier,
    onOpenManager: () -> Unit = {},
) {
    val status by viewModel.xiaomiStatus.collectAsStateWithLifecycle()
    val rooms by viewModel.xiaomiRooms.collectAsStateWithLifecycle()
    val error by viewModel.xiaomiError.collectAsStateWithLifecycle()
    val busy by viewModel.xiaomiBusy.collectAsStateWithLifecycle()
    val cloudReady by viewModel.xiaomiCloudReady.collectAsStateWithLifecycle()
    val cloudMessage by viewModel.xiaomiCloudMessage.collectAsStateWithLifecycle()
    val commandLog by viewModel.xiaomiLog.collectAsStateWithLifecycle()

    var roomsLoaded by remember { mutableStateOf(false) }
    var ipDialogOpen by remember { mutableStateOf(false) }
    var ipDraft by remember { mutableStateOf(config.ip) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var diagnosticsLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var foundOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var foundDevices by remember { mutableStateOf<List<MiioDiscovery.Found>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(config.name ?: "Xiaomi")
                        Text(
                            "${status?.stateLabel ?: "нет связи"} • ${config.ip}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        diagnosticsOpen = true
                        diagnosticsLines = listOf("Проверка…")
                        scope.launch {
                            diagnosticsLines = NetworkDiagnostics.run(context, config.ip)
                        }
                    }) {
                        Icon(Icons.Filled.BugReport, contentDescription = "Диагностика сети")
                    }
                    IconButton(onClick = onOpenManager) {
                        Icon(Icons.Filled.Settings, contentDescription = "Роботы")
                    }
                    IconButton(onClick = { viewModel.xiaomiRefresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
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
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }

            val message = error
            if (message != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            val current = status
            if (current != null) {
                XiaomiStatusCard(current)
            } else {
                Text(
                    "Робот не отвечает. На базе он может спать — запустите уборку " +
                        "кнопкой на корпусе или в Mi Home, и статус появится.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("Управление", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XiaomiButton("Убрать", Modifier.weight(1f)) { viewModel.xiaomiStart() }
                XiaomiButton("Пауза", Modifier.weight(1f)) { viewModel.xiaomiPause() }
                XiaomiButton("Стоп", Modifier.weight(1f)) { viewModel.xiaomiStop() }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XiaomiButton("Домой", Modifier.weight(1f)) { viewModel.xiaomiDock() }
                XiaomiButton("Найти", Modifier.weight(1f)) { viewModel.xiaomiFind() }
            }

            Spacer(Modifier.height(20.dp))
            Text("Облако", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (cloudReady) {
                OutlinedButton(onClick = { viewModel.xiaomiCloudStart() }) {
                    Text("Убрать через облако")
                }
                Text(
                    "Нужно, когда робот спит на базе. Команда идёт через серверы Xiaomi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                cloudMessage?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.clearXiaomiCloudMessage() }) {
                        Text("Скрыть")
                    }
                }
            } else {
                Text(
                    "Облако не подключено. Войдите в Mi-аккаунт во вкладке Xiaomi " +
                        "(код на почту), чтобы запускать робота с базы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("Комнаты", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "ID комнат приходят с робота (get_room_mapping). " +
                    "Имена комнат хранятся в облаке Xiaomi и локально недоступны.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    roomsLoaded = true
                    viewModel.xiaomiLoadRooms()
                },
            ) {
                Text("Загрузить комнаты")
            }
            if (roomsLoaded) {
                if (rooms.isEmpty()) {
                    Text(
                        "Комнаты не получены: робот спит или карта ещё не построена.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    rooms.forEach { id ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Комната $id", modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.xiaomiClean(listOf(id)) }) {
                                Text("Убрать")
                            }
                            if (cloudReady) {
                                TextButton(onClick = { viewModel.xiaomiCloudClean(listOf(id)) }) {
                                    Text("Облако")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Модель: ${config.sku ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Локальный miIO: UDP 54321, токен сохранён.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                ipDraft = config.ip
                ipDialogOpen = true
            }) {
                Text("Изменить IP")
            }
            TextButton(onClick = { logOpen = true }) {
                Text("Журнал команд")
            }
            TextButton(
                onClick = {
                    searching = true
                    scope.launch {
                        foundDevices = MiioDiscovery.discover(context)
                        foundOpen = true
                        searching = false
                    }
                },
            ) {
                Text(if (searching) "Ищу miIO-устройства…" else "Найти робота в сети")
            }
        }
    }

    if (logOpen) {
        AlertDialog(
            onDismissRequest = { logOpen = false },
            confirmButton = {
                TextButton(onClick = { logOpen = false }) { Text("Закрыть") }
            },
            title = { Text("Журнал команд") },
            text = {
                Column(
                    Modifier
                        .height(420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (commandLog.isEmpty()) {
                        Text("Пока пусто", style = MaterialTheme.typography.bodySmall)
                    }
                    commandLog.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
        )
    }

    if (foundOpen) {
        AlertDialog(
            onDismissRequest = { foundOpen = false },
            confirmButton = {
                TextButton(onClick = { foundOpen = false }) { Text("Закрыть") }
            },
            title = { Text("miIO-устройства в сети") },
            text = {
                Column {
                    if (foundDevices.isEmpty()) {
                        Text(
                            "Ничего не найдено. Разбудите робота (уборка или кнопка) " +
                                "и повторите: на базе он может не отвечать.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        foundDevices.forEach { device ->
                            TextButton(
                                onClick = {
                                    if (device.ip != config.ip) {
                                        viewModel.updateRobot(config.copy(ip = device.ip))
                                    }
                                    foundOpen = false
                                },
                            ) {
                                Text("${device.ip}  •  id ${device.deviceId}")
                            }
                        }
                    }
                }
            },
        )
    }

    if (diagnosticsOpen) {
        AlertDialog(
            onDismissRequest = { diagnosticsOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    diagnosticsLines = listOf("Проверка…")
                    scope.launch {
                        diagnosticsLines = NetworkDiagnostics.run(context, config.ip)
                    }
                }) { Text("Повторить") }
            },
            dismissButton = {
                TextButton(onClick = { diagnosticsOpen = false }) { Text("Закрыть") }
            },
            title = { Text("Диагностика сети") },
            text = {
                Column {
                    diagnosticsLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
        )
    }

    if (ipDialogOpen) {
        AlertDialog(
            onDismissRequest = { ipDialogOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ip = ipDraft.trim()
                        if (ip.isNotBlank() && ip != config.ip) {
                            viewModel.updateRobot(config.copy(ip = ip))
                        }
                        ipDialogOpen = false
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { ipDialogOpen = false }) { Text("Отмена") }
            },
            title = { Text("IP-адрес робота") },
            text = {
                OutlinedTextField(
                    value = ipDraft,
                    onValueChange = { ipDraft = it },
                    label = { Text("IP-адрес") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

@Composable
private fun XiaomiStatusCard(status: XiaomiStatus) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(status.stateLabel, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("Заряд: ${status.battery}%")
            if (status.cleanTimeSec > 0) {
                Text(
                    "Убрано: ${status.cleanMinutes} мин • " +
                        "%.1f м²".format(status.areaM2),
                )
            }
            if (status.errorCode != 0) {
                Text(
                    "Ошибка ${status.errorCode}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun XiaomiButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(text)
    }
}
