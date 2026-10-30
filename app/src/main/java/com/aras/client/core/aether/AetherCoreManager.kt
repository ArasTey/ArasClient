package com.aras.client.core.aether

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.AetherEndpoint
import com.aras.client.dto.AetherRange
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherFingerprint
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
import com.aras.client.enums.EConfigType
import com.aras.client.fmt.AetherFmt
import com.aras.client.util.Utils
import com.aras.client.util.LogUtil
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Owns the aether core process: one live session at a time, the command line a profile
 * runs as, readiness probing, and cleanup.
 *
 * The core is a separate process, not a library — it is the only thing that can dial a
 * WARP gateway with a scanned endpoint and a WARP identity, so an Aether profile is
 * carried by dialing this process's loopback SOCKS listener.
 */
object AetherCoreManager {

    /** Sentinel the output thread puts when the core's stream ends. */
    private val EOF = String(charArrayOf('\u0000'))

    /**
     * Drains a core's output on a thread of its own and puts each line on [lines].
     *
     * Reading here would block for as long as the core runs: a session core runs until
     * it is stopped, so the caller would never get as far as waiting for the listener.
     *
     * Nothing may escape this thread — an uncaught exception on it is fatal to the app
     * whatever the caller's cleanup did. The core going away, the stream closing and an
     * interrupt on teardown are all ordinary here, not faults.
     */
    private fun pumpOutput(
        process: Process,
        lines: LinkedBlockingQueue<String>,
        onOutput: (String) -> Unit,
        onEnded: () -> Unit = {},
    ): Thread = Thread {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                LogUtil.i(AppConfig.TAG, "AetherCore: $line")
                lines.put(line)
                onOutput(line)
            }
        } catch (_: Throwable) {
            // The core ended or the stream closed; the marker below still goes in.
        } finally {
            runCatching { lines.offer(EOF) }
            runCatching { onEnded() }
        }
    }.apply {
        isDaemon = true
        name = "aether-core-output"
        start()
    }

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

    /**
     * The options that name the HTTP proxies a command written by hand may open beside the SOCKS listeners: the core's
     * own, Tor's and Psiphon's. The app names none of them, but the ports they name are the core's as well.
     */
    const val HTTP_PROXY = "--http-proxy"
    const val TOR_HTTP = "--tor-http"
    const val PSIPHON_HTTP = "--psiphon-http"

    /**
     * The ports the core's SOCKS listeners take where a command written by hand does not name them (aether cli.rs): its
     * own 1819, Tor's 1820 and Psiphon's 1821. The HTTP proxies are off unless named, and the app's own commands name
     * every listener they run.
     */
    internal val defaultListenerPorts = mapOf("--bind" to 1819, TOR_BIND to 1820, PSIPHON_BIND to 1821)

    /** The Psiphon client, run beside the core. */
    const val PSIPHON_BINARY_NAME = "libpsiphon-tunnel-core.so"

    /** The pluggable transport Tor's bridges run through; it speaks every one the core asks for. */
    const val TRANSPORT_BINARY_NAME = "liblyrebird.so"

    const val PSIPHON_BIN_ENV = "AETHER_PSIPHON_BIN"
    const val TOR_PT_ENV = "AETHER_TOR_PT"
    const val CERT_DIR_ENV = "SSL_CERT_DIR"
    const val PSIPHON_DIR_ENV = "AETHER_PSIPHON_DIR"

    /**
     * The server entries Psiphon starts from. Its own list download resolves names with
     * the system resolver, which a Linux-built program cannot read on Android, so without
     * these a fresh session has no server to dial.
     */
    const val PSIPHON_ENTRIES_ENV = "AETHER_PSIPHON_SERVER_ENTRIES"

    /**
     * The core's word that Psiphon has a tunnel: "psiphon is ready". The start waits for it as well as for the
     * greeting's answer where the app dials Psiphon: Psiphon's own listener answers the greeting before it has a
     * tunnel. The core holds that listener itself and passes clients on to Psiphon only once it has said this word,
     * so the answer comes with the word there.
     */
    private val psiphonReady = Regex("""psiphon is ready""")

    /**
     * The core's word that it did not start the session for want of an ECH key: ECH is on, and it had no key it
     * could offer, so it stopped rather than send the server name in the clear.
     */
    private val noEchKey = Regex("""ECH is on but there is no ECH key to offer""")

    /** The label an error of the core starts its words with, the kind it is of (aether error.rs, AetherError). */
    private val errorLabel = Regex("""^(?:io|quic|h3|tls|ech|masque|capsule|api|other|identity refused): """)

    /** The levels at which the core writes its info lines, the ready word among them. */
    private val infoLevels = setOf("info", "debug", "trace")

    private val logLevels = setOf("ERROR", "WARN", "INFO", "DEBUG", "TRACE")

    /**
     * The loopback SOCKS listeners are asked about their greeting over at most one connection
     * per port at a time; see [SocksAnswers] for why a plain connect is not enough.
     */
    private val socksAnswers = SocksAnswers(PROBE_TIMEOUT_MS)

    /** The work directory, shared with [AetherIdentityManager]. */
    const val WORK_DIR = "aether"

    /** How long a scan or a key renewal may take before it is given up on. */
    const val ONE_SHOT_TIMEOUT_MS = 2 * 60_000L

    /** A free loopback port for a one-shot core, away from the session's own. */
    fun scanPort(profile: ProfileItem): Int {
        val sessionPort = listenPort(profile)
        return if (sessionPort + 1 in 1..65535) sessionPort + 1 else sessionPort - 1
    }

    @Volatile
    private var session: Session? = null

    /** Whether the session core that ended on its own last stopped for want of an ECH key; see [stoppedMessage]. */
    @Volatile
    private var stoppedForEchKey = false

    /**
     * The core's words that Tor or Psiphon inside the tunnel had ended, when that is why the app ended the session
     * core that ended last; see [chainEndWords] and [stoppedMessage].
     */
    @Volatile
    private var stoppedChainEnd: String? = null

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
        // A Psiphon or Tor profile is the carrier alone, so it never scans: there is
        // no WARP endpoint to find.
        val tor = when (profile.configType) {
            EConfigType.TOR -> AetherTor.ONLY
            else -> AetherTor.fromString(profile.aetherTor).takeUnless { scan && it != AetherTor.REVERSE }
                ?: AetherTor.OFF
        }
        val psiphon = when (profile.configType) {
            EConfigType.PSIPHON -> AetherPsiphon.ONLY
            else -> AetherPsiphon.fromString(profile.aetherPsiphon)
                .takeUnless { scan && it != AetherPsiphon.REVERSE } ?: AetherPsiphon.OFF
        }
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
            settingValue(profile.aetherDns)?.let { addAll(listOf("--dns", it)) }
            // A scan keeps the exit rule too, so it ends on an endpoint the session accepts.
            settingValue(profile.aetherExitLoc)?.let { addAll(listOf("--exit-loc", it)) }

            // The exit node: the core's own dialer points at the exit-node
            // SOCKS inbound of the app's Xray config (CoreConfigManager's
            // applyAetherExitNode serves it on listenPort + offset), so
            // everything this core sends out leaves through whatever outbound
            // the session tagged exit-node.
            profile.aetherExitNode?.takeIf { it.isNotBlank() }
                ?.let {
                    addAll(
                        listOf(
                            "--upstream",
                            "socks5://${AppConfig.LOOPBACK}:${listenPort(profile) + AppConfig.AETHER_EXIT_IN_PORT_OFFSET}",
                        )
                    )
                }

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
            // aether 2.3: the key lookup and the name inside the encrypted hello
            // are configurable; an explicit key stays a job for the raw command.
            if (protocol.overMasque && profile.aetherEch == true) {
                addAll(listOf("--ech", "auto"))
                settingValue(profile.aetherEchDns)?.let { addAll(listOf("--ech-dns", it)) }
                settingValue(profile.aetherEchDomain)?.let { addAll(listOf("--ech-domain", it)) }
            }
            // aether 2.3: the name the MASQUE handshakes put in their ClientHello
            // (the :authority of the CONNECT stays cloudflareaccess.com).
            settingValue(profile.aetherMasqueSni)?.takeIf { protocol.overMasque }
                ?.let { addAll(listOf("--masque-sni", it)) }
            // aether 2.3.7: where the calls to the WARP API (register and renew)
            // go, the server name of their ClientHello and their HTTP host; each
            // left blank follows the next, as the core has them.
            settingValue(profile.aetherApiAddress)?.let { addAll(listOf("--api-address", it)) }
            settingValue(profile.aetherApiSni)?.let { addAll(listOf("--api-sni", it)) }
            settingValue(profile.aetherApiHost)?.let { addAll(listOf("--api-host", it)) }
            // The ClientHello fingerprint (PattNG's four choices); the core's
            // own TLS 1.2 suites and GREASE behaviour follow the chosen client.
            if (protocol.overMasque) {
                addAll(AetherFingerprint.fromString(profile.aetherFingerprint).arguments)
            }

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
                        .mapNotNull(::settingValue)
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
                // The CDN lists feed the fronted transports alone, which direct never uses; the
                // server names count only beside an IP list of one's own, since the built-in list
                // comes whole.
                val cdnIps = settingValue(profile.aetherPsiphonCdnIps)?.takeIf { shape != AetherPsiphonMode.DIRECT }
                cdnIps?.let { addAll(listOf("--psiphon-cdn-ips", it)) }
                if (cdnIps != null) {
                    settingValue(profile.aetherPsiphonCdnSni)?.let { addAll(listOf("--psiphon-cdn-sni", it)) }
                }
                // aether 2.3.5 and later take the list again: which edge lists built into the
                // Psiphon client the fronting scan tries, beside addresses of one's own.
                if (shape != AetherPsiphonMode.DIRECT) {
                    AetherPsiphonCdnSet.join(AetherPsiphonCdnSet.parse(profile.aetherPsiphonCdnSets))
                        ?.let { addAll(listOf("--psiphon-cdn-sets", it)) }
                }
                settingValue(profile.aetherPsiphonRegion)?.let { addAll(listOf("--psiphon-region", it)) }

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

    /**
     * The text of a profile setting as the value of an option of the core: trimmed, and null when it is blank or starts
     * with '-'. No value of these settings starts so, and one that did would read as an option of its own wherever the
     * app looks options up word by word: a link carrying dns=--upstream would leave the core without the upstream the
     * app gives it, so that it dialled out around Xray.
     */
    internal fun settingValue(value: String?): String? =
        value?.trim()?.takeUnless { it.isEmpty() || it.startsWith('-') }

    // ------------------------------------------------------------- argv tools

    /** [arguments] without every [option] and the value after it. */
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

    /** [arguments] with the listener the app dials on the loopback port [port], whatever they named there. */
    internal fun withListener(arguments: List<String>, port: Int): List<String> =
        withoutOption(arguments, "--bind") + listOf("--bind", "${AppConfig.LOOPBACK}:$port")

    /** The value after the last [flag] in [arguments]; the last one is the one the core keeps. */
    internal fun valueAfter(arguments: List<String>, flag: String): String? =
        arguments.lastIndexOf(flag).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }

    /** The port of the address after the listener option [flag], as the core reads it; null when there is none. */
    internal fun portAfter(arguments: List<String>, flag: String): Int? =
        listenerAddressAfter(arguments, flag)?.substringAfterLast(':', "")?.toIntOrNull()

    /**
     * The address after the listener option [flag] in [arguments], as the core reads it: its own listener's (--bind) as
     * written, Tor's, Psiphon's and the HTTP proxies' trimmed (aether lib.rs, tor.rs and psiphon.rs); null when there is
     * none. Every reader of a listener's address or port goes by it, so that the app and the core agree on it.
     */
    internal fun listenerAddressAfter(arguments: List<String>, flag: String): String? =
        valueAfter(arguments, flag)?.let { if (flag == "--bind") it else it.trim(::isRustWhitespace) }

    /** The address of the listener the app dials, see [listenerFlagOf], as the core reads it; null when none is named. */
    internal fun listenerAddressOf(arguments: List<String>): String? = listenerAddressAfter(arguments, listenerFlagOf(arguments))

    /** The port of the listener the app dials; null when [arguments] name no address for it. */
    internal fun listenerPortOf(arguments: List<String>): Int? = listenerAddressOf(arguments)?.substringAfterLast(':', "")?.toIntOrNull()

    /**
     * The option naming the listener the app dials: Psiphon's when Psiphon runs inside the tunnel, Tor's when Tor does,
     * the core's own otherwise. Both inside at once is refused by the editor; a command written that way is dialled on
     * Psiphon's.
     */
    internal fun listenerFlagOf(arguments: List<String>): String = when {
        // Alone, Tor or Psiphon serves the core's own listener, and the core starts nothing else (aether lib.rs).
        aloneOf(arguments) != null -> "--bind"
        psiphonModeOf(arguments) == AetherPsiphon.CHAIN -> PSIPHON_BIND
        torModeOf(arguments) == AetherTor.CHAIN -> TOR_BIND
        else -> "--bind"
    }

    /**
     * The carrier [arguments] runs alone, with no tunnel: Tor, before Psiphon when both are asked to, as the core starts
     * them and [aloneOf] names them; null when neither runs alone. The core then starts nothing else.
     */
    internal fun aloneOf(arguments: List<String>): String? = when {
        torModeOf(arguments) == AetherTor.ONLY -> TOR_NAME
        psiphonModeOf(arguments) == AetherPsiphon.ONLY -> PSIPHON_NAME
        else -> null
    }

    /** The carriers as the session messages name them, beside the names of [AetherProtocol]. */
    private const val TOR_NAME = "TOR"
    private const val PSIPHON_NAME = "PSIPHON"

    /** Where Tor stands in the tunnel [arguments] run, read the way the core reads it: the last mode flag wins. */
    internal fun torModeOf(arguments: List<String>): AetherTor = arguments.fold(AetherTor.OFF) { mode, word ->
        when (word) {
            "--tor" -> AetherTor.CHAIN
            "--tor-reverse" -> AetherTor.REVERSE
            "--tor-only" -> AetherTor.ONLY
            else -> mode
        }
    }

    /** Where Psiphon stands in the tunnel [arguments] run, read the way the core reads it: the last mode flag wins. */
    internal fun psiphonModeOf(arguments: List<String>): AetherPsiphon = arguments.fold(AetherPsiphon.OFF) { mode, word ->
        when (word) {
            "--psiphon" -> AetherPsiphon.CHAIN
            "--psiphon-reverse" -> AetherPsiphon.REVERSE
            "--psiphon-only" -> AetherPsiphon.ONLY
            else -> mode
        }
    }

    /** The protocol [arguments] select, read the way the core reads it: the last of --protocol wins. */
    internal fun protocolOf(arguments: List<String>): AetherProtocol =
        valueAfter(arguments, "--protocol")?.let { AetherProtocol.fromString(it) } ?: AetherProtocol.WIREGUARD

    /**
     * Whitespace as the core trims it, Rust's char::is_whitespace, the Unicode White_Space property. Kotlin's trim()
     * takes U+001C to U+001F as well and leaves U+0085 out, so an address it trims could be one the core does not.
     */
    internal fun isRustWhitespace(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c == '\u0085' || c == '\u00A0' || c == '\u1680' || c in '\u2000'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u202F' || c == '\u205F' || c == '\u3000'

    /**
     * True when the core takes [address] for a listener, as Rust's SocketAddr reads it: an IPv4 address, or an IPv6 one
     * in brackets, then a colon and a port from 0 to 65535, leading zeros allowed. A host name is no such address.
     */
    internal fun isSocketAddress(address: String): Boolean {
        val colon = address.lastIndexOf(':')
        if (colon <= 0 || !isDecimalUpTo(address.substring(colon + 1), 65_535)) return false
        val host = address.substring(0, colon)
        return if (host.startsWith('[') && host.endsWith(']')) isIpv6Literal(host.substring(1, host.length - 1)) else isIpv4Literal(host)
    }

    /** Four numbers from 0 to 255, joined by dots, none with a leading zero, as Rust reads an IPv4 address. */
    internal fun isIpv4Literal(text: String): Boolean {
        val parts = text.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } && (part == "0" || !part.startsWith('0')) &&
                part.toInt() <= 255
        }
    }

    /**
     * An IPv6 address as Rust reads one: up to eight groups of one to four hex digits joined by colons, one "::" at most
     * standing for one or more groups of zeros, an IPv4 address in place of the last two groups, and a scope after a "%",
     * a number a u32 holds.
     */
    internal fun isIpv6Literal(text: String): Boolean {
        val address = text.substringBefore('%')
        if ('%' in text && !isDecimalUpTo(text.substringAfter('%'), 4_294_967_295)) return false
        val halves = address.split("::")
        if (halves.size > 2) return false
        val head = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
        val tail = if (halves.size < 2 || halves[1].isEmpty()) emptyList() else halves[1].split(':')
        val groups = head + tail
        var count = 0
        for ((index, group) in groups.withIndex()) {
            count += when {
                '.' in group -> if (index == groups.lastIndex && isIpv4Literal(group) && (halves.size == 1 || tail.isNotEmpty())) 2 else return false
                group.isNotEmpty() && group.length <= 4 && group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> 1
                else -> return false
            }
        }
        return if (halves.size == 2) count <= 7 else count == 8
    }

    /** One or more ASCII digits whose value, leading zeros and all, is at most [max], as Rust reads a port or a scope. */
    private fun isDecimalUpTo(text: String, max: Long): Boolean {
        if (text.isEmpty() || !text.all { it in '0'..'9' }) return false
        val significant = text.trimStart('0')
        return significant.length <= 10 && (significant.isEmpty() || significant.toLong() <= max)
    }

    /**
     * Whether a listener bound on [host], an IPv4 address, or an IPv6 one in brackets, as [isSocketAddress] takes it,
     * answers a connection to 127.0.0.1, where the app dials the core: 127.0.0.1 itself and every IPv4 address, 0.0.0.0;
     * and, since Android, as Linux, opens an IPv6 socket to IPv4 as well unless told otherwise, which the core never
     * does (tokio and mio set no IPV6_V6ONLY), every address of IPv6, ::, and the IPv4-mapped spellings of those two.
     */
    internal fun reachesLoopback(host: String): Boolean {
        if (!host.startsWith('[')) return host == AppConfig.LOOPBACK || host == "0.0.0.0"
        val groups = ipv6Groups(host.removePrefix("[").removeSuffix("]").substringBefore('%')) ?: return false
        if (groups.all { it == 0 }) return true
        val mapped = (0..4).all { groups[it] == 0 } && groups[5] == 0xFFFF
        return mapped && ((groups[6] == 0x7F00 && groups[7] == 0x0001) || (groups[6] == 0 && groups[7] == 0))
    }

    /** The eight 16-bit groups of the IPv6 address [text], as [isIpv6Literal] takes it without a scope; null for none. */
    internal fun ipv6Groups(text: String): IntArray? {
        if ('%' in text || !isIpv6Literal(text)) return null
        fun groupsOf(part: String): List<Int> = if (part.isEmpty()) emptyList() else part.split(':').flatMap { group ->
            if ('.' in group) {
                val octets = group.split('.').map { it.toInt() }
                listOf((octets[0] shl 8) or octets[1], (octets[2] shl 8) or octets[3])
            } else {
                listOf(group.toInt(16))
            }
        }
        val halves = text.split("::")
        val head = groupsOf(halves[0])
        val tail = if (halves.size == 2) groupsOf(halves[1]) else emptyList()
        val zeros = if (halves.size == 2) 8 - head.size - tail.size else 0
        return (head + List(zeros) { 0 } + tail).toIntArray()
    }

    // ------------------------------------------------------------- session ports

    /** A loopback port a session takes as it starts, one of its core's listeners. */
    internal data class SessionPort(val port: Int, val secondary: Boolean = false)

    /** Why a start refuses its session on [core], in the words the caller reports; null when it may start. */
    internal fun problemsOf(core: AetherCore, free: (SessionPort) -> Boolean = ::isFree): String? {
        core.invalidListener?.let { return "The aether command gives $it an address the core does not take; write an IP address and a port, such as 127.0.0.1:20808" }
        core.unreachableListener?.let { return "The aether command gives $it an address the app does not connect to: the app connects to the core on 127.0.0.1" }
        core.sharedPort?.let { return "The aether command names port $it for two of the core's listeners; give each listener a port of its own" }
        core.ports.firstOrNull { !free(SessionPort(it)) }?.let {
            return "Port $it, which the aether core listens on, is in use by another app; change the listen port in the settings, or the ports of the command"
        }
        return null
    }

    /**
     * True when [port] can be bound on the loopback address now, over TCP, the way the core's SOCKS
     * listeners bind it. The probe reuses the address as the listeners of the core do, so a connection
     * of the previous session still in TIME_WAIT does not count; a listener does.
     */
    internal fun isFree(port: SessionPort): Boolean = try {
        if (port.port in 1..65535) {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(AppConfig.LOOPBACK, port.port))
            }
            true
        } else {
            false
        }
    } catch (_: IOException) {
        false
    }

    /** The first of [ports], in their order, that [free] says cannot be bound; null when all can. */
    internal fun firstTaken(ports: List<SessionPort>, free: (SessionPort) -> Boolean = ::isFree): SessionPort? =
        ports.firstOrNull { !free(it) }

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
            ProcessBuilder(listOf(exe.absolutePath) + arguments)
                .directory(workDir(context))
                .redirectErrorStream(true)
                .apply { environment().putAll(coreEnvironment(context, markSession = false)) }
                .start()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "AetherCore: failed to launch $source", e)
            return null
        }

        // The core's output is read on its own thread. Reading it here would block for
        // as long as the core is quiet, and the deadline below is only ever checked
        // between lines - so a core that says nothing would sit here long past it, with
        // the screen showing nothing at all.
        val lines = LinkedBlockingQueue<String>()
        val reader = pumpOutput(process, lines, {})

        return try {
            val deadline = System.currentTimeMillis() + timeoutMs
            var matched: String? = null
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) {
                    LogUtil.w(AppConfig.TAG, "AetherCore: $source gave up after ${timeoutMs / 1000}s")
                    break
                }
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: run {
                    LogUtil.w(AppConfig.TAG, "AetherCore: $source timed out after ${timeoutMs / 1000}s")
                    break
                }
                if (line === EOF) break
                onOutput(line)
                if (matched == null && ready(line)) {
                    matched = line
                    break
                }
            }
            matched
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            // Destroying the core closes its output, which ends the reader on its own.
            // Interrupting it instead races the stream and used to leave an
            // InterruptedException to escape the reader thread and take the app down.
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
            psiphonEntriesFile(context)?.let { put(PSIPHON_ENTRIES_ENV, it.absolutePath) }
            put(
                PSIPHON_DIR_ENV,
                File(if (markSession) context.filesDir else context.cacheDir, "aether-psiphon")
                    .apply { mkdirs() }.absolutePath,
            )
        }
    }

    /**
     * The transports Tor's pluggable transport is asked for, in the core's own names.
     * One binary speaks all of them, so the same path is offered for each.
     */

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
     * The server entries Psiphon starts with: the list the asset section brought, when
     * there is one, else the signed list shipped in the APK, so a fresh install has
     * servers before it can reach a list on its own.
     */
    private fun psiphonEntriesFile(context: Context): File? {
        val work = workDir(context)
        val userList = File(Utils.userAssetPath(context), PsiphonServerList.ASSET_NAME)
        if (userList.isFile) {
            PsiphonServerList.entriesFile(
                packed = runCatching { userList.readBytes() }.getOrNull(),
                stamp = "user:${userList.length()}:${userList.lastModified()}",
                workDir = work,
            )?.let { entries ->
                LogUtil.i(AppConfig.TAG, "AetherCore: Psiphon starts from the asset section's server list")
                return entries
            }
        }
        val packaged = runCatching {
            context.assets.open(PsiphonServerList.ASSET_NAME).use { it.readBytes() }
        }.getOrNull() ?: return null
        return PsiphonServerList.entriesFile(
            packed = packaged,
            stamp = "apk:${packaged.size}:${packaged.contentHashCode()}",
            workDir = work,
        )
    }

    /** Whether this build carries the helper processes a Psiphon or Tor profile needs. */
    fun carriersAvailable(context: Context): Boolean =
        psiphonBinary(context).canExecute() && transportBinary(context).canExecute()

    /**
     * Starts a core on [arguments]. It uses, or registers where there are none, the keys in the
     * identity folder.
     *
     * @param context the app's
     * @param core the profile's core, whose listener the app dials
     * @param onOutput every line the core writes, already relayed into the app log
     * @param onExit called once the core has ended on its own and nothing stopped it first;
     *   the [String] carries the core's words that Tor or Psiphon inside the tunnel had ended,
     *   where that is why, see [chainEndWords]
     * @return true when the listener came up within the wait
     */
    fun start(
        context: Context,
        core: AetherCore,
        onOutput: (String) -> Unit,
        onExit: (String?) -> Unit = {},
    ): Boolean {
        stop()
        reapStale()
        val exe = binary(context)
        if (!exe.canExecute()) {
            LogUtil.e(AppConfig.TAG, "AetherCore: ${exe.name} is not present or not executable")
            return false
        }
        stoppedForEchKey = false
        stoppedChainEnd = null
        val started = try {
            ProcessBuilder(listOf(exe.absolutePath) + core.arguments)
                .directory(workDir(context))
                .redirectErrorStream(true)
                .apply { environment().putAll(coreEnvironment(context, markSession = true)) }
                .start()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "AetherCore: failed to launch the core", e)
            return false
        }
        val next = Session(core.port, core.arguments, started)
        session = next

        // The session core runs until it is stopped, so its output is drained in the
        // background; reading it here would block the start forever.
        pumpOutput(started, LinkedBlockingQueue(), { line ->
            onOutput(line)
            watch(next, line)
        }) { ended(next, onExit) }

        val ready = awaitListener(next)
        if (!ready) {
            LogUtil.e(AppConfig.TAG, "AetherCore: listener on ${next.port} never came up")
            stop()
        }
        return ready
    }

    /**
     * Reads, over the drain thread, the core's words that the listener the app dials is ready:
     * the word that Psiphon has its tunnel where the app dials Psiphon, and that it stopped for
     * want of an ECH key. And the core's words that Tor or Psiphon inside the tunnel had ended:
     * the core ends itself on that line (aether lib.rs, watch_chain), and the session stops with
     * it rather than wait for Xray to notice the dead listener.
     */
    private fun watch(target: Session, line: String) {
        if (line.isBlank()) return
        if (psiphonReady.containsMatchIn(line)) target.wordSeen = true
        if (isNoEchKeyWord(line)) target.noEchKey = true
        if (target.chainEnd == null && session === target) {
            chainEndWords(line, target.arguments)?.let { words ->
                target.chainEnd = words
                LogUtil.w(AppConfig.TAG, "AetherCore: ending the session core, Tor or Psiphon inside the tunnel has ended: $words")
                runCatching { target.process.destroy() }
            }
        }
    }

    /**
     * Reports a session core that ended, calling [onExit] where the caller asked for it: a core
     * the app stopped or a start that replaced it has no such report to make, look at [session].
     */
    private fun ended(target: Session, onExit: (String?) -> Unit) {
        if (session !== target) return
        stoppedForEchKey = target.noEchKey
        stoppedChainEnd = target.chainEnd
        if (target.chainEnd != null) {
            LogUtil.e(AppConfig.TAG, "AetherCore: the session core was ended, Tor or Psiphon inside the tunnel had ended")
        } else {
            LogUtil.e(AppConfig.TAG, "AetherCore: the session core exited on its own")
        }
        runCatching { onExit(target.chainEnd) }
    }

    /**
     * True when the listener the app dials is Psiphon's, inside the tunnel or alone: it answers the
     * greeting before it carries anything, so readiness needs the core's word as well.
     */
    internal fun readyNeedsWord(arguments: List<String>): Boolean = when (aloneOf(arguments)) {
        PSIPHON_NAME -> true
        TOR_NAME -> false
        else -> psiphonModeOf(arguments) == AetherPsiphon.CHAIN
    }

    /** True when [line] is the core's word that the listener the app dials carries traffic now. */
    internal fun isReadyWord(line: String): Boolean = psiphonReady.containsMatchIn(line)

    /** True when [line] is the core's word that it stopped for want of an ECH key; see [noEchKey]. */
    internal fun isNoEchKeyWord(line: String): Boolean = noEchKey.containsMatchIn(line)

    /**
     * The core's words, as "tor: ..." or "psiphon: ...", when [line] says that Tor or Psiphon inside the tunnel, whose
     * listener a session started with [arguments] is dialled on, has ended or could not start; null for any other line,
     * and for a session dialled on the core's own listener. The core logs it and ends (aether lib.rs, watch_chain:
     * "[-] {name}: {e}" at the error level under the target "aether"), and the session stops with these words. The
     * label of the error's kind, "other: " or "io: ", is left out of the words.
     */
    internal fun chainEndWords(line: String, arguments: List<String>): String? {
        val header = logHeader(line.trim()) ?: return null
        if (header[1] != "ERROR" || header[2] != "aether") return null
        val carrier = when (listenerFlagOf(arguments)) {
            TOR_BIND -> "tor"
            PSIPHON_BIND -> "psiphon"
            else -> return null
        }
        val message = outputMessage(line)
        val prefix = "[-] $carrier: "
        if (!message.startsWith(prefix)) return null
        return "$carrier: " + message.removePrefix(prefix).replaceFirst(errorLabel, "")
    }

    /** True when the SOCKS server on the loopback [port] answers the greeting; see [SocksAnswers]. */
    internal fun answersSocks(port: Int): Boolean = socksAnswers.answers(port)

    /** True when a core started with [arguments] writes its info lines, the ready word among them. */
    internal fun showsInfo(arguments: List<String>): Boolean =
        "--verbose" in arguments || (valueAfter(arguments, "--log-level") ?: DEFAULT_LOG_LEVEL) in infoLevels

    /**
     * What to tell the user of the session core that ended on its own last, with [noEchKey] when it ended for want of
     * an ECH key, and [chainEndWords] words as [chainEnded] where Tor or Psiphon inside the tunnel had ended.
     */
    internal fun stoppedMessage(noEchKey: Boolean, chainEnded: Boolean): Int = when {
        chainEnded -> R.string.aether_core_stopped_chain_ended
        noEchKey -> R.string.aether_core_stopped_no_ech_key
        else -> R.string.aether_core_stopped
    }

    /** What to tell the user of the session core that ended on its own last, in the words it ended with. */
    fun stoppedMessage(context: Context): String =
        context.getString(stoppedMessage(stoppedForEchKey, stoppedChainEnd != null)).let { message ->
            val chainEnd = stoppedChainEnd
            if (chainEnd != null) context.getString(R.string.aether_core_stopped_chain_ended, chainEnd) else message
        }

    /** The header a log line starts with: [time LEVEL target], read as its fields; null where the line carries none. */
    private fun logHeader(line: String): List<String>? {
        if (!line.startsWith('[')) return null
        val headerEnd = line.indexOf(']').takeIf { it > 0 } ?: return null
        return line.substring(1, headerEnd)
            .split(' ')
            .filter(String::isNotEmpty)
            .takeIf { it.size >= 3 && it[1] in logLevels }
    }

    /** A log line without its header, the words of the core itself. */
    private fun outputMessage(line: String): String {
        val text = line.trim()
        return if (logHeader(text) != null) text.substringAfter(']').trim() else text
    }

    private fun awaitListener(target: Session): Boolean {
        val deadline = System.currentTimeMillis() + MAX_READY_WAIT_MS
        val needsWord = readyNeedsWord(target.arguments)
        val wearsInfo = showsInfo(target.arguments)
        while (System.currentTimeMillis() < deadline) {
            if (session !== target) return false
            // A quieter core never writes the ready word; the listener alone decides then.
            if (socksAnswers.answers(target.port) && (!needsWord || !wearsInfo || target.wordSeen)) return true
            val alive = try {
                target.process.isAlive
            } catch (_: IllegalStateException) {
                false
            }
            if (!alive) return false
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
            if (!start(context, core, onOutput = {})) {
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

    /** The core of the running session, or null without one still alive. */
    fun running(): AetherCore? =
        session?.takeIf { try { it.process.isAlive } catch (_: IllegalStateException) { false } && probe(it.port) }?.core

    fun stop() {
        val current = session
        session = null
        current?.let {
            runCatching { it.process.destroy() }
            try {
                it.process.waitFor(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * A session core: the core of it, the port the app dials, the process, and what the output
     * drain has read that readiness and the way the session ended go by.
     */
    private class Session(val port: Int, val arguments: List<String>, val process: Process) {
        /** Whether the core said that Psiphon has its tunnel, where the app dials Psiphon. */
        @Volatile
        var wordSeen: Boolean = false

        /** Whether the core said it stopped for want of an ECH key. */
        @Volatile
        var noEchKey: Boolean = false

        /** The core's words that Tor or Psiphon inside the tunnel had ended; see [chainEndWords]. */
        @Volatile
        var chainEnd: String? = null

        val core: AetherCore = AetherCore(arguments)
    }
}
