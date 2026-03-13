package com.openclaw.mobile.service

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Manages downloading and extracting the Termux bootstrap environment.
 * The bootstrap contains a minimal Linux environment with pkg, apt, etc.
 */
class BootstrapManager(private val context: Context) {

    val prefixDir: File get() = File(context.filesDir, "usr")
    private val binDir: File get() = File(prefixDir, "bin")
    private val etcDir: File get() = File(prefixDir, "etc")
    private val tmpDir: File get() = File(context.filesDir, "tmp")

    companion object {
        // 国内镜像（已验证可用）
        private const val MIRROR_BASE_URL =
            "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/latest/download"
        // GitHub 原始地址（备用）
        private const val GITHUB_BASE_URL =
            "https://github.com/termux/termux-packages/releases/latest/download"

        private fun getBootstrapUrls(arch: String): List<String> {
            val archSuffix = when (arch) {
                "aarch64" -> "aarch64"
                "arm" -> "arm"
                "x86_64" -> "x86_64"
                "i686" -> "i686"
                else -> "aarch64"
            }
            val filename = "bootstrap-$archSuffix.zip"
            return listOf(
                "$MIRROR_BASE_URL/$filename",   // 优先国内镜像
                "$GITHUB_BASE_URL/$filename"    // 备用原始地址
            )
        }
    }

    fun isInstalled(): Boolean {
        return File(binDir, "sh").exists() && File(binDir, "pkg").exists()
    }

    /**
     * Downloads the bootstrap zip for the device's architecture and extracts it.
     */
    suspend fun downloadAndExtract(
        onProgress: (Float, String) -> Unit
    ) {
        if (isInstalled()) {
            onProgress(1f, "运行环境已存在，跳过下载")
            return
        }

        // Detect architecture
        val arch = System.getProperty("os.arch") ?: "aarch64"
        onProgress(0f, "检测到架构: $arch")

        val urls = getBootstrapUrls(arch)
        val zipFile = File(context.cacheDir, "bootstrap.zip")

        // Download with mirror fallback
        onProgress(0.1f, "正在下载 bootstrap ($arch)...")
        var lastError: Exception? = null
        for ((index, url) in urls.withIndex()) {
            try {
                val source = if (index == 0) "国内镜像" else "GitHub"
                onProgress(0.1f, "尝试从${source}下载...")
                downloadFile(url, zipFile) { downloaded, total ->
                    val p = if (total > 0) downloaded.toFloat() / total else 0f
                    val mb = downloaded / (1024 * 1024)
                    onProgress(0.1f + p * 0.5f, "下载中: ${mb}MB (${source})")
                }
                lastError = null
                break  // 下载成功，跳出循环
            } catch (e: Exception) {
                lastError = e
                onProgress(0.1f, "${if (index == 0) "国内镜像" else "GitHub"}下载失败，尝试备用源...")
            }
        }
        if (lastError != null) {
            throw Exception("所有下载源均失败: ${lastError!!.message}")
        }
        onProgress(0.6f, "下载完成，正在解压...")

        // Extract
        extractBootstrap(zipFile)
        onProgress(0.9f, "设置文件权限...")

        // Set permissions
        setExecutablePermissions()

        // Create essential directories
        tmpDir.mkdirs()
        File(context.filesDir, "home").mkdirs()

        // Clean up
        zipFile.delete()
        onProgress(1f, "运行环境就绪")
    }

    /**
     * Download a file using OkHttp (uses Android system SSL certs).
     * Public so ViewModel can use it for downloading Node.js etc.
     */
    fun downloadFile(
        url: String,
        outputFile: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)   // 30MB on slow mobile: allow up to 5 min
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenClaw-Mobile/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("下载失败: HTTP ${response.code}")
            }

            val body = response.body ?: throw Exception("下载响应为空")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            FileOutputStream(outputFile).use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }
        }
    }

    private fun extractBootstrap(zipFile: File) {
        prefixDir.mkdirs()

        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(prefixDir, entry.name)

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        zis.copyTo(fos)
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Handle symlinks file if present
        val symlinkFile = File(prefixDir, "SYMLINKS.txt")
        if (symlinkFile.exists()) {
            symlinkFile.readLines().forEach { line ->
                val parts = line.split("←")
                if (parts.size == 2) {
                    try {
                        val target = parts[0].trim()
                        val link = File(prefixDir, parts[1].trim())
                        link.parentFile?.mkdirs()
                        Runtime.getRuntime().exec(
                            arrayOf("ln", "-sf", target, link.absolutePath)
                        ).waitFor()
                    } catch (_: Exception) { }
                }
            }
            symlinkFile.delete()
        }
    }

    private fun setExecutablePermissions() {
        binDir.listFiles()?.forEach { file ->
            file.setExecutable(true, false)
        }

        // Also set executable on common lib directories
        listOf("lib", "libexec").forEach { dir ->
            File(prefixDir, dir).walkTopDown().forEach { file ->
                if (file.isFile && (file.name.endsWith(".so") || !file.name.contains("."))) {
                    file.setExecutable(true, false)
                }
            }
        }
    }

    /**
     * Build the environment variables for running commands in the bootstrap.
     */
    fun buildEnvironment(): Map<String, String> {
        val prefix = prefixDir.absolutePath
        val home = File(context.filesDir, "home").absolutePath
        val tmp = tmpDir.absolutePath

        return mapOf(
            "PREFIX" to prefix,
            "HOME" to home,
            "TMPDIR" to tmp,
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "PATH" to "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "$prefix/lib",
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data"
        )
    }
}
