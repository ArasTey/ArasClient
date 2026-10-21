package com.aras.client.core.dnstunnel

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.util.LogUtil
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the DNS-tunnel client for a profile, the way the aether core and the mieru
 * client are run: a child process that holds the tunnel and serves a local
 * SOCKS5, with the app's outbound a hop to that listener.
 *
 * The engine is a program that reads a TOML configuration and a resolver list
 * and prints its progress on stdout; the app hands it both files and reads the
 * output back, so what the tunnel is doing is visible in the log and on the
 * profile card.
 */
object DnsTunnelProcessManager {

    /** Names the app process that started the engine, so an orphan can be recognised. */
    const val OWNER_ENV = "ARASCLIENT_DNSTUNNEL_OWNER"

    private const val WORK_DIR = "dnstunnel"
    private const val CONFIG_FILE = "client.toml"
    private const val RESOLVERS_FILE = "resolvers.txt"
    private const val PROBE_TIMEOUT_MS = 1000
    private const val READY_POLL_MS = 400L
    private const val READY_WAIT_MS = 30_000L

    /** What the engine reported about the session, as far as the app has read. */
    data class State(
        val phase: String? = null,
        val percent: Int? = null,
        val activeResolvers: List<String> = emptyList(),
        val standbyResolvers: List<String> = emptyList(),
        val validResolvers: Int? = null,
        val uploadSpeed: String? = null,
        val downloadSpeed: String? = null,
    )

    private val state = AtomicReference(State())
    private val lastError = AtomicReference<String?>(null)
    private var process: Process? = null

    fun binary(context: Context, engine: DnsTunnelEngine): File =
        File(context.applicationInfo.nativeLibraryDir, engine.binaryName)

    fun isAvailable(context: Context, engine: DnsTunnelEngine): Boolean =
        binary(context, engine).canExecute()

    /** What the engine has reported so far. */
    fun currentState(): State = state.get()

    /**
     * The last thing the engine complained about.
     *
     * A tunnel whose zone is wrong, or whose resolvers cannot reach the zone,
     * never brings its listener up — and the engine says why. That reason is
     * worth more to the user than "the listener never came up", so it is kept.
     */
    fun lastError(): String? = lastError.get()

    private fun workDir(context: Context, engine: DnsTunnelEngine): File =
        File(File(context.filesDir, WORK_DIR), engine.id).apply { mkdirs() }

    /**
     * Starts the engine and waits for its SOCKS5 listener.
     *
     * @param socksPort the port the engine is told to serve its SOCKS5 on
     * @param dnsPort the port the engine is told to serve its own DNS on, or
     *   null to leave that off
     * @return true when the listener came up within the wait
     */
    fun start(
        context: Context,
        profile: ProfileItem,
        engine: DnsTunnelEngine,
        socksPort: Int,
        dnsPort: Int?,
    ): Boolean {
        stop()
        reapStale()
        val exe = binary(context, engine)
        if (!exe.canExecute()) {
            LogUtil.e(AppConfig.TAG, "DNS tunnel: ${exe.name} is not present or not executable")
            return false
        }
        val work = workDir(context, engine)
        val config = File(work, CONFIG_FILE)
        val resolvers = File(work, RESOLVERS_FILE)
        val written = runCatching {
            config.writeText(DnsTunnelConfigRenderer.renderToml(profile, engine, socksPort, dnsPort))
            resolvers.writeText(DnsTunnelConfigRenderer.renderResolvers(profile))
        }
        if (written.isFailure) {
            written.exceptionOrNull()?.let {
                LogUtil.e(AppConfig.TAG, "DNS tunnel: could not write the engine configuration", it)
            }
            return false
        }

        val builder = ProcessBuilder(
            listOf(exe.absolutePath, "-config", config.absolutePath, "-resolvers", resolvers.absolutePath)
        )
            .directory(work)
            .redirectErrorStream(true)
        builder.environment().apply {
            put(OWNER_ENV, android.os.Process.myPid().toString())
            // The engine keeps its state beside its configuration; without a HOME
            // it looks in a place an app cannot write.
            put("HOME", work.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
        }
        val started = try {
            builder.start()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "DNS tunnel: failed to launch the engine", e)
            return false
        }
        process = started
        state.set(State())
        lastError.set(null)
        pumpOutput(started)

        val up = awaitListener(socksPort)
        if (!up) {
            LogUtil.e(AppConfig.TAG, "DNS tunnel: the listener on $socksPort never came up")
            stop()
        }
        return up
    }

    /** Reads the engine on its own thread; the engine runs until it is stopped. */
    private fun pumpOutput(started: Process) {
        Thread {
            try {
                started.inputStream.bufferedReader().forEachLine { line ->
                    LogUtil.i(AppConfig.TAG, "DNS tunnel | $line")
                    handleOutput(line)
                }
            } catch (_: Throwable) {
                // The engine ended or the stream closed.
            }
        }.apply {
            isDaemon = true
            name = "dns-tunnel-output"
            start()
        }
    }

    /**
     * Reads what the engine reports about the session.
     *
     * The engine prints its progress, its resolver state and its traffic on
     * stdout; what it says is kept so the app can show the session's state
     * without asking the engine again.
     */
    internal fun applyTelemetry(line: String) {
        val current = state.get()
        when {
            line.startsWith("WD_PROGRESS") -> {
                val fields = telemetryFields(line)
                state.set(
                    current.copy(
                        phase = fields["phase"] ?: current.phase,
                        percent = fields["percent"]?.toIntOrNull() ?: current.percent,
                    )
                )
            }

            line.startsWith("WD_RESOLVERS") -> {
                val fields = telemetryFields(line)
                state.set(
                    current.copy(
                        activeResolvers = splitList(fields["active"]),
                        standbyResolvers = splitList(fields["standby"]),
                        validResolvers = fields["valid"]?.toIntOrNull() ?: current.validResolvers,
                    )
                )
            }

            TRAFFIC.containsMatchIn(line) -> {
                val groups = TRAFFIC.find(line)?.groupValues
                val up = groups?.getOrNull(1)
                val down = groups?.getOrNull(2)
                state.set(
                    current.copy(
                        uploadSpeed = up ?: current.uploadSpeed,
                        downloadSpeed = down ?: current.downloadSpeed,
                    )
                )
            }
        }
    }

    /**
     * What one line of the engine's output means.
     *
     * The telemetry is read for the session's state, and a complaint is kept:
     * a tunnel whose zone does not resolve never brings its listener up, and
     * the engine's own reason is worth more to the user than the closed port.
     */
    internal fun handleOutput(line: String) {
        applyTelemetry(line)
        if (line.contains("[ERROR]") || line.contains("Rejected")) {
            lastError.set(line.substringAfter("] ").trim())
        }
    }

    /** The key=value pairs of a telemetry line, the marker itself left out. */    private fun telemetryFields(line: String): Map<String, String> =
        line.split(WHITESPACE)
            .drop(1)
            .mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator <= 0) null
                else field.substring(0, separator) to field.substring(separator + 1)
            }
            .toMap()

    private val WHITESPACE = Regex("\\s+")

    /** The engine's traffic line, as the StormDNS family prints it. */
    private val TRAFFIC = Regex("""([0-9.]+\s?[KMGT]?B/s)\s*\(Total:.*?\).*?([0-9.]+\s?[KMGT]?B/s)""")

    private fun splitList(value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it != "-" } ?: emptyList()

    private fun awaitListener(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + READY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (probe(port)) return true
            if (process?.isAlive == false) return false
            try {
                Thread.sleep(READY_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /** Whether something is already listening on loopback [port]. */
    fun probe(port: Int): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(AppConfig.LOOPBACK, port), PROBE_TIMEOUT_MS)
            true
        }
    } catch (e: IOException) {
        false
    }

    /**
     * Kills an engine left behind by an app process that died.
     *
     * A process the system killed mid-session can survive its owner, and a
     * surviving engine holds the loopback port: the next start then dies with
     * "address already in use" rather than starting. Every engine is started
     * with this pid in its environment, so anything carrying it and still alive
     * is ours to kill.
     */
    fun reapStale() {
        val mine = android.os.Process.myPid()
        runCatching {
            File("/proc").listFiles()
                ?.filter { it.isDirectory && it.name.toIntOrNull() != null }
                ?.forEach { dir ->
                    val pid = dir.name.toIntOrNull() ?: return@forEach
                    if (pid == mine) return@forEach
                    val environ = runCatching { File(dir, "environ").readBytes() }.getOrNull()
                        ?: return@forEach
                    if (!environ.toString(Charsets.ISO_8859_1).contains("$OWNER_ENV=$mine")) {
                        return@forEach
                    }
                    LogUtil.w(AppConfig.TAG, "DNS tunnel: reaping an engine left behind in pid $pid")
                    runCatching { android.os.Process.killProcess(pid) }
                }
        }
    }

    fun stop() {
        process?.let { started ->
            runCatching { started.destroy() }
            if (!started.waitFor(2, TimeUnit.SECONDS)) {
                runCatching { started.destroyForcibly() }
            }
        }
        process = null
        state.set(State())
    }
}
