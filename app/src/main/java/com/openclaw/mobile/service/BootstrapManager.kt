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
     * Install Node.js from a Termux .deb file entirely in Java.
     * 1. Parse ar archive to find data.tar.xz
     * 2. Decompress XZ and extract tar entries
     * 3. Strip Termux path prefix and write to prefixDir
     *
     * @return number of files extracted
     */
    fun installNodeFromDeb(
        debFile: File,
        onProgress: (String) -> Unit
    ): Int {
        onProgress("解析 .deb 文件...")

        // Step 1: Extract data.tar.xz from ar archive
        val dataTarBytes = extractArEntry(debFile, "data.tar")
            ?: throw Exception(".deb 中未找到 data.tar")

        onProgress("解压 data.tar.xz (${dataTarBytes.size / 1024}KB)...")

        // Step 2: Decompress XZ and extract tar
        val termuxPrefix = "data/data/com.termux/files/usr/"
        var fileCount = 0

        val xzInput = org.tukaani.xz.XZInputStream(dataTarBytes.inputStream())
        val tarInput = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xzInput)

        var entry = tarInput.nextEntry
        while (entry != null) {
            var entryName = entry.name
            // Strip leading "./" if present
            if (entryName.startsWith("./")) entryName = entryName.substring(2)

            // Strip Termux prefix: data/data/com.termux/files/usr/ -> ""
            val relativePath = if (entryName.startsWith(termuxPrefix)) {
                entryName.substring(termuxPrefix.length)
            } else {
                entry = tarInput.nextEntry
                continue // Skip files outside usr/
            }

            if (relativePath.isEmpty()) {
                entry = tarInput.nextEntry
                continue
            }

            val outFile = File(prefixDir, relativePath)

            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                // Handle symlinks
                if (entry.isSymbolicLink) {
                    try {
                        Runtime.getRuntime().exec(
                            arrayOf("ln", "-sf", entry.linkName, outFile.absolutePath)
                        ).waitFor()
                    } catch (_: Exception) { }
                } else {
                    FileOutputStream(outFile).use { fos ->
                        tarInput.copyTo(fos)
                    }
                    // Set executable for bin/ files
                    if (relativePath.startsWith("bin/") ||
                        relativePath.endsWith(".so") ||
                        !relativePath.contains(".")) {
                        outFile.setExecutable(true, false)
                    }
                }
                fileCount++
            }

            entry = tarInput.nextEntry
        }
        tarInput.close()

        onProgress("安装完成: $fileCount 个文件")
        return fileCount
    }

    /**
     * Extract a named entry from an ar archive (.deb file).
     * Returns the raw bytes of the entry, or null if not found.
     */
    private fun extractArEntry(arFile: File, namePrefix: String): ByteArray? {
        val input = arFile.inputStream().buffered()

        // Skip ar magic "!<arch>\n" (8 bytes)
        input.skip(8)

        while (input.available() > 0) {
            val header = ByteArray(60)
            if (input.read(header) < 60) break

            val name = String(header, 0, 16).trim().trimEnd('/')
            val sizeStr = String(header, 48, 10).trim()
            val size = sizeStr.toLongOrNull() ?: 0L

            if (name.startsWith(namePrefix)) {
                val data = ByteArray(size.toInt())
                var offset = 0
                while (offset < data.size) {
                    val read = input.read(data, offset, data.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                input.close()
                return data
            } else {
                input.skip(size)
                if (size % 2 != 0L) input.skip(1)
            }
        }
        input.close()
        return null
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
