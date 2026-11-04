package com.aras.client.root

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.util.LogUtil
import java.io.File

/**
 * Minimal root command runner backed by the `su` binary.
 *
 * Scripts are written to the app's private root runtime dir and executed with
 * `su -c sh <file>` so shell quoting stays simple. stderr is merged into stdout.
 */
object RootShell {

    data class Result(val code: Int, val output: String) {
        val success: Boolean get() = code == 0
    }

    /** Write [script] to `<filesDir>/root/<name>` and run it as root. */
    fun runScript(context: Context, name: String, script: String): Result {
        val dir = File(context.filesDir, AppConfig.ROOT_RUNTIME_DIR).apply { mkdirs() }
        val file = File(dir, name).apply {
            writeText(script)
            setExecutable(true, false)
        }
        val safePath = file.absolutePath.replace("'", "'\\''")
        return exec("sh '$safePath'")
    }

    private fun exec(command: String, timeoutSeconds: Long = 30): Result {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            // Drain stdout concurrently so a verbose root command cannot fill the pipe
            // and deadlock before it exits. Keep the reader daemonized for timeout safety.
            val output = StringBuilder()
            val reader = Thread({
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) {
                                if (output.isNotEmpty()) output.append('\n')
                                output.append(line)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // The process may close the stream while being terminated.
                }
            }, "RootShell-output").apply {
                isDaemon = true
                start()
            }

            val timeoutNanos = timeoutSeconds.coerceAtLeast(1) * 1_000_000_000L
            val deadline = System.nanoTime() + timeoutNanos
            var exitCode: Int? = null
            while (System.nanoTime() < deadline) {
                exitCode = try {
                    process.exitValue()
                } catch (_: IllegalThreadStateException) {
                    null
                }
                if (exitCode != null) break
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    runCatching { process.destroy() }
                    return Result(-1, synchronized(output) { output.toString() })
                }
            }

            if (exitCode == null) {
                runCatching { process.destroy() }
                try {
                    reader.join(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val captured = synchronized(output) { output.toString() }
                LogUtil.e(AppConfig.TAG, "RootShell: timed out: $command")
                return Result(-1, captured)
            }

            try {
                reader.join(1_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val captured = synchronized(output) { output.toString() }
            val result = Result(exitCode, captured)
            if (!result.success) {
                LogUtil.w(AppConfig.TAG, "RootShell: '$command' exited ${result.code}: ${captured.trim()}")
            }
            result
        } catch (e: Exception) {
            runCatching { process?.destroy() }
            LogUtil.e(AppConfig.TAG, "RootShell: failed to run '$command'", e)
            Result(-1, e.message ?: e.javaClass.simpleName)
        }
    }
}
