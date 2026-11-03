package com.aras.client.handler

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

object UpdateDownloader {

    private const val UPDATES_DIR = "updates"
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Downloads an update APK into the app cache.
     *
     * The file is written to [Context.getCacheDir]/updates and verified against
     * [expectedSha256] when the release provides a digest. The call suspends until
     * the download completes and throws on failure; partial files are removed.
     */
    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        expectedSha256: String?,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
        if (!dir.isDirectory) throw IOException("Unable to create update cache directory")
        dir.listFiles()?.forEach { it.delete() }

        val safeName = sanitizeFileName(fileName)
        val target = File(dir, safeName)

        val httpPort = SettingsManager.getHttpPort()
        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        try {
            try {
                downloadOnce(url, target, 0, null, null, onProgress)
            } catch (e: IOException) {
                if (httpPort == 0) throw e
                LogUtil.w(AppConfig.TAG, "Direct update download failed, retrying via proxy", e)
                target.delete()
                downloadOnce(url, target, httpPort, proxyUsername, proxyPassword, onProgress)
            }

            val expected = expectedSha256?.takeIf { it.isNotBlank() }
            if (expected != null) {
                onVerifying()
                val actual = sha256(target)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IOException("Update checksum mismatch")
                }
            }
            target
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private suspend fun downloadOnce(
        url: String,
        target: File,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        val client = buildClient(httpPort, proxyUsername, proxyPassword)
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/octet-stream")
        if (httpPort != 0 && !proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
            requestBuilder.header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
        }

        val call = client.newCall(requestBuilder.build())
        val job = coroutineContext[Job]
        val cancellationHandle = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Update download failed with HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty update response")
                val totalBytes = body.contentLength()
                var bytesRead = 0L
                onProgress(0L, totalBytes)

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytesRead += read
                            onProgress(bytesRead, totalBytes)
                        }
                    }
                }
                if (bytesRead == 0L) throw IOException("Empty update file")
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun buildClient(
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        if (httpPort != 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, httpPort)))
            if (!proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
                            .build()
                    }
                }
            }
        }

        return builder.build()
    }

    private fun sanitizeFileName(fileName: String): String {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        val safe = name.ifBlank { "update.apk" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return when {
            safe.endsWith(".apk", ignoreCase = true) || safe.endsWith(".patch", ignoreCase = true) -> safe
            else -> "$safe.apk"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun hasSha256(file: File, expected: String): Boolean =
        file.isFile && sha256(file).equals(expected, ignoreCase = true)
}
