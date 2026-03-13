package com.openclaw.mobile.service

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Executes shell commands within the bootstrap Linux environment.
 */
class TerminalSession(
    private val context: Context,
    private val prefixDir: File
) {
    private val bootstrapManager = BootstrapManager(context)
    private val env = bootstrapManager.buildEnvironment()

    /**
     * Execute a command and wait for it to complete.
     * @return exit code
     */
    suspend fun execute(
        command: String,
        onOutput: (String) -> Unit = {}
    ): Int {
        val shell = File(prefixDir, "bin/sh").absolutePath

        val processBuilder = ProcessBuilder(shell, "-c", command).apply {
            directory(File(env["HOME"] ?: context.filesDir.absolutePath))
            environment().putAll(env)
            redirectErrorStream(true)
        }

        val process = processBuilder.start()

        // Read output line by line
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { onOutput(it) }
            }
        }

        return process.waitFor()
    }

    /**
     * Start a long-running command (like gateway) and return the process.
     */
    fun startLongRunning(
        command: String,
        onOutput: (String) -> Unit = {}
    ): Process {
        val shell = File(prefixDir, "bin/sh").absolutePath

        val processBuilder = ProcessBuilder(shell, "-c", command).apply {
            directory(File(env["HOME"] ?: context.filesDir.absolutePath))
            environment().putAll(env)
            redirectErrorStream(true)
        }

        val process = processBuilder.start()

        // Read output in a separate thread
        Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { onOutput(it) }
                    }
                }
            } catch (_: Exception) { }
        }.start()

        return process
    }
}
