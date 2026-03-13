package com.openclaw.mobile.viewmodel

import android.app.Application
import android.content.Intent
import java.io.File
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import com.openclaw.mobile.service.BootstrapManager
import com.openclaw.mobile.service.GatewayService
import com.openclaw.mobile.service.ModelFetcher
import com.openclaw.mobile.service.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val isInstalling: Boolean = false,
    val isRunning: Boolean = false,
    val installStep: String = "",
    val installProgress: Float = 0f,
    val logs: List<String> = emptyList(),
    val error: String? = null,
    val savedBaseUrl: String = "",
    val savedApiKey: String = "",
    val models: List<String> = emptyList(),
    val isFetchingModels: Boolean = false,
    val modelError: String? = null
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val bootstrapManager = BootstrapManager(app)
    private var terminalSession: TerminalSession? = null
    private var gatewayProcess: Process? = null

    init {
        // Load saved config
        val prefs = app.getSharedPreferences("openclaw", 0)
        val baseUrl = prefs.getString("base_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        _uiState.update { it.copy(savedBaseUrl = baseUrl, savedApiKey = apiKey) }

        // Check if already installed
        if (bootstrapManager.isInstalled()) {
            addLog("✔ 检测到已有安装，可直接启动")
        }
    }

    /**
     * Fetch available models from the API.
     */
    fun fetchModels(baseUrl: String, apiKey: String) {
        _uiState.update { it.copy(isFetchingModels = true, modelError = null, models = emptyList()) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val models = ModelFetcher.fetchModels(baseUrl, apiKey)
                val modelIds = models.map { it.id }
                _uiState.update {
                    it.copy(
                        models = modelIds,
                        isFetchingModels = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isFetchingModels = false,
                        modelError = "获取模型失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun startFullInstall(baseUrl: String, apiKey: String, modelId: String) {
        // Save config
        app.getSharedPreferences("openclaw", 0).edit()
            .putString("base_url", baseUrl)
            .putString("api_key", apiKey)
            .putString("model_id", modelId)
            .apply()

        _uiState.update {
            it.copy(
                isInstalling = true,
                error = null,
                logs = emptyList(),
                savedBaseUrl = baseUrl,
                savedApiKey = apiKey
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Step 1: Download & extract bootstrap
                updateStep("正在下载 Linux 运行环境...", 0.1f)
                addLog("> 下载 Termux bootstrap 包...")

                bootstrapManager.downloadAndExtract(
                    onProgress = { progress, message ->
                        updateStep(message, 0.1f + progress * 0.3f)
                        addLog("  $message")
                    }
                )

                addLog("✔ Linux 运行环境就绪")

                // Step 2: Install Node.js (Java downloads, shell extracts only)
                updateStep("正在安装 Node.js...", 0.4f)
                addLog("> 下载 Node.js (Termux 预编译)...")

                terminalSession = TerminalSession(app, bootstrapManager.prefixDir)

                // Check if node already installed
                val checkNode = terminalSession!!.execute("command -v node && node -v")
                if (checkNode != 0) {
                    // Download .deb via Java/OkHttp (has SSL certs, shell doesn't)
                    val arch = System.getProperty("os.arch") ?: "aarch64"
                    val debArch = when {
                        arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
                        arch.contains("arm") -> "arm"
                        arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
                        else -> "aarch64"
                    }
                    val debUrl = "https://packages.termux.dev/apt/termux-main/pool/main/n/nodejs-lts/nodejs-lts_24.14.0_${debArch}.deb"
                    val debFile = File(app.cacheDir, "nodejs.deb")

                    addLog("  下载 nodejs-lts_24.14.0_${debArch}.deb ...")
                    bootstrapManager.downloadFile(debUrl, debFile) { downloaded, total ->
                        val mb = downloaded / (1024 * 1024)
                        updateStep("下载 Node.js: ${mb}MB", 0.4f + (if (total > 0) downloaded.toFloat() / total * 0.15f else 0f))
                    }
                    addLog("  下载完成，解压中...")

                    // Copy .deb to bootstrap tmp dir and extract via shell
                    val tmpDeb = File(File(app.filesDir, "tmp"), "node.deb")
                    debFile.copyTo(tmpDeb, overwrite = true)
                    debFile.delete()

                    val extractResult = terminalSession!!.execute(
                        """
                        set -e
                        cd ${'$'}TMPDIR
                        ar x node.deb 2>/dev/null || (echo "ar not found, using tar fallback"; cp node.deb data.tar.xz 2>/dev/null || true)
                        tar xf data.tar.* -C ${'$'}PREFIX/.. 2>/dev/null || tar xf data.tar.* -C / 2>/dev/null || true
                        chmod +x ${'$'}PREFIX/bin/node 2>/dev/null || true
                        if [ ! -f "${'$'}PREFIX/bin/npm" ] && [ -f "${'$'}PREFIX/lib/node_modules/npm/bin/npm-cli.js" ]; then
                            ln -sf ../lib/node_modules/npm/bin/npm-cli.js ${'$'}PREFIX/bin/npm
                            ln -sf ../lib/node_modules/npm/bin/npx-cli.js ${'$'}PREFIX/bin/npx
                        fi
                        rm -f node.deb data.tar.* control.tar.* debian-binary
                        echo "Node: $(node -v 2>&1 || echo 'failed')"
                        echo "npm: $(npm -v 2>&1 || echo 'not found')"
                        """.trimIndent(),
                        onOutput = { addLog("  $it") }
                    )

                    if (extractResult != 0) {
                        throw Exception("Node.js 解压失败 (exit code: $extractResult)")
                    }
                }
                addLog("✔ Node.js 安装完成")

                // Step 2.5: Set npm mirror
                addLog("> 配置 npm 淘宝镜像加速...")
                terminalSession!!.execute(
                    "npm config set registry https://registry.npmmirror.com",
                    onOutput = { addLog("  $it") }
                )
                addLog("✔ npm 镜像已切换为淘宝源")

                // Step 3: Install OpenClaw (npm install runs in shell, but downloads use npm registry which works)
                updateStep("正在安装 OpenClaw...", 0.6f)
                addLog("> npm install -g openclaw...")

                val openclawResult = terminalSession!!.execute(
                    "npm install -g openclaw",
                    onOutput = { addLog("  $it") }
                )

                if (openclawResult != 0) {
                    throw Exception("OpenClaw 安装失败 (exit code: $openclawResult)")
                }
                addLog("✔ OpenClaw 安装完成")

                // Step 4: Configure OpenClaw
                updateStep("正在配置 OpenClaw...", 0.8f)
                addLog("> 自动配置 (onboard --non-interactive)...")

                val configResult = terminalSession!!.execute(
                    """openclaw onboard \
                        --non-interactive \
                        --mode local \
                        --auth-choice custom-api-key \
                        --custom-base-url "$baseUrl" \
                        --custom-api-key "$apiKey" \
                        --custom-model-id "$modelId" \
                        --custom-compatibility openai \
                        --no-install-daemon \
                        --skip-channels \
                        --skip-skills \
                        --skip-health""".trimIndent(),
                    onOutput = { addLog("  $it") }
                )

                if (configResult != 0) {
                    throw Exception("OpenClaw 配置失败 (exit code: $configResult)")
                }
                addLog("✔ OpenClaw 配置完成")

                // Step 5: Start gateway
                updateStep("正在启动 Gateway...", 0.95f)
                addLog("> 启动 openclaw gateway...")

                startGatewayProcess()

                addLog("✔ Gateway 启动成功")
                addLog("✔ 控制面板: http://localhost:18789")

                _uiState.update {
                    it.copy(
                        isInstalling = false,
                        isRunning = true,
                        installStep = "",
                        installProgress = 1f
                    )
                }

            } catch (e: Exception) {
                addLog("✘ 安装失败: ${e.message}")
                _uiState.update {
                    it.copy(
                        isInstalling = false,
                        error = e.message ?: "未知错误"
                    )
                }
            }
        }
    }

    private fun startGatewayProcess() {
        // Start foreground service
        val intent = Intent(app, GatewayService::class.java).apply {
            putExtra("prefix_dir", bootstrapManager.prefixDir.absolutePath)
        }
        app.startForegroundService(intent)

        // Also start the process
        terminalSession?.let { session ->
            viewModelScope.launch(Dispatchers.IO) {
                gatewayProcess = session.startLongRunning(
                    "openclaw gateway",
                    onOutput = { addLog(it) }
                )
            }
        }

        _uiState.update { it.copy(isRunning = true) }
    }

    fun stopGateway() {
        gatewayProcess?.destroyForcibly()
        gatewayProcess = null
        app.stopService(Intent(app, GatewayService::class.java))
        _uiState.update { it.copy(isRunning = false) }
        addLog("⚠ Gateway 已停止")
    }

    fun restartGateway() {
        stopGateway()
        addLog("> 正在重启 Gateway...")
        startGatewayProcess()
        addLog("✔ Gateway 已重启")
    }

    private fun updateStep(step: String, progress: Float) {
        _uiState.update {
            it.copy(installStep = step, installProgress = progress)
        }
    }

    private fun addLog(line: String) {
        _uiState.update {
            it.copy(logs = it.logs + line)
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            return MainViewModel(app) as T
        }
    }
}
