package com.braava.local.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.braava.local.data.RobotConfig

/** Shared dialog to edit a saved robot: name, IP, and LAN discovery. */
@Composable
fun RobotEditDialog(
    viewModel: MainViewModel,
    robot: RobotConfig,
    onDismiss: () -> Unit,
) {
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var ip by remember { mutableStateOf(robot.ip) }
    var name by remember { mutableStateOf(robot.name.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.updateRobot(robot.copy(ip = ip.trim(), name = name.trim().takeIf { it.isNotBlank() }))
                    onDismiss()
                },
                enabled = ip.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Изменить робота") },
        text = {
            Column(
                Modifier
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "BLID: ${robot.blid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                        Spacer(Modifier.width(6.dp))
                        Text("Найти")
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    CircularProgressIndicator()
                }
                if (discovered.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Найденные в сети:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DiscoveredList(discovered) { found ->
                        ip = found.ip
                        if (name.isBlank()) name = found.robotName.orEmpty()
                    }
                }
            }
        },
    )
}
