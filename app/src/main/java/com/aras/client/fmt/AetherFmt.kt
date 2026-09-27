package com.aras.client.fmt

import com.aras.client.AppConfig
import com.aras.client.dto.AetherEndpoint
import com.aras.client.dto.AetherRange
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherIpVersion
import com.aras.client.enums.AetherObfuscation
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

        val endpoint = AetherEndpoint.of(config.server, config.serverPort).takeUnless { protocol.twoHops }
        val queryText = query.entries.joinToString("&") { "${it.key}=${Utils.encodeURIComponent(it.value)}" }
        return "${SCHEME}${endpoint ?: ""}?$queryText#${Utils.encodeURIComponent(config.remarks)}"
    }

    /** The loopback port [text] names for the core to listen on, null when it names none. */
    fun listenPortOf(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    /**
     * The listen port as a profile stores it: nothing for the default, so a profile saved
     * before the port could be chosen and one saved with the default stay the same profile.
     */
    fun storedListenPort(port: Int): String? =
        port.toString().takeUnless { it == AppConfig.PORT_AETHER_SOCKS }
}
