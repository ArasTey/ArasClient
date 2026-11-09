package com.aras.client.core.aether

import android.content.Context
import com.aras.client.dto.AetherEndpoint
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherProtocol
import com.aras.client.util.LogUtil
import com.aras.client.AppConfig

/** What a scan found: the gateway to dial, and the inner hop when the tunnel is two. */
data class AetherScanResult(
    val endpoint: AetherEndpoint,
    val innerHop: AetherEndpoint? = null,
)

/**
 * Finds a gateway for an Aether profile by watching what a scan core reports.
 *
 * The core does the work — it sweeps the documented ranges and keeps a candidate that
 * carries real traffic — and names the one it kept on its own output. The app's part
 * is to read that line and put the gateway on the profile, which is the whole point of
 * scanning: a sweep that found nothing and said nothing would leave the profile
 * exactly as it was.
 */
object AetherScanner {

    /** A sweep of the whole ranges with an end-to-end check per candidate is slow. */
    const val SCAN_TIMEOUT_MS = 8 * 60_000L

    private val masqueGateway = Regex("""selected MASQUE gateway (\S+)""")
    private val wireguardEndpoint = Regex("""selected WireGuard endpoint (\S+)""")
    private val goolHops = Regex("""using cloudflare edge (\S+) \(outer\) and (\S+) \(inner\)""")
    private val mimHops = Regex("""masque-in-masque ready: (\S+) \(outer\) and (\S+) \(inner\)""")
    private val exitAccepted = Regex("""exit location \S+ accepted""")
    private val exitRejected = Regex("""exit location \S+ rejected""")

    // What the core says when it cannot tell what country the exit is in; aether 2.4
    // words it on a "tunnel ended" line rather than an "exit location" one.
    private val exitUnreadable = Regex("""exit location could not be read""")

    /**
     * Runs a scan core and returns what it settled on, or null when it never named one.
     *
     * @param onOutput every line, so the caller can show the sweep as it happens
     */
    fun scan(
        context: Context,
        profile: ProfileItem,
        onOutput: (String) -> Unit = {},
    ): AetherScanResult? {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        val port = AetherCoreManager.scanPort(profile)
        val ruled = !profile.aetherExitLoc.isNullOrBlank()
        val watcher = Watcher(protocol, ruled)

        AetherCoreManager.runUntil(
            context = context,
            arguments = AetherCoreManager.buildArguments(profile, port, scan = true),
            timeoutMs = SCAN_TIMEOUT_MS,
            source = "aether-scan",
            onOutput = onOutput,
            ready = watcher::evaluate,
        )

        return watcher.answer
    }

    /**
     * Reads a scan's output for one profile, line by line, and says when the answer is in.
     *
     * Without an exit rule the line naming the endpoint is the answer. With one the
     * endpoint and its acceptance are two facts, and aether 2.4 spells them in either
     * order on separate lines — gool names its hops and checks the exit a minute later,
     * mim checks the exit and then says it is ready — so the endpoint is the answer
     * once both are known, and is thrown away when a line rejects the exit or cannot
     * read it, because the core will reconnect and choose fresh endpoints.
     */
    internal class Watcher(private val protocol: AetherProtocol, private val ruled: Boolean) {

        /** What the scan settled on, once [evaluate] says true. */
        var answer: AetherScanResult? = null
            private set

        /** The endpoint the core named but the exit behind it is not vouched for yet. */
        private var pending: AetherScanResult? = null

        /** Whether the exit behind the current endpoints has been accepted. */
        private var exitOk = false

        fun evaluate(line: String): Boolean {
            if (exitRejected.containsMatchIn(line) || exitUnreadable.containsMatchIn(line)) {
                LogUtil.d(AppConfig.TAG, "AetherScanner: the exit was refused, the core will look again")
                pending = null
                exitOk = false
                return false
            }
            // An old core spells endpoint and acceptance on one line; matches() reads
            // such a line only when ruled.
            val named = matches(protocol, line, ruled) ?: parse(protocol, line)
            if (named != null) {
                if (!ruled) {
                    answer = named
                    return true
                }
                pending = named
                if (exitOk) {
                    answer = named
                    return true
                }
                return false
            }
            if (ruled && exitAccepted.containsMatchIn(line)) {
                exitOk = true
                val candidate = pending
                if (candidate != null) {
                    answer = candidate
                    return true
                }
            }
            return false
        }
    }

    /**
     * What ends a scan, line by line.
     *
     * Without an exit rule the line naming the endpoint ends it. With one, the core
     * names its endpoint before it has checked the exit behind it, and looks again
     * when that exit is refused — so a named endpoint on its own is not the answer
     * until the exit has been accepted. (An old core spells both on one line; a
     * current one says the exit on a line of its own, which is what
     * [Watcher] pairs up.)
     */
    internal fun matches(protocol: AetherProtocol, line: String, ruled: Boolean): AetherScanResult? {
        if (ruled && !exitAccepted.containsMatchIn(line)) return null
        return parse(protocol, line)
    }

    /** The endpoint a line names, accepted or not; null when the line names none. */
    private fun parse(protocol: AetherProtocol, line: String): AetherScanResult? {
        if (protocol.twoHops) {
            val hop = if (protocol == AetherProtocol.MIM) mimHops else goolHops
            val match = hop.find(line) ?: return null
            val outer = AetherEndpoint.parse(match.groupValues[1]) ?: return null
            val inner = AetherEndpoint.parse(match.groupValues[2]) ?: return null
            return AetherScanResult(outer, inner)
        }

        val match = if (protocol == AetherProtocol.MASQUE) masqueGateway else wireguardEndpoint
        val endpoint = match.find(line)?.groupValues?.get(1)?.let { AetherEndpoint.parse(it) } ?: return null
        return AetherScanResult(endpoint)
    }

    /**
     * Puts what a scan found on the profile.
     *
     * A two hop tunnel keeps its two gateways in its own fields and clears the single
     * address, since a single one would be read as the endpoint the core dials.
     */
    fun apply(profile: ProfileItem, result: AetherScanResult) {
        val inner = result.innerHop
        if (inner != null) {
            profile.aetherWiwOuter = result.endpoint.toString()
            profile.aetherWiwInner = inner.toString()
            profile.server = null
            profile.serverPort = null
        } else {
            profile.server = result.endpoint.host
            profile.serverPort = result.endpoint.port.toString()
        }
        LogUtil.i(
            AppConfig.TAG,
            "AetherScanner: profile now dials ${result.endpoint}" +
                (inner?.let { " through $it" } ?: ""),
        )
    }
}
