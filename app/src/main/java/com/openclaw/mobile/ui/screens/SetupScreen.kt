package com.openclaw.mobile.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.mobile.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    uiState: UiState,
    onFetchModels: (baseUrl: String, apiKey: String) -> Unit,
    onStartInstall: (baseUrl: String, apiKey: String, modelId: String) -> Unit
) {
    var baseUrl by remember { mutableStateOf(uiState.savedBaseUrl) }
    var apiKey by remember { mutableStateOf(uiState.savedApiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var baseUrlError by remember { mutableStateOf<String?>(null) }
    var apiKeyError by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf("") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    // Auto-select first model when list loads
    LaunchedEffect(uiState.models) {
        if (uiState.models.isNotEmpty() && selectedModel.isEmpty()) {
            selectedModel = uiState.models.first()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D0D1A),
                        Color(0xFF1A0A2E),
                        Color(0xFF0D0D1A)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Logo
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                tonalElevation = 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "◉",
                        fontSize = 32.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "OpenClaw",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "让 AI 操控你的手机",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Base URL input
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    baseUrlError = null
                },
                label = { Text("API Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                leadingIcon = {
                    Icon(Icons.Default.Cloud, contentDescription = null)
                },
                isError = baseUrlError != null,
                supportingText = baseUrlError?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = Color.White.copy(alpha = 0.05f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // API Key input
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    apiKeyError = null
                },
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
                leadingIcon = {
                    Icon(Icons.Default.Key, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = "Toggle visibility"
                        )
                    }
                },
                isError = apiKeyError != null,
                supportingText = apiKeyError?.let { { Text(it) } },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = Color.White.copy(alpha = 0.05f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Fetch models button
            FilledTonalButton(
                onClick = {
                    var hasError = false
                    if (baseUrl.isBlank() || !baseUrl.startsWith("http")) {
                        baseUrlError = "请先输入有效的 URL"
                        hasError = true
                    }
                    if (apiKey.isBlank() || apiKey.length < 8) {
                        apiKeyError = "请先输入有效的 API Key"
                        hasError = true
                    }
                    if (!hasError) {
                        selectedModel = ""
                        onFetchModels(baseUrl.trim(), apiKey.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !uiState.isFetchingModels,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                )
            ) {
                if (uiState.isFetchingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("获取模型列表中...", fontSize = 13.sp)
                } else {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("获取可用模型列表", fontSize = 13.sp)
                }
            }

            // Model error
            if (uiState.modelError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.modelError,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }

            // Model selector
            if (uiState.models.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { modelDropdownExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择模型") },
                        leadingIcon = {
                            Icon(Icons.Default.SmartToy, contentDescription = null)
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.secondary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false },
                        modifier = Modifier.heightIn(max = 250.dp)
                    ) {
                        uiState.models.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = model,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    selectedModel = model
                                    modelDropdownExpanded = false
                                },
                                leadingIcon = {
                                    if (model == selectedModel) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "共 ${uiState.models.size} 个可用模型",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Error message
            if (uiState.error != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.error,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Install button
            Button(
                onClick = {
                    var hasError = false
                    if (baseUrl.isBlank() || !baseUrl.startsWith("http")) {
                        baseUrlError = "请输入有效的 URL（以 http:// 或 https:// 开头）"
                        hasError = true
                    }
                    if (apiKey.isBlank() || apiKey.length < 8) {
                        apiKeyError = "请输入有效的 API Key"
                        hasError = true
                    }
                    if (selectedModel.isBlank()) {
                        hasError = true
                        // If no model selected, use fallback
                        if (uiState.models.isEmpty()) {
                            selectedModel = "gpt-5.2"
                            hasError = false
                        }
                    }
                    if (!hasError) {
                        onStartInstall(baseUrl.trim(), apiKey.trim(), selectedModel)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.RocketLaunch, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (selectedModel.isNotBlank()) "安装并启动（$selectedModel）"
                           else "一键安装并启动",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "首次安装约需 5-10 分钟 · 请保持网络连接",
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f)
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
