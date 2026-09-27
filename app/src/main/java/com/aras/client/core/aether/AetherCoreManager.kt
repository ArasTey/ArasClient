package com.aras.client.core.aether

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.dto.AetherEndpoint
import com.aras.client.dto.AetherRange
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherIpVersion
import com.aras.client.enums.AetherObfuscation
import com.aras.client.enums.AetherProtocol
import com.aras.client.enums.AetherScanMode
import com.aras.client.enums.AetherTransport
import com.aras.client.fmt.AetherFmt
import com.aras.client.util.LogUtil
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the aether core process: one live session at a time, the command line a profile
 * runs as, readiness probing, and cleanup.
 *
 * The core is a separate process, not a library — it is the only thing that can dial a
 * WARP gateway with a scanned endpoint and a WARP identity, so an Aether profile is
 * carried by dialing this process's loopback SOCKS listener.
 */
object AetherCoreManager {

    /**
     * The core ships in the APK as a shared library, because a file in the app's data
     * directory is not executable on Android 10 and later. Shipping it under jniLibs
     * puts it in nativeLibraryDir, where it is both packaged and runnable.
     */
    const val BINARY_NAME = "libaether.so"

    const val COMMAND_NAME = "aether"

    private const val PROBE_TIMEOUT_MS = 1000
    private const val READY_POLL_MS = 500L
    private const val DEFAULT_LOG_LEVEL = "info"
    private const val MAX_READY_WAIT_MS = 30_000L

    /**
     * Names the app process that spawned a core process. Rust ignores SIGPIPE and the core
     * has no parent-death handling, so a core whose owner was killed would keep running;
     * this is how a later session recognises and reaps such an orphan.
     */
    const val OWNER_ENV = "ARASCLIENT_AETHER_OWNER"

    /** Set on the session core and on no other, so a scan or a test core can be told apart. */
    const val SESSION_ENV = "ARASCLIENT_AETHER_SESSION"

    private val session = AtomicReference<AetherCore?>(null)
    private var process: Process? = null

    val socksPort: Int get() = AppConfig.PORT_AETHER_SOCKS.toInt()

    fun listenPort(profile: ProfileItem): Int =
        AetherFmt.listenPortOf(profile.aetherListenPort) ?: socksPort

    // ------------------------------------------------------------- arguments

    /**
     * The arguments a profile's core is started with.
     *
     * @param port the loopback port the app will dial
     * @param scan whether this is a scan for a gateway rather than the session;
     *   a scan looks for WARP endpoints from where the session will look, so a
     *   profile's own peer and hops are left out and `--no-quick-reconnect` is set
     * @param logLevel the core's own verbosity
     */
    fun buildArguments(
        profile: ProfileItem,
        port: Int,
        scan: Boolean = false,
        logLevel: String = DEFAULT_LOG_LEVEL,
    ): List<String> {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        return buildList {
            addAll(listOf("--bind", "${AppConfig.LOOPBACK}:$port"))
            addAll(listOf("--protocol", protocol.type))
            addAll(listOf("--scan", AetherScanMode.fromString(profile.aetherScanMode).type))
            // Automatic obfuscation is the core's own choice per protocol, so say nothing.
            AetherObfuscation.fromString(profile.aetherObfuscation)
                .takeUnless { it == AetherObfuscation.AUTO }
                ?.let { addAll(listOf("--noize", it.type)) }
            addAll(listOf("--ip", AetherIpVersion.fromString(profile.aetherIpVersion).type))
            profile.aetherDns?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--dns", it)) }
            // A scan keeps the exit rule too, so it ends on an endpoint the session accepts.
            profile.aetherExitLoc?.takeIf { it.isNotBlank() }
                ?.let { addAll(listOf("--exit-loc", it)) }

            if (protocol.overMasque &&
                AetherTransport.fromString(profile.aetherTransport) == AetherTransport.HTTP2
            ) {
                add("--h2")
                if (profile.aetherFragment == true) {
                    add("--fragment")
                    AetherRange.parse(profile.aetherFragmentSize, AetherRange.FRAGMENT_SIZE)
                        ?.let { addAll(listOf("--fragment-size", it.toString())) }
                    AetherRange.parse(profile.aetherFragmentDelay, AetherRange.FRAGMENT_DELAY)
                        ?.let { addAll(listOf("--fragment-delay", it.toString())) }
                }
            }
            // Encrypted Client Hello hides the server name of the MASQUE handshake.
            if (protocol.overMasque && profile.aetherEch == true) addAll(listOf("--ech", "auto"))

            if (protocol.twoHops) {
                val hop = if (protocol == AetherProtocol.MIM) "--mim" else "--wiw"
                val outer = AetherEndpoint.parse(profile.aetherWiwOuter).takeUnless { scan }
                val inner = AetherEndpoint.parse(profile.aetherWiwInner).takeUnless { scan }
                outer?.let { addAll(listOf("$hop-outer", it.toString())) }
                inner?.let { addAll(listOf("$hop-inner", it.toString())) }
                if (outer == null && inner == null) add("$hop-scan")
            } else if (!scan) {
                AetherEndpoint.of(profile.server, profile.serverPort)
                    ?.let { addAll(listOf("--peer", it.toString())) }
            }

            add(if (scan) "--no-quick-reconnect" else "--quick-reconnect")
            addAll(listOf("--log-level", logLevel))
        }
    }

    /** Maps the app's log level setting onto the levels the core accepts. */
    fun coreLogLevel(appLevel: String?): String = when (appLevel?.lowercase(Locale.US)) {
        "debug" -> "debug"
        "info" -> "info"
        "warning", "warn" -> "warn"
        "error", "none" -> "error"
        else -> DEFAULT_LOG_LEVEL
    }

    // ------------------------------------------------------------- argv tools

    internal fun withoutOption(arguments: List<String>, option: String): List<String> {
        val out = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            if (arguments[index] == option) {
                index += 2
                continue
            }
            out += arguments[index]
            index++
        }
        return out
    }

    internal fun withListener(arguments: List<String>, port: Int): List<String> {
        val index = arguments.indexOf("--bind")
        if (index < 0 || index + 1 >= arguments.size) return arguments
        return arguments.toMutableList().apply { this[index + 1] = "${AppConfig.LOOPBACK}:$port" }
    }

    internal fun listenerPortOf(arguments: List<String>): Int? {
        val index = arguments.indexOf("--bind")
        if (index < 0 || index + 1 >= arguments.size) return null
        return arguments[index + 1].substringAfterLast(':').toIntOrNull()
    }

    internal fun protocolOf(arguments: List<String>): AetherProtocol {
        val index = arguments.indexOf("--protocol")
        if (index < 0 || index + 1 >= arguments.size) return AetherProtocol.WIREGUARD
        return AetherProtocol.fromString(arguments[index + 1])
    }

    // ------------------------------------------------------------- process

    /** Where the core binary lives, and whether this build carries it. */
    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    fun isAvailable(context: Context): Boolean = binary(context).canExecute()

    private fun workDir(context: Context): File =
        File(context.filesDir, "aether").apply { mkdirs() }

    /**
     * Starts the core and waits for its listener to answer.
     *
     * @param onOutput every line the core writes, already relayed into the app log
     * @return true when the listener came up within the wait
     */
    fun start(
        context: Context,
        core: AetherCore,
        onOutput: (String) -> Unit,
    ): Boolean {
        stop()
        val exe = binary(context)
        if (!exe.canExecute()) {
            LogUtil.e(AppConfig.TAG, "AetherCore: ${exe.name} is not present or not executable")
            return false
        }
        val work = workDir(context)
        val builder = ProcessBuilder(listOf(exe.absolutePath) + core.arguments)
            .directory(work)
            .redirectErrorStream(true)
        builder.environment().apply {
            put(OWNER_ENV, android.os.Process.myPid().toString())
            put(SESSION_ENV, "1")
            // The core keeps its WARP identity here; without HOME it looks in a place
            // an app cannot write and re-registers on every start.
            put("HOME", work.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
        }
        val started = try {
            builder.start()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "AetherCore: failed to launch the core", e)
            return false
        }
        process = started
        session.set(core)

        started.inputStream.bufferedReader().forEachLine { line ->
            LogUtil.i(AppConfig.TAG, "AetherCore: $line")
            onOutput(line)
        }

        val ready = awaitListener(core.port)
        if (!ready) {
            LogUtil.e(AppConfig.TAG, "AetherCore: listener on ${core.port} never came up")
            stop()
        }
        return ready
    }

    private fun awaitListener(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + MAX_READY_WAIT_MS
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

    /** The core currently running, or null. */
    fun running(): AetherCore? = session.get()?.takeIf { probe(it.port) }

    fun stop() {
        process?.let { running ->
            runCatching { running.destroy() }
            if (!running.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                runCatching { running.destroyForcibly() }
            }
        }
        process = null
        session.set(null)
    }
}
