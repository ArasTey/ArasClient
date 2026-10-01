package com.aras.client.core.mieru

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the mieru client for a profile, the way the aether core is run: a child process
 * that holds the tunnel and serves a local SOCKS5, with the app's outbound a hop to
 * that listener.
 *
 * mieru is started in the foreground (`mieru run`) and pointed at a configuration file
 * through MIERU_CONFIG_JSON_FILE, so its lifetime is the lifetime of the handle held
 * here rather than something it detaches into the background on its own.
 */
object MieruProcessManager {

    /** Ships in the APK as a shared library: a data-dir file is not executable. */
    const val BINARY_NAME = "libmieru.so"

    /** The configuration file this process is pointed at; also how an orphan is found. */
    const val CONFIG_ENV = "MIERU_CONFIG_JSON_FILE"

    /** Names the app process that started it, so an orphan can be recognised. */
    const val OWNER_ENV = "ARASCLIENT_MIERU_OWNER"

    private const val WORK_DIR = "mieru"
    private const val CONFIG_FILE = "mieru.json"
    private const val PROFILE_NAME = "aras"
    private const val DEFAULT_MTU = 1360
    private const val PROBE_TIMEOUT_MS = 1000
    private const val READY_POLL_MS = 400L
    private const val READY_WAIT_MS = 30_000L

    /** What the client prints once its SOCKS5 listener is up. */
    private val ready = Regex("""socks5 server is running""")

    private val session = AtomicReference<Int?>(null)
    private var process: Process? = null

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    fun isAvailable(context: Context): Boolean = binary(context).canExecute()

    private fun workDir(context: Context): File =
        File(context.filesDir, WORK_DIR).apply { mkdirs() }

    /**
     * The configuration for [profile], in the shape the client reads.
     *
     * The client is told where to look with [CONFIG_ENV] rather than being asked to
     * store anything itself, so a profile is a pure function of what the user typed.
     */
    fun buildConfig(profile: ProfileItem, socksPort: Int): String {
        val host = profile.server?.trim().orEmpty()
        val port = profile.serverPort?.trim()?.toIntOrNull() ?: 0
        val mtu = profile.mtu?.takeIf { it > 0 } ?: DEFAULT_MTU
        val server = if (Utils.isPureIpAddress(host)) {
            """"ipAddress": "${JsonEscape(host)}""""
        } else {
            """"domainName": "${JsonEscape(host)}""""
        }
        return buildString {
            append("""{"profiles":[{""")
            append(""""profileName": "$PROFILE_NAME",""")
            append(""""user": {""")
            profile.username?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(""""name": "${JsonEscape(it)}",""")
            }
            profile.password?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(""""password": "${JsonEscape(it)}"""")
            }
            append("},")
            append(""""servers": [{$server, """)
            append(""""portBindings": [{"port": $port, "protocol": "TCP"}]}],""")
            append(""""mtu": $mtu""")
            append("}],")
            append(""""activeProfile": "$PROFILE_NAME",""")
            append(""""socks5Port": $socksPort,""")
            append(""""loggingLevel": "INFO"""")
            append("}")
        }
    }

    /** Quotes a value for the configuration, so a quote in a password cannot break it. */
    internal fun JsonEscape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    /**
     * Starts the client and waits for its listener.
     *
     * @return true when the SOCKS5 port came up within the wait
     */
    fun start(context: Context, profile: ProfileItem, socksPort: Int): Boolean {
        stop()
        reapStale()
        val exe = binary(context)
        if (!exe.canExecute()) {
            LogUtil.e(AppConfig.TAG, "Mieru: ${exe.name} is not present or not executable")
            return false
        }
        val work = workDir(context)
        val config = File(work, CONFIG_FILE)
        val written = runCatching { config.writeText(buildConfig(profile, socksPort)) }
        if (written.isFailure) {
            written.exceptionOrNull()?.let {
                LogUtil.e(AppConfig.TAG, "Mieru: could not write the client configuration", it)
            }
            return false
        }

        val builder = ProcessBuilder(listOf(exe.absolutePath, "run"))
            .directory(work)
            .redirectErrorStream(true)
        builder.environment().apply {
            put(CONFIG_ENV, config.absolutePath)
            put(OWNER_ENV, android.os.Process.myPid().toString())
            // The client keeps its own state beside its configuration; without a HOME
            // it looks in a place an app cannot write.
            put("HOME", work.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
        }
        val started = try {
            builder.start()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Mieru: failed to launch the client", e)
            return false
        }
        process = started
        session.set(socksPort)
        pumpOutput(started, socksPort)

        val up = awaitListener(socksPort)
        if (!up) {
            LogUtil.e(AppConfig.TAG, "Mieru: the listener on $socksPort never came up")
            stop()
        }
        return up
    }

    /** Reads the client on its own thread; a session core runs until it is stopped. */
    private fun pumpOutput(started: Process, socksPort: Int) {
        val lines = LinkedBlockingQueue<String>()
        Thread {
            try {
                started.inputStream.bufferedReader().forEachLine { line ->
                    LogUtil.i(AppConfig.TAG, "Mieru: $line")
                    lines.put(line)
                }
            } catch (_: Throwable) {
                // The client ended or the stream closed; the marker below still goes in.
            } finally {
                runCatching { lines.offer("") }
            }
        }.apply {
            isDaemon = true
            name = "mieru-output"
            start()
        }
    }

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
     * Kills a client left behind by an app process that died.
     *
     * The client detaches nothing here, but a process the system killed mid-session can
     * survive its owner, and a surviving client holds the loopback port: the next start
     * then dies with "address already in use" rather than starting. Every client is
     * started with this pid in its environment, so anything carrying it and still alive
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
                    LogUtil.w(AppConfig.TAG, "Mieru: reaping a client left behind in pid $pid")
                    runCatching { android.os.Process.killProcess(pid) }
                }
        }
    }

    /** The client currently running, or null. */
    fun running(): Int? = session.get()?.takeIf { probe(it) }

    fun stop() {
        process?.let { started ->
            runCatching { started.destroy() }
            if (!started.waitFor(2, TimeUnit.SECONDS)) {
                runCatching { started.destroyForcibly() }
            }
        }
        process = null
        session.set(null)
    }
}
