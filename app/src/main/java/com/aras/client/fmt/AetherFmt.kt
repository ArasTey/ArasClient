package com.aras.client.fmt

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
import com.aras.client.enums.EConfigType
import com.aras.client.extension.idnHost
import com.aras.client.extension.nullIfBlank
import com.aras.client.util.Utils
import java.net.URI

/**
 * Share-link format for an Aether profile.
 *
 * The wire values are the aether core's own — protocol `wg` rather than `wireguard`,
 * IP version `v6` rather than `ipv6`, obfuscation carried under `noize` — so links
 * produced by the Aether app or by PattNG import here unchanged, and links produced
 * here import there.
 *
 * The credential is the endpoint: a single `host:port` for masque and wg, and an
 * `outer`/`inner` pair for the two-hop gool and mim tunnels.
 */
object AetherFmt : FmtBase() {

    private const val SCHEME = "aether://"

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.AETHER)

        val uri = URI(Utils.fixIllegalUrl(str))
        val queryParam = if (uri.rawQuery.isNullOrEmpty()) emptyMap() else getQueryParam(uri)
        val protocol = AetherProtocol.fromString(queryParam["protocol"])

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "Aether" }
        config.aetherProtocol = protocol.type
        config.aetherTransport = AetherTransport.fromString(queryParam["transport"]).type
        config.aetherScanMode = AetherScanMode.fromString(queryParam["scan"]).type
        config.aetherObfuscation = AetherObfuscation.fromString(queryParam["noize"]).type
        config.aetherIpVersion = AetherIpVersion.fromString(queryParam["ip"]).type
        config.aetherFragment = queryParam["fragment"] == "1"
        config.aetherFragmentSize =
            AetherRange.parse(queryParam["fragment_size"], AetherRange.FRAGMENT_SIZE)?.toString()
        config.aetherFragmentDelay =
            AetherRange.parse(queryParam["fragment_delay"], AetherRange.FRAGMENT_DELAY)?.toString()
        config.aetherEch = queryParam["ech"] == "1"
        config.aetherDns = queryParam["dns"]
        config.aetherExitLoc = queryParam["exit_loc"]
        config.aetherListenPort = listenPortOf(queryParam["listen"])?.let(::storedListenPort)
        config.aetherPsiphon =
            AetherPsiphon.fromString(queryParam["psiphon"]).type.takeUnless { it == AetherPsiphon.OFF.type }
        config.aetherPsiphonMode = queryParam["psiphon_mode"]?.let { AetherPsiphonMode.fromString(it).type }
        config.aetherPsiphonCdnIps = queryParam["cdn_ips"]
        config.aetherPsiphonCdnSni = queryParam["cdn_sni"]
        config.aetherPsiphonCdnSets = queryParam["cdn_sets"]
        config.aetherPsiphonRegion = queryParam["region"]
        // Stored only when it says no; yes is the default and needs no word.
        config.aetherPsiphonBundledList = if (queryParam["psiphon_bundled"] == "0") false else null
        config.aetherTor =
            AetherTor.fromString(queryParam["tor"]).type.takeUnless { it == AetherTor.OFF.type }
        config.aetherTorBridges = queryParam["tor_bridges"]?.let { AetherTorBridges.fromString(it).type }
        config.aetherTorBridgeLines = queryParam["bridges"]?.split(';')?.joinToString("\n")
        config.aetherTorRelays = queryParam["tor_relays"]?.let { AetherTorRelays.fromString(it).type }

        if (protocol.twoHops) {
            val outer = AetherEndpoint.parse(queryParam["outer"])
            val inner = AetherEndpoint.parse(queryParam["inner"])?.takeUnless { it.host == outer?.host }
            config.aetherWiwOuter = outer?.toString()
            config.aetherWiwInner = inner?.toString()
        } else {
            val endpoint = AetherEndpoint.of(uri.idnHost, uri.port.takeIf { it > 0 }?.toString())
            config.server = endpoint?.host
            config.serverPort = endpoint?.port?.toString()
        }

        return config
    }

    fun toUri(config: ProfileItem): String {
        val protocol = AetherProtocol.fromString(config.aetherProtocol)
        val query = linkedMapOf(
            "protocol" to protocol.type,
            "scan" to AetherScanMode.fromString(config.aetherScanMode).type,
        )
        // Automatic obfuscation is the core's own choice; a link says nothing about it.
        AetherObfuscation.fromString(config.aetherObfuscation)
            .takeUnless { it == AetherObfuscation.AUTO }?.let { query["noize"] = it.type }
        query["ip"] = AetherIpVersion.fromString(config.aetherIpVersion).type
        config.aetherDns?.takeIf { it.isNotBlank() }?.let { query["dns"] = it }
        config.aetherExitLoc?.takeIf { it.isNotBlank() }?.let { query["exit_loc"] = it }
        if (protocol.overMasque) {
            query["transport"] = AetherTransport.fromString(config.aetherTransport).type
            if (config.aetherFragment == true) {
                query["fragment"] = "1"
                AetherRange.parse(config.aetherFragmentSize, AetherRange.FRAGMENT_SIZE)
                    ?.let { query["fragment_size"] = it.toString() }
                AetherRange.parse(config.aetherFragmentDelay, AetherRange.FRAGMENT_DELAY)
                    ?.let { query["fragment_delay"] = it.toString() }
            }
            if (config.aetherEch == true) query["ech"] = "1"
        }
        if (protocol.twoHops) {
            AetherEndpoint.parse(config.aetherWiwOuter)?.let { query["outer"] = it.toString() }
            AetherEndpoint.parse(config.aetherWiwInner)?.let { query["inner"] = it.toString() }
        }
        listenPortOf(config.aetherListenPort)?.let(::storedListenPort)?.let { query["listen"] = it }
        val psiphon = AetherPsiphon.fromString(config.aetherPsiphon)
        if (psiphon != AetherPsiphon.OFF) {
            query["psiphon"] = psiphon.type
            query["psiphon_mode"] = AetherPsiphonMode.fromString(config.aetherPsiphonMode).type
            config.aetherPsiphonCdnIps?.takeIf { it.isNotBlank() }?.let { query["cdn_ips"] = it }
            config.aetherPsiphonCdnSni?.takeIf { it.isNotBlank() }?.let { query["cdn_sni"] = it }
            config.aetherPsiphonCdnSets?.takeIf { it.isNotBlank() }?.let { query["cdn_sets"] = it }
            config.aetherPsiphonRegion?.takeIf { it.isNotBlank() }?.let { query["region"] = it }
            if (config.aetherPsiphonBundledList == false) query["psiphon_bundled"] = "0"
        }
        val tor = AetherTor.fromString(config.aetherTor)
        if (tor != AetherTor.OFF) {
            query["tor"] = tor.type
            query["tor_bridges"] = AetherTorBridges.fromString(config.aetherTorBridges).type
            query["tor_relays"] = AetherTorRelays.fromString(config.aetherTorRelays).type
            // A bridge line never holds a semicolon: the core separates them with one.
            bridgeLines(config.aetherTorBridgeLines).takeIf { it.isNotEmpty() }
                ?.let { query["bridges"] = it.joinToString(";") }
        }

        val endpoint = AetherEndpoint.of(config.server, config.serverPort).takeUnless { protocol.twoHops }
        val queryText = query.entries.joinToString("&") { "${it.key}=${Utils.encodeURIComponent(it.value)}" }
        return "${SCHEME}${endpoint ?: ""}?$queryText#${Utils.encodeURIComponent(config.remarks)}"
    }


    /** Why a profile cannot be used as it stands. */
    enum class Problem {
        INVALID_PEER,
        INVALID_HOP,
        SHARED_HOP,
        INVALID_DNS,
        INVALID_EXIT_LOC,
        INVALID_LISTEN_PORT,
        PSIPHON_NEEDS_MASQUE,
        TOR_NEEDS_MASQUE,
        TOR_PSIPHON_CONFLICT,
        TOR_BRIDGES_MISSING,
    }

    /**
     * Brings a profile into the shape the core accepts, or says why it cannot be.
     *
     * The two pairings the core refuses are the point of this: Psiphon and Tor carry
     * TCP alone while WARP's WireGuard endpoints answer on UDP, so either one in
     * *reverse* needs MASQUE; and the two of them go together only nested, one inside
     * the tunnel and the other around it.
     */
    fun normalize(config: ProfileItem): Problem? =
        normalizeListenPort(config)
            ?: normalizeDns(config)
            ?: normalizeExitLoc(config)
            ?: normalizePsiphon(config)
            ?: normalizeTor(config)

    /** The listen port, when the profile names one, has to name a port. */
    private fun normalizeListenPort(config: ProfileItem): Problem? {
        val text = config.aetherListenPort?.trim().orEmpty()
        if (text.isEmpty()) {
            config.aetherListenPort = null
            return null
        }
        if (listenPortOf(text) == null) return Problem.INVALID_LISTEN_PORT
        config.aetherListenPort = text
        return null
    }

    /** The resolvers, each an address with or without a port; written back comma-separated. */
    private fun normalizeDns(config: ProfileItem): Problem? {
        val resolvers = config.aetherDns.orEmpty().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
        if (resolvers.any { AetherEndpoint.parse(it) == null && AetherEndpoint.of(it, "53") == null }) {
            return Problem.INVALID_DNS
        }
        config.aetherDns = resolvers.joinToString(",").ifEmpty { null }
        return null
    }

    private val exitRule = Regex("!?[A-Z]{2}(,[A-Z]{2})*")

    /** The exit rule as the core reads it: country codes to allow, or a leading ! to refuse. */
    private fun normalizeExitLoc(config: ProfileItem): Problem? {
        val rule = config.aetherExitLoc.orEmpty().filterNot { it.isWhitespace() }.uppercase()
        if (rule.isEmpty()) {
            config.aetherExitLoc = null
            return null
        }
        if (!exitRule.matches(rule)) return Problem.INVALID_EXIT_LOC
        config.aetherExitLoc = rule
        return null
    }

    private fun normalizePsiphon(config: ProfileItem): Problem? {
        val psiphon = AetherPsiphon.fromString(config.aetherPsiphon)
        if (psiphon == AetherPsiphon.OFF) {
            config.aetherPsiphon = null
            config.aetherPsiphonMode = null
            config.aetherPsiphonCdnIps = null
            config.aetherPsiphonCdnSni = null
            config.aetherPsiphonCdnSets = null
            config.aetherPsiphonRegion = null
            config.aetherPsiphonBundledList = null
            return null
        }
        // Psiphon carries TCP alone and WARP's WireGuard endpoints answer on UDP.
        if (psiphon == AetherPsiphon.REVERSE && !AetherProtocol.fromString(config.aetherProtocol).overMasque) {
            return Problem.PSIPHON_NEEDS_MASQUE
        }
        config.aetherPsiphon = psiphon.type
        config.aetherPsiphonMode = AetherPsiphonMode.fromString(config.aetherPsiphonMode).type
        config.aetherPsiphonCdnIps = commaList(config.aetherPsiphonCdnIps)
        config.aetherPsiphonCdnSni = commaList(config.aetherPsiphonCdnSni)
        config.aetherPsiphonCdnSets =
            AetherPsiphonCdnSet.join(AetherPsiphonCdnSet.parse(config.aetherPsiphonCdnSets))
        config.aetherPsiphonRegion =
            config.aetherPsiphonRegion?.trim()?.uppercase()?.ifEmpty { null }
        // Stored only when it says no; yes is the default and needs no word.
        config.aetherPsiphonBundledList = config.aetherPsiphonBundledList?.takeUnless { it }
        return null
    }

    private fun normalizeTor(config: ProfileItem): Problem? {
        val tor = AetherTor.fromString(config.aetherTor)
        if (tor == AetherTor.OFF) {
            config.aetherTor = null
            config.aetherTorBridges = null
            config.aetherTorBridgeLines = null
            config.aetherTorRelays = null
            return null
        }
        if (tor == AetherTor.REVERSE && !AetherProtocol.fromString(config.aetherProtocol).overMasque) {
            return Problem.TOR_NEEDS_MASQUE
        }
        // Tor and Psiphon go together only nested, one inside the tunnel and the other
        // around it: two around it the core refuses, two inside it or one alone leaves
        // the app nothing to dial the other on.
        val psiphon = AetherPsiphon.fromString(config.aetherPsiphon)
        val nested = psiphon == AetherPsiphon.OFF ||
            (tor == AetherTor.CHAIN && psiphon == AetherPsiphon.REVERSE) ||
            (tor == AetherTor.REVERSE && psiphon == AetherPsiphon.CHAIN)
        if (!nested) return Problem.TOR_PSIPHON_CONFLICT
        val bridges = AetherTorBridges.fromString(config.aetherTorBridges)
        val lines = bridgeLines(config.aetherTorBridgeLines)
        if (bridges == AetherTorBridges.OWN && lines.isEmpty()) return Problem.TOR_BRIDGES_MISSING
        config.aetherTor = tor.type
        config.aetherTorBridges = bridges.type
        config.aetherTorBridgeLines =
            lines.takeIf { bridges == AetherTorBridges.OWN }?.joinToString("\n")
        config.aetherTorRelays = AetherTorRelays.fromString(config.aetherTorRelays).type
        return null
    }

    /**
     * The bridge lines in [text], one per line the way torrc writes them, read as the
     * core reads a bridge file: blank lines and comments dropped, a leading Bridge
     * keyword taken off.
     */
    fun bridgeLines(text: String?): List<String> =
        text.orEmpty().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removePrefix("Bridge ").removePrefix("bridge ").trim() }
            .filter { it.isNotEmpty() }

    /** A list as the core reads it, comma or space separated, written back with commas alone. */
    private fun commaList(text: String?): String? =
        text?.split(Regex("[,\\s]+"))?.filter { it.isNotEmpty() }?.joinToString(",")?.ifEmpty { null }

    /** The loopback port [text] names for the core to listen on, null when it names none. */
    fun listenPortOf(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    /**
     * The listen port as a profile stores it: nothing for the default, so a profile saved
     * before the port could be chosen and one saved with the default stay the same profile.
     */
    fun storedListenPort(port: Int): String? =
        port.toString().takeUnless { it == AppConfig.PORT_AETHER_SOCKS }
}
