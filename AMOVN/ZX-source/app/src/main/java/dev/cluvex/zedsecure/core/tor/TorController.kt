package dev.cluvex.zedsecure.core.tor

import android.content.Context
import dev.cluvex.zedsecure.core.AppLog as Log
import dev.cluvex.zedsecure.domain.model.AppSettings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class TorController(
    private val context: Context,
    private val settings: AppSettings,
    private val onBootstrapped: () -> Unit,
    private val onStopped: (String?) -> Unit,

    private val upstreamSocksPort: Int? = null,

    private val onProgress: (Int) -> Unit = {},

    val socksPort: Int = TorConfigBuilder.SOCKS_PORT,
) {
    private val active = AtomicBoolean(false)
    private var process: Process? = null
    private var reader: Thread? = null

    val isRunning: Boolean get() = active.get()

    fun start(): Boolean {
        if (active.get()) stop()
        return try {
            val torDir = File(context.filesDir, "tor").apply { mkdirs() }
            extractAsset("geoip", torDir)
            extractAsset("geoip6", torDir)
            extractAsset("bridges_default.lst", torDir)

            val dataDir = File(context.filesDir, "tor_data").apply { mkdirs() }
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val torrc = File(torDir, "torrc").apply {
                writeText(TorConfigBuilder.build(settings, nativeLibDir, torDir, dataDir, upstreamSocksPort, socksPort = socksPort))
            }

            val cmd = mutableListOf(
                "$nativeLibDir/libtor.so", "-f", torrc.absolutePath,
                "--pidfile", File(dataDir, "tor.pid").absolutePath,
            )

            val webtunnel = settings.torBridgesMode != "none" && settings.torBridgeTransport == "webtunnel"
            if (settings.torFakeSniEnabled && !webtunnel) {
                val hosts = settings.torFakeSniHosts.split(',', '\n').map { it.trim() }
                    .filter { it.isNotEmpty() }.ifEmpty { DEFAULT_FAKE_SNI }.joinToString(",")
                cmd += "--fake-hosts"; cmd += hosts
            }
            val pb = ProcessBuilder(cmd)
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p
            active.set(true)

            reader = Thread {
                var bootstrapped = false
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        Log.i(TAG, line)
                        BOOTSTRAP_PCT.find(line)?.groupValues?.get(1)?.toIntOrNull()
                            ?.let { pct -> runCatching { onProgress(pct) } }
                        if (!bootstrapped && line.contains("Bootstrapped 100%")) {
                            bootstrapped = true

                            Thread { runCatching { onBootstrapped() } }
                                .apply { isDaemon = true; start() }
                        }
                    }
                }

                if (active.compareAndSet(true, false)) {
                    val code = runCatching { p.waitFor() }.getOrDefault(-1)
                    Log.i(TAG, "tor exited ($code)")
                    onStopped(if (bootstrapped) null else "Tor exited before connecting")
                }
            }.apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            active.set(false)
            Log.e(TAG, "tor failed to start", e)
            onStopped(e.message ?: "Tor failed to start")
            false
        }
    }

    fun stop() {
        active.set(false)
        runCatching { process?.destroy() }
        process = null
        reader = null
    }

    private fun extractAsset(name: String, destDir: File) {
        val dest = File(destDir, name)
        if (dest.exists() && dest.length() > 0) return
        context.assets.open("tor/$name").use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    private companion object {
        const val TAG = "Tor"

        val BOOTSTRAP_PCT = Regex("""Bootstrapped (\d{1,3})%""")

        val DEFAULT_FAKE_SNI = listOf(
            "play.googleapis.com", "drive.google.com", "cdn.ampproject.org", "api.github.com",
            "ajax.aspnetcdn.com", "verizon.com", "eset.com",
        )
    }
}
