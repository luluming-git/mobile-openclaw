package com.openclaw.mobile.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openclaw.mobile.ui.screens.SetupScreen
import com.openclaw.mobile.ui.screens.StatusScreen
import com.openclaw.mobile.ui.screens.TerminalScreen
import com.openclaw.mobile.ui.screens.WebViewScreen
import com.openclaw.mobile.viewmodel.MainViewModel

@Composable
fun OpenClawApp() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(
        factory = MainViewModel.Factory(context.applicationContext as android.app.Application)
    )
    val uiState by viewModel.uiState.collectAsState()

    // Navigation state
    var webViewUrl by remember { mutableStateOf<String?>(null) }
    var webViewTitle by remember { mutableStateOf("") }
    var showTerminal by remember { mutableStateOf(false) }

    when {
        // Terminal console
        showTerminal -> {
            TerminalScreen(
                viewModel = viewModel,
                onBack = { showTerminal = false }
            )
        }
        // WebView is open
        webViewUrl != null -> {
            WebViewScreen(
                url = webViewUrl!!,
                title = webViewTitle,
                onBack = { webViewUrl = null }
            )
        }
        // Status / Installing / Running
        uiState.isRunning || uiState.isInstalling || uiState.showLogs -> {
            StatusScreen(
                uiState = uiState,
                onStop = { viewModel.stopGateway() },
                onRestart = { viewModel.restartGateway() },
                onCopyLogs = { viewModel.copyLogs() },
                onDismiss = { viewModel.dismissLogs() },
                onOpenChat = {
                    webViewUrl = uiState.gatewayUrl
                    webViewTitle = "OpenClaw 对话"
                },
                onOpenPanel = {
                    showTerminal = true
                }
            )
        }
        // Setup screen
        else -> {
            SetupScreen(
                uiState = uiState,
                onFetchModels = { baseUrl, apiKey ->
                    viewModel.fetchModels(baseUrl, apiKey)
                },
                onStartInstall = { baseUrl, apiKey, modelId ->
                    viewModel.startFullInstall(baseUrl, apiKey, modelId)
                }
            )
        }
    }
}
