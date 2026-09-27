package com.aras.client.core.aether

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.dto.AetherEndpoint
import com.aras.client.dto.AetherRange
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherIpVersion
import com.aras.client.enums.AetherObfuscation
import com.aras.client.enums.AetherPsiphon
import com.aras.client.enums.AetherPsiphonCdnSet
import com.aras.client.enums.AetherPsiphonMode
import com.aras.client.enums.AetherTor
import com.aras.client.enums.AetherTorBridges
import com.aras.client.enums.AetherTorRelays
import com.aras.client.enums.AetherProtocol
import com.aras.client.enums.AetherScanMode
import com.aras.client.enums.AetherTransport
import com.aras.client.fmt.AetherFmt
import com.aras.client.handler.SettingsManager
import com.aras.client.util.Utils
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

    const val AETHER_CONFIG_ENV = "AETHER_CONFIG"
    const val AETHER_MASQUE_CONFIG_ENV = "AETHER_MASQUE_CONFIG"
    const val AETHER_WG_CONFIG_ENV = "AETHER_WG_CONFIG"

    /** The option that names Psiphon's own listener. */
    const val PSIPHON_BIND = "--psiphon-bind"

    /** The option that names Tor's own listener. */
    const val TOR_BIND = "--tor-bind"

    /** The Psiphon client, run beside the core. */
    const val PSIPHON_BINARY_NAME = "libpsiphon-tunnel-core.so"

    /** The pluggable transport Tor's bridges run through; it speaks every one the core asks for. */
    const val TRANSPORT_BINARY_NAME = "liblyrebird.so"

    const val PSIPHON_BIN_ENV = "AETHER_PSIPHON_BIN"
    const val TOR_PT_ENV = "AETHER_TOR_PT"
    const val CERT_DIR_ENV = "SSL_CERT_DIR"
    const val PSIPHON_CONFIG_ENV = "AETHER_PSIPHON_CONFIG"
    const val PSIPHON_DIR_ENV = "AETHER_PSIPHON_DIR"
    const val PSIPHON_SERVER_ENTRIES = "--psiphon-server-entries"
    const val SHIPPED_LIST = "shipped-list"

    private val psiphonReady = Regex("psiphon ready|proxy started: psiphon", RegexOption.IGNORE_CASE)
    private val torReady = Regex("tor ready|proxy started: tor", RegexOption.IGNORE_CASE)

    /** The work directory, shared with [AetherIdentityManager]. */
    const val WORK_DIR = "aether"

    /** How long a scan or a key renewal may take before it is given up on. */
    const val ONE_SHOT_TIMEOUT_MS = 2 * 60_000L

    /** A free loopback port for a one-shot core, away from the session's own. */
    fun scanPort(profile: ProfileItem): Int {
        val sessionPort = listenPort(profile)
        return if (sessionPort + 1 in 1..65535) sessionPort + 1 else sessionPort - 1
    }

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
        // A scan looks for WARP endpoints from where the session will look: a carrier
        // around the tunnel stays, one inside it has no part in a scan.
        val tor = AetherTor.fromString(profile.aetherTor).takeUnless { scan && it != AetherTor.REVERSE }
            ?: AetherTor.OFF
        val psiphon = AetherPsiphon.fromString(profile.aetherPsiphon)
            .takeUnless { scan && it != AetherPsiphon.REVERSE } ?: AetherPsiphon.OFF
        // The listener the app dials takes [port]: Psiphon's or Tor's when one of them
        // runs inside the tunnel and is what the app reaches, the tunnel's own
        // otherwise. Every other listener takes a port after it, in the order the
        // tunnel's own, then Tor's, then Psiphon's.
        val dialsPsiphon = psiphon == AetherPsiphon.CHAIN
        val dialsTor = tor == AetherTor.CHAIN && !dialsPsiphon
        var next = port + 1
        val own = if (dialsPsiphon || dialsTor) next++ else port
        val torBind = when (tor) {
            AetherTor.CHAIN -> if (dialsTor) port else next++
            AetherTor.REVERSE -> next++
            AetherTor.OFF, AetherTor.ONLY -> null
        }
        // Psiphon around the tunnel is the exception: nothing of the app dials its
        // listener and the core takes the port Psiphon reports, so an ephemeral port
        // keeps a test core from colliding with the session's.
        val psiphonBind = when (psiphon) {
            AetherPsiphon.CHAIN -> if (dialsPsiphon) port else next++
            AetherPsiphon.REVERSE -> 0
            AetherPsiphon.OFF, AetherPsiphon.ONLY -> null
        }
        return buildList {
            addAll(listOf("--bind", "${AppConfig.LOOPBACK}:$own"))
            if (psiphon != AetherPsiphon.ONLY && tor != AetherTor.ONLY) {
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
            }

            when (tor) {
                AetherTor.OFF -> Unit
                AetherTor.CHAIN -> add("--tor")
                AetherTor.REVERSE -> add("--tor-reverse")
                AetherTor.ONLY -> add("--tor-only")
            }
            torBind?.let { addAll(listOf(TOR_BIND, "${AppConfig.LOOPBACK}:$it")) }
            if (tor != AetherTor.OFF) {
                // Told nothing, the core tries Tor plainly and turns to fetched bridges
                // where Tor is blocked.
                when (AetherTorBridges.fromString(profile.aetherTorBridges)) {
                    AetherTorBridges.AUTO -> Unit
                    AetherTorBridges.FIRST -> add("--tor-bridges")
                    AetherTorBridges.NEVER -> add("--no-tor-bridges")
                    AetherTorBridges.OWN -> AetherFmt.bridgeLines(profile.aetherTorBridgeLines)
                        .forEach { addAll(listOf("--tor-bridge", it)) }
                }
                if (AetherTorBridges.fromString(profile.aetherTorBridges) in
                    setOf(AetherTorBridges.AUTO, AetherTorBridges.FIRST)
                ) {
                    AetherTorRelays.fromString(profile.aetherTorRelays)
                        .takeUnless { it == AetherTorRelays.AUTO }
                        ?.let { addAll(listOf("--tor-relays", it.type)) }
                }
            }

            when (psiphon) {
                AetherPsiphon.OFF -> Unit
                AetherPsiphon.CHAIN -> add("--psiphon")
                AetherPsiphon.REVERSE -> add("--psiphon-reverse")
                AetherPsiphon.ONLY -> add("--psiphon-only")
            }
            psiphonBind?.let { addAll(listOf(PSIPHON_BIND, "${AppConfig.LOOPBACK}:$it")) }
            if (psiphon != AetherPsiphon.OFF) {
                val shape = AetherPsiphonMode.fromString(profile.aetherPsiphonMode)
                addAll(listOf("--psiphon-mode", shape.type))
                // The CDN lists feed the fronted transports alone, which direct never uses.
                val cdnIps = profile.aetherPsiphonCdnIps
                    ?.takeIf { it.isNotBlank() && shape != AetherPsiphonMode.DIRECT }
                cdnIps?.let { addAll(listOf("--psiphon-cdn-ips", it)) }
                if (cdnIps != null) {
                    profile.aetherPsiphonCdnSni?.takeIf { it.isNotBlank() }
                        ?.let { addAll(listOf("--psiphon-cdn-sni", it)) }
                }
                if (shape != AetherPsiphonMode.DIRECT) {
                    AetherPsiphonCdnSet.join(AetherPsiphonCdnSet.parse(profile.aetherPsiphonCdnSets))
                        ?.let { addAll(listOf("--psiphon-cdn-sets", it)) }
                }
                profile.aetherPsiphonRegion?.takeIf { it.isNotBlank() }
                    ?.let { addAll(listOf("--psiphon-region", it)) }
                if (profile.aetherPsiphonBundledList != false) {
                    addAll(listOf(PSIPHON_SERVER_ENTRIES, SHIPPED_LIST))
                }
            }
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
        File(context.filesDir, WORK_DIR).apply { mkdirs() }

    /**
     * Runs a core to completion, stopping as soon as [ready] sees what it is waiting for.
     *
     * Used for the one-shot jobs — an endpoint scan, a WARP key renewal — where the
     * core does its work and exits rather than serving a session.
     *
     * @return the line [ready] accepted, or null when the core exited without it
     */
    fun runUntil(
        context: Context,
        arguments: List<String>,
        timeoutMs: Long,
        source: String,
        onOutput: (String) -> Unit,
        ready: (String) -> Boolean,
    ): String? {
        reapStale()
        val exe = binary(context)
        if (!exe.canExecute()) {
            LogUtil.e(AppConfig.TAG, "AetherCore: ${exe.name} is not present or not executable")
            return null
        }
        val process = try {
            ProcessBuilder(listOf(exe.absolutePath) + withShippedList(context, arguments))
                .directory(workDir(context))
                .redirectErrorStream(true)
                .apply { environment().putAll(coreEnvironment(context, markSession = false)) }
                .start()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "AetherCore: failed to launch $source", e)
            return null
        }
        return try {
            val deadline = System.currentTimeMillis() + timeoutMs
            var matched: String? = null
            val reader = process.inputStream.bufferedReader()
            while (System.currentTimeMillis() < deadline) {
                if (!reader.ready() && !process.isAlive) break
                val line = reader.readLine() ?: break
                LogUtil.i(AppConfig.TAG, "AetherCore: $line")
                onOutput(line)
                if (matched == null && ready(line)) {
                    matched = line
                    break
                }
            }
            matched
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "AetherCore: $source ended early", e)
            null
        } finally {
            runCatching { process.destroy() }
        }
    }

    /**
     * The environment every core is started with.
     *
     * The config paths matter: without them the core keeps its WARP identity beside
     * whatever it considers its config path, which on Android is a place an app
     * cannot write, so it would re-register a device on every start.
     */
    internal fun coreEnvironment(context: Context, markSession: Boolean): Map<String, String> {
        val work = workDir(context)
        return buildMap {
            put(OWNER_ENV, android.os.Process.myPid().toString())
            if (markSession) put(SESSION_ENV, "1")
            put("HOME", work.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put(AETHER_CONFIG_ENV, File(work, AetherIdentityManager.BASE_FILE).absolutePath)
            put(AETHER_MASQUE_CONFIG_ENV, File(work, AetherIdentityManager.MASQUE_FILE).absolutePath)
            put(AETHER_WG_CONFIG_ENV, File(work, AetherIdentityManager.WIREGUARD_FILE).absolutePath)
            psiphonBinary(context).takeIf { it.canExecute() }
                ?.let { put(PSIPHON_BIN_ENV, it.absolutePath) }
            transportBinary(context).takeIf { it.canExecute() }?.let { transport ->
                put(TOR_PT_ENV, torTransports.joinToString(";") { "$it=${transport.absolutePath}" })
            }
            // The Psiphon client and lyrebird are programs built for Linux that read
            // Linux certificate paths; Android keeps its roots in the Conscrypt module
            // since Android 14 and on the system image before that. Without this neither
            // can verify a certificate, the Psiphon server list first of all.
            certificateDirectories { File(it).isDirectory }?.let { put(CERT_DIR_ENV, it) }
            psiphonOverlay(context)?.let { put(PSIPHON_CONFIG_ENV, it.absolutePath) }
            put(
                PSIPHON_DIR_ENV,
                File(if (markSession) context.filesDir else context.cacheDir, "aether-psiphon")
                    .apply { mkdirs() }.absolutePath,
            )
        }
    }

    /**
     * [arguments] with the shipped-list placeholder replaced by the unpacked server list.
     *
     * The core is told `shipped-list`, a word it looks for beside itself, which is no
     * use inside an APK. The real file is unpacked from the bundled list on the way in;
     * when the list cannot be unpacked the placeholder is left, so the core goes and
     * fetches a list itself rather than being handed nothing.
     */
    private fun withShippedList(context: Context, arguments: List<String>): List<String> {
        val index = arguments.indexOf(PSIPHON_SERVER_ENTRIES)
        if (index < 0 || index + 1 >= arguments.size) return arguments
        if (arguments[index + 1] != SHIPPED_LIST) return arguments
        val work = workDir(context)
        val entries = PsiphonServerList.entriesFile(
            assetDir = File(Utils.userAssetPath(context)),
            workDir = work,
        ) ?: return arguments
        LogUtil.i(AppConfig.TAG, "AetherCore: Psiphon starts with ${entries.name} from the bundled list")
        return arguments.toMutableList().apply { this[index + 1] = entries.absolutePath }
    }

    /** The transports Tor's pluggable transport is asked for, in the core's own names. */
    internal val torTransports = listOf("obfs4", "obfs4proxy", "snowflake", "conjure", "meek")

    /** The certificate directories of this device that exist, joined the way Go reads them. */
    internal fun certificateDirectories(exists: (String) -> Boolean): String? =
        ANDROID_CERTIFICATE_DIRECTORIES.filter { exists(it) }
            .takeIf { it.isNotEmpty() }?.joinToString(":")

    private val ANDROID_CERTIFICATE_DIRECTORIES =
        listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")

    fun psiphonBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, PSIPHON_BINARY_NAME)

    fun transportBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, TRANSPORT_BINARY_NAME)

    /**
     * The settings file laid over Psiphon's built-in configuration.
     *
     * Android has no resolver configuration a Linux-built program could read, so
     * Psiphon is given resolvers of its own for the names it looks up itself.
     */
    private fun psiphonOverlay(context: Context): File? {
        val dir = File(context.filesDir, "aether-psiphon").apply { mkdirs() }
        val file = File(dir, "config.json")
        val resolvers = SettingsManager.getRemoteDnsServers()
            .filter { com.aras.client.util.Utils.isPureIpAddress(it) }
            .joinToString(",")
            .ifBlank { DEFAULT_RESOLVERS }
        return runCatching {
            file.writeText("""{"PropagationChannels":[],"RemoteDnsAddresses":[""" +
                resolvers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .joinToString(",") { """"$it"""" } + "]}")
            file
        }.getOrNull()
    }

    private const val DEFAULT_RESOLVERS = "1.1.1.1,8.8.8.8"

    /** Whether this build carries the helper processes a Psiphon or Tor profile needs. */
    fun carriersAvailable(context: Context): Boolean =
        psiphonBinary(context).canExecute() && transportBinary(context).canExecute()

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
        reapStale()
        val exe = binary(context)
        if (!exe.canExecute()) {
            LogUtil.e(AppConfig.TAG, "AetherCore: ${exe.name} is not present or not executable")
            return false
        }
        val builder = ProcessBuilder(listOf(exe.absolutePath) + withShippedList(context, core.arguments))
            .directory(workDir(context))
            .redirectErrorStream(true)
        builder.environment().putAll(coreEnvironment(context, markSession = true))
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

    /**
     * Runs [block] with the aether core up, starting it if it is not already running.
     *
     * A latency test measures through the core's loopback SOCKS listener, so the core
     * has to be listening for the measurement to mean anything. A core this started is
     * stopped again afterwards; one that was already up is left alone, so a test during
     * a live session does not tear the session down.
     */
    fun <T> withSession(
        context: Context,
        profile: ProfileItem,
        block: () -> T,
    ): T {
        val startedHere = running() == null
        if (startedHere) {
            val core = AetherCore.of(profile)
            if (!start(context, core) { }) {
                stop()
                error("The aether core did not start; the test cannot reach the tunnel")
            }
        }
        return try {
            block()
        } finally {
            if (startedHere) stop()
        }
    }

    /**
     * Kills a core left behind by an app process that died.
     *
     * Rust ignores SIGPIPE and the core has no parent-death handling, so a core whose
     * owner was killed keeps running and keeps its loopback listener — which then stops
     * the next session from binding it. Every core is started with the owning pid in its
     * environment, and [OWNER_PID] is a process of ours, so anything carrying it and
     * still alive is an orphan of this app and nothing else.
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
                    val env = environ.toString(Charsets.ISO_8859_1)
                    if (!env.contains("$OWNER_ENV=$mine")) return@forEach
                    LogUtil.w(AppConfig.TAG, "AetherCore: reaping core left behind in pid $pid")
                    runCatching {
                        android.os.Process.killProcess(pid)
                    }
                }
        }.onFailure {
            LogUtil.d(AppConfig.TAG, "AetherCore: no stale core to reap (${it.message})")
        }
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
