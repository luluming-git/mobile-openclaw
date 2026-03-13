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
    val showLogs: Boolean = false,
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

    fun dismissLogs() {
        _uiState.update { it.copy(showLogs = false, error = null) }
    }

    fun copyLogs() {
        val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val logText = _uiState.value.logs.joinToString("\n")
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OpenClaw Logs", logText))
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
                showLogs = true,
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

                // Step 2: Install Node.js and dependencies
                updateStep("正在安装 Node.js...", 0.4f)

                terminalSession = TerminalSession(app, bootstrapManager.prefixDir)

                // Check if node actually works (not just exists - needs shared libs)
                // Use full path and actually execute, don't just check file existence
                val checkNode = terminalSession!!.execute(
                    "${'$'}PREFIX/bin/node -e \"process.exit(0)\" 2>/dev/null"
                )
                addLog("  node 可用性检查: ${if (checkNode == 0) "OK" else "需要安装 (exit $checkNode)"}")
                if (checkNode != 0) {
                    val arch = System.getProperty("os.arch") ?: "aarch64"
                    val debArch = when {
                        arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
                        arch.contains("arm") -> "arm"
                        arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
                        else -> "aarch64"
                    }

                    // Termux dependency packages that nodejs-lts needs
                    // These provide the shared libraries (libcares.so, libicu*.so, etc.)
                    // Package format: Triple(displayName, repoSubPath, debFileName)
                    // Paths and versions verified from Tsinghua mirror directory listing
                    data class DepPkg(val name: String, val subPath: String, val debName: String)
                    val depPackages = listOf(
                        DepPkg("c-ares", "c/c-ares", "c-ares_1.34.6_$debArch.deb"),
                        DepPkg("libc++", "libc/libc++", "libc++_29_$debArch.deb"),
                        DepPkg("libicu", "libi/libicu", "libicu_78.2_$debArch.deb"),
                        DepPkg("openssl", "o/openssl", "openssl_1%3A3.6.1_$debArch.deb"),
                        DepPkg("zlib", "z/zlib", "zlib_1.3.2_$debArch.deb"),
                        DepPkg("libnghttp2", "libn/libnghttp2", "libnghttp2_1.68.0-1_$debArch.deb"),
                        DepPkg("libnghttp3", "libn/libnghttp3", "libnghttp3_1.15.0_$debArch.deb"),
                        DepPkg("libngtcp2", "libn/libngtcp2", "libngtcp2_1.21.0_$debArch.deb"),
                        DepPkg("brotli", "b/brotli", "brotli_1.2.0_$debArch.deb"),
                        DepPkg("libsqlite", "libs/libsqlite", "libsqlite_3.52.0-1_$debArch.deb")
                    )

                    val mirrors = listOf(
                        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main" to "清华镜像",
                        "https://mirrors.ustc.edu.cn/termux/apt/termux-main" to "中科大镜像",
                        "https://packages.termux.dev/apt/termux-main" to "官方源"
                    )

                    // Step 2a: Install dependency packages
                    addLog("> 安装 Node.js 依赖库...")
                    val totalPkgs = depPackages.size + 1 // +1 for nodejs itself
                    for ((idx, pkg) in depPackages.withIndex()) {
                        val debFile = File(app.cacheDir, "dep_${pkg.name}.deb")
                        val progress = 0.2f + (idx.toFloat() / totalPkgs) * 0.3f
                        updateStep("安装依赖 (${pkg.name}) ${idx+1}/$totalPkgs", progress)

                        var downloaded = false
                        for ((repo, mirrorName) in mirrors) {
                            val url = "$repo/pool/main/${pkg.subPath}/${pkg.debName}"
                            try {
                                bootstrapManager.downloadFile(url, debFile) { _, _ -> }
                                downloaded = true
                                break
                            } catch (_: Exception) { }
                        }
                        if (downloaded) {
                            try {
                                val count = bootstrapManager.installNodeFromDeb(debFile) { }
                                addLog("  ✔ ${pkg.name} ($count 文件)")
                            } catch (e: Exception) {
                                addLog("  ⚠ ${pkg.name} 安装失败: ${e.message}")
                            }
                            debFile.delete()
                        } else {
                            addLog("  ⚠ ${pkg.name} 下载失败 (跳过)")
                        }
                    }

                    // Step 2b: Install nodejs-lts
                    addLog("> 下载 Node.js (Termux 预编译)...")
                    val debName = "nodejs-lts_24.14.0_${debArch}.deb"
                    val debFile = File(app.cacheDir, "nodejs.deb")

                    var downloadSuccess = false
                    for ((repo, name) in mirrors) {
                        val debUrl = "$repo/pool/main/n/nodejs-lts/$debName"
                        addLog("  尝试 $name 下载 $debName ...")
                        try {
                            bootstrapManager.downloadFile(debUrl, debFile) { downloaded, total ->
                                val mb = downloaded / (1024 * 1024)
                                val totalMb = if (total > 0) total / (1024 * 1024) else 9
                                updateStep("下载 Node.js: ${mb}/${totalMb}MB ($name)", 0.55f + (if (total > 0) downloaded.toFloat() / total * 0.1f else 0f))
                            }
                            addLog("  ✔ 从 $name 下载成功")
                            downloadSuccess = true
                            break
                        } catch (e: Exception) {
                            addLog("  ✘ $name 失败: ${e.message}")
                        }
                    }
                    if (!downloadSuccess) {
                        throw Exception("所有镜像源下载 Node.js 均失败")
                    }

                    addLog("  解压并安装中...")
                    val fileCount = bootstrapManager.installNodeFromDeb(debFile) { msg ->
                        addLog("  $msg")
                    }
                    debFile.delete()
                    addLog("  ✔ 已安装 $fileCount 个文件")

                    // Step 2c: Create npm/npx symlinks (deb symlinks may not extract properly)
                    val prefix = bootstrapManager.prefixDir.absolutePath
                    val npmCliPath = "$prefix/lib/node_modules/npm/bin/npm-cli.js"
                    val npxCliPath = "$prefix/lib/node_modules/npm/bin/npx-cli.js"
                    val npmBin = File("$prefix/bin/npm")
                    val npxBin = File("$prefix/bin/npx")

                    // Find npm-cli.js - might be at different paths
                    val possibleNpmPaths = listOf(
                        "$prefix/lib/node_modules/npm/bin/npm-cli.js",
                        "$prefix/lib/node_modules/.package-lock.json" // marker
                    )
                    addLog("  检查 npm 文件...")
                    // List what we have in lib/node_modules
                    val nodeModulesDir = File("$prefix/lib/node_modules")
                    if (nodeModulesDir.exists()) {
                        val contents = nodeModulesDir.list() ?: emptyArray()
                        addLog("  lib/node_modules/ 内容: ${contents.joinToString(", ")}")
                    } else {
                        addLog("  ⚠ lib/node_modules/ 不存在")
                    }

                    // Create npm wrapper script if npm-cli.js exists
                    if (File(npmCliPath).exists()) {
                        npmBin.writeText("#!/bin/sh\nexec \"$prefix/bin/node\" \"$npmCliPath\" \"\$@\"\n")
                        npmBin.setExecutable(true, false)
                        addLog("  ✔ 创建 npm 脚本")
                    } else {
                        addLog("  ⚠ npm-cli.js 不存在")
                    }
                    if (File(npxCliPath).exists()) {
                        npxBin.writeText("#!/bin/sh\nexec \"$prefix/bin/node\" \"$npxCliPath\" \"\$@\"\n")
                        npxBin.setExecutable(true, false)
                        addLog("  ✔ 创建 npx 脚本")
                    }

                    // Step 2d: Ensure OpenSSL config exists
                    val opensslConf = File("$prefix/etc/tls/openssl.cnf")
                    if (!opensslConf.exists()) {
                        File("$prefix/etc/tls").mkdirs()
                        opensslConf.writeText("""
                            # Minimal OpenSSL configuration
                            openssl_conf = openssl_init
                            [openssl_init]
                            ssl_conf = ssl_sect
                            [ssl_sect]
                            system_default = system_default_sect
                            [system_default_sect]
                            CipherString = DEFAULT:@SECLEVEL=1
                        """.trimIndent() + "\n")
                        addLog("  ✔ 创建 OpenSSL 默认配置")
                    }
                }

                // Diagnostic: check what we have
                addLog("> 诊断检查...")
                val prefix = bootstrapManager.prefixDir.absolutePath
                terminalSession = TerminalSession(app, bootstrapManager.prefixDir)
                terminalSession!!.execute(
                    """
                    echo "PREFIX=${'$'}PREFIX"
                    echo "--- bin/ 中的关键文件 ---"
                    ls -la ${'$'}PREFIX/bin/node 2>&1
                    ls -la ${'$'}PREFIX/bin/npm 2>&1
                    ls -la ${'$'}PREFIX/bin/npx 2>&1
                    echo "--- 测试 node ---"
                    ${'$'}PREFIX/bin/node -v 2>&1
                    echo "--- npm-cli.js ---"
                    ls -la ${'$'}PREFIX/lib/node_modules/npm/bin/npm-cli.js 2>&1
                    """.trimIndent(),
                    onOutput = { addLog("  $it") }
                )
                addLog("✔ Node.js 安装完成")

                // Step 2.5: Set npm mirror (use node to call npm-cli.js directly)
                addLog("> 配置 npm 淘宝镜像加速...")
                val npmCli = "$prefix/lib/node_modules/npm/bin/npm-cli.js"
                terminalSession!!.execute(
                    "${'$'}PREFIX/bin/node $npmCli config set registry https://registry.npmmirror.com",
                    onOutput = { addLog("  $it") }
                )
                addLog("✔ npm 镜像已切换为淘宝源")

                // Step 3: Install OpenClaw (use node to call npm-cli.js directly, bypass shebang)
                updateStep("正在安装 OpenClaw...", 0.6f)
                addLog("> 通过 node 直接运行 npm install -g openclaw...")

                val openclawResult = terminalSession!!.execute(
                    "${'$'}PREFIX/bin/node $npmCli install -g openclaw --ignore-scripts --no-optional 2>&1",
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
                    """${'$'}PREFIX/bin/node ${'$'}PREFIX/lib/node_modules/openclaw/bin/openclaw.js onboard \
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
                addLog("")
                addLog("✘ ======== 安装失败 ========")
                addLog("✘ 错误: ${e.message}")
                addLog("✘ 请点击上方【复制日志】按钮")
                addLog("✘ 然后粘贴日志给开发者")
                addLog("✘ ========================")
                _uiState.update {
                    it.copy(
                        isInstalling = false,
                        showLogs = true,
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
