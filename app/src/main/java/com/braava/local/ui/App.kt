package com.braava.local.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.braava.local.ui.theme.BraavaTheme

@Composable
fun BraavaApp(viewModel: MainViewModel = viewModel()) {
    BraavaTheme {
        val config by viewModel.config.collectAsStateWithLifecycle()
        val error by viewModel.error.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        var showManager by remember { mutableStateOf(false) }

        LaunchedEffect(error) {
            val message = error
            if (message != null) {
                snackbarHostState.showSnackbar(message)
                viewModel.clearError()
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            val current = config
            if (current == null || showManager) {
                SetupScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(padding),
                    // A back button only makes sense when there is something
                    // to go back to.
                    onClose = if (current != null) ({ showManager = false }) else null,
                )
            } else {
                ControlScreen(
                    viewModel = viewModel,
                    config = current,
                    modifier = Modifier.padding(padding),
                    onOpenManager = { showManager = true },
                )
            }
        }
    }
}
