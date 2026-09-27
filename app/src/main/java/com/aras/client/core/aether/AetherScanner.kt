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
        var found: AetherScanResult? = null

        AetherCoreManager.runUntil(
            context = context,
            arguments = AetherCoreManager.buildArguments(profile, port, scan = true),
            timeoutMs = SCAN_TIMEOUT_MS,
            source = "aether-scan",
            onOutput = onOutput,
        ) { line -> matches(protocol, line, ruled)?.let { found = it; true } == true }

        return found
    }

    /**
     * What ends a scan, line by line.
     *
     * Without an exit rule the line naming the endpoint ends it. With one, the core
     * names its endpoint before it has checked the exit behind it, and looks again
     * when that exit is refused — so a named endpoint on its own is not the answer
     * until the exit has been accepted.
     */
    internal fun matches(protocol: AetherProtocol, line: String, ruled: Boolean): AetherScanResult? {
        exitRejected.find(line)?.let { rejected ->
            LogUtil.d(AppConfig.TAG, "AetherScanner: $rejected, the core will look again")
        }
        if (ruled && !exitAccepted.containsMatchIn(line)) return null

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
