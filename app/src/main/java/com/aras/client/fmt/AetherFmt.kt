package com.aras.client.fmt

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.idnHost
import com.aras.client.extension.nullIfBlank
import com.aras.client.util.Utils
import java.net.URI

/**
 * Share-link format for an Aether / gateway profile.
 *
 * `aether://host:port?protocol=wireguard&psk=..&address=..&reserved=..&mtu=1280&ip=ipv4&obfuscation=auto#remarks`
 *
 * The credential lives in `address` for MASQUE and the WireGuard key fields for
 * WireGuard, mirroring how the generated outbound is built.
 */
object AetherFmt : FmtBase() {

    private const val SCHEME = "aether://"

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.AETHER)

        val uri = URI(Utils.fixIllegalUrl(str))
        val queryParam = if (uri.rawQuery.isNullOrEmpty()) emptyMap() else getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = uri.port.takeIf { it > 0 }?.toString()

        config.aetherProtocol = queryParam["protocol"]
        config.aetherIpVersion = queryParam["ip"]
        config.aetherObfuscation = queryParam["obfuscation"]

        config.publicKey = queryParam["pbk"]?.nullIfBlank()
        config.secretKey = queryParam["sk"]?.nullIfBlank()
        config.preSharedKey = queryParam["psk"]?.nullIfBlank()
        config.localAddress = queryParam["address"]?.nullIfBlank()
        config.reserved = queryParam["reserved"]?.nullIfBlank()
        config.mtu = queryParam["mtu"]?.toIntOrNull()
        config.sni = queryParam["sni"]?.nullIfBlank()
        config.path = queryParam["path"]?.nullIfBlank()

        return config
    }

    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()
        config.aetherProtocol?.nullIfBlank()?.let { dicQuery["protocol"] = it }
        config.aetherIpVersion?.nullIfBlank()?.let { dicQuery["ip"] = it }
        config.aetherObfuscation?.nullIfBlank()?.let { dicQuery["obfuscation"] = it }

        config.publicKey?.nullIfBlank()?.let { dicQuery["pbk"] = it }
        config.secretKey?.nullIfBlank()?.let { dicQuery["sk"] = it }
        config.preSharedKey?.nullIfBlank()?.let { dicQuery["psk"] = it }
        config.localAddress?.nullIfBlank()?.let { dicQuery["address"] = it }
        config.reserved?.nullIfBlank()?.let { dicQuery["reserved"] = it }
        config.mtu?.let { dicQuery["mtu"] = it.toString() }
        config.sni?.nullIfBlank()?.let { dicQuery["sni"] = it }
        config.path?.nullIfBlank()?.let { dicQuery["path"] = it }

        return SCHEME + toUri(config, null, dicQuery).removePrefix("@")
    }
}
