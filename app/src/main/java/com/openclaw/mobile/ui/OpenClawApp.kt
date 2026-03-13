package com.openclaw.mobile.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openclaw.mobile.ui.screens.SetupScreen
import com.openclaw.mobile.ui.screens.StatusScreen
import com.openclaw.mobile.viewmodel.MainViewModel

@Composable
fun OpenClawApp() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(
        factory = MainViewModel.Factory(context.applicationContext as android.app.Application)
    )
    val uiState by viewModel.uiState.collectAsState()

    when {
        uiState.isRunning || uiState.isInstalling -> {
            StatusScreen(
                uiState = uiState,
                onStop = { viewModel.stopGateway() },
                onRestart = { viewModel.restartGateway() }
            )
        }
        else -> {
            SetupScreen(
                uiState = uiState,
                onStartInstall = { baseUrl, apiKey ->
                    viewModel.startFullInstall(baseUrl, apiKey)
                }
            )
        }
    }
}
