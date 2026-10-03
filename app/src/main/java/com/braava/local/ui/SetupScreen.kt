package com.braava.local.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.braava.local.data.cloud.IrobotCloud
import com.braava.local.data.discovery.RobotDiscovery

@Composable
fun SetupScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val robots by viewModel.robots.collectAsStateWithLifecycle()
    val selected by viewModel.config.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            }
            Column {
                Text("МойРобот", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Локальное управление роботами без облака",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        if (robots.isNotEmpty()) {
            Text("Мои роботы", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            robots.forEach { robot ->
                SavedRobotCard(
                    name = robot.name ?: robot.sku ?: "Робот",
                    ip = robot.ip,
                    selected = robot.blid == selected?.blid,
                    onSelect = {
                        viewModel.selectRobot(robot.blid)
                        onClose?.invoke()
                    },
                    onRemove = { viewModel.removeRobot(robot.blid) },
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Добавить робота", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
        }

        Row(Modifier.fillMaxWidth()) {
            TabButton("Аккаунт iRobot", tab == 0, Modifier.weight(1f)) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            TabButton("Вручную", tab == 1, Modifier.weight(1f)) { tab = 1 }
        }
        Spacer(Modifier.height(20.dp))

        if (tab == 0) {
            CloudSetup(viewModel, onAdded = { onClose?.invoke() })
        } else {
            ManualSetup(viewModel, onAdded = { onClose?.invoke() })
        }

        if (busy) {
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SavedRobotCard(
    name: String,
    ip: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (selected) "$ip  •  выбран" else ip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить")
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun CloudSetup(viewModel: MainViewModel, onAdded: () -> Unit) {
    val robots by viewModel.cloudRobots.collectAsStateWithLifecycle()
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<IrobotCloud.CloudRobot?>(null) }

    Text("Шаг 1. Вход в аккаунт iRobot", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Логин и пароль нужны один раз, чтобы получить локальный пароль робота. " +
            "После этого приложение работает только по Wi-Fi.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("E-mail") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    PasswordField(
        value = password,
        onValueChange = { password = it },
        label = "Пароль",
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { viewModel.cloudLogin(username, password) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.CloudDownload, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Получить доступ к роботу")
    }

    if (robots.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text("Шаг 2. Выберите робота", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        robots.forEach { robot ->
            RobotCard(
                title = robot.name ?: "Робот",
                subtitle = buildString {
                    append("BLID: ${robot.blid}")
                    robot.sku?.let { append("  •  $it") }
                    robot.softwareVer?.let { append("  •  ПО $it") }
                },
                selected = selected?.blid == robot.blid,
                onClick = { selected = robot },
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("Шаг 3. IP-адрес в локальной сети", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("IP-адрес") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = { viewModel.discover() }) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Найти")
        }
    }

    if (discovered.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        DiscoveredList(discovered) { found ->
            ip = found.ip
            selected = selected?.let { current ->
                if (current.blid == found.blid) current else current
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { selected?.let { if (viewModel.chooseCloudRobot(it, ip)) onAdded() } },
        enabled = selected != null && ip.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Сохранить и подключиться")
    }
}

@Composable
private fun ManualSetup(viewModel: MainViewModel, onAdded: () -> Unit) {
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val fetchedPassword by viewModel.localPassword.collectAsStateWithLifecycle()

    var blid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    LaunchedEffect(fetchedPassword) {
        val value = fetchedPassword
        if (value != null) {
            password = value
            viewModel.clearLocalPassword()
        }
    }

    Text("Данные робота", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = blid,
        onValueChange = { blid = it },
        label = { Text("BLID") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    PasswordField(
        value = password,
        onValueChange = { password = it },
        label = "Локальный пароль",
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Имя (необязательно)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("IP-адрес") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = { viewModel.discover() }) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Найти")
        }
    }

    if (discovered.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        DiscoveredList(discovered) { found ->
            ip = found.ip
            if (blid.isBlank()) blid = found.blid
            if (name.isBlank()) name = found.robotName.orEmpty()
        }
    }

    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = { viewModel.fetchLocalPassword(ip) },
        enabled = ip.isNotBlank(),
    ) {
        Icon(Icons.Filled.VpnKey, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Получить пароль с робота (режим прослушивания)")
    }
    Text(
        "Поставьте робота на базу, зажмите кнопку HOME до звукового сигнала, " +
            "затем нажмите кнопку. Работает не на всех прошивках.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { if (viewModel.saveManual(blid, password, ip, name)) onAdded() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Сохранить и подключиться")
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        ),
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Скрыть пароль" else "Показать пароль",
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun RobotCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiscoveredList(
    found: List<RobotDiscovery.Found>,
    onPick: (RobotDiscovery.Found) -> Unit,
) {
    Column {
        found.forEach { device ->
            Card(
                onClick = { onPick(device) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        device.robotName ?: device.hostname,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "IP: ${device.ip}  •  BLID: ${device.blid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
