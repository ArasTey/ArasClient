package com.aras.client.fmt

import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.idnHost
import com.aras.client.extension.nullIfBlank
import com.aras.client.extension.removeWhiteSpace
import com.aras.client.util.Utils
import java.net.URI

object AmneziawgFmt : FmtBase() {

    /**
     * Parses an awg:// URI string into a ProfileItem object.
     * Scheme follows AmneziaWG conventions:
     * awg://privateKey@host:port/?publicKey=&presharedkey=&address=&mtu=
     *     &reserved=&junkPacketCount=&junkPacketMinSize=&junkPacketMaxSize=
     *     &initPacketJunkSize=&responsePacketJunkSize=...
     *
     * @param str the URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.AMNEZIAWG)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.rawQuery.isNullOrEmpty()) return null
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()

        config.secretKey = uri.userInfo.orEmpty()
        config.localAddress = queryParam["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.publicKey = queryParam["publickey"].orEmpty()
        config.preSharedKey = queryParam["presharedkey"]?.nullIfBlank()
        config.mtu = Utils.parseInt(queryParam["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.reserved = queryParam["reserved"] ?: "0,0,0"

        config.junkPacketCount = queryParam["junkPacketCount"]?.nullIfBlank()
        config.junkPacketMinSize = queryParam["junkPacketMinSize"]?.nullIfBlank()
        config.junkPacketMaxSize = queryParam["junkPacketMaxSize"]?.nullIfBlank()
        config.initPacketJunkSize = queryParam["initPacketJunkSize"]?.nullIfBlank()
        config.responsePacketJunkSize = queryParam["responsePacketJunkSize"]?.nullIfBlank()
        config.initPacketJunkHeader = queryParam["initPacketJunkHeader"]?.nullIfBlank()
        config.responsePacketJunkHeader = queryParam["responsePacketJunkHeader"]?.nullIfBlank()
        config.transportPacketJunkHeader = queryParam["transportPacketJunkHeader"]?.nullIfBlank()

        applyDefaultJunkParams(config)
        return config
    }

    /**
     * AmneziaWG's standard obfuscation defaults (the same values the
     * AmneziaVPN client applies to awg:// links that carry no junk
     * parameters — e.g. Cloudflare WARP endpoints). Without these the
     * handshake goes out as plain WireGuard and gets dropped.
     */
    fun applyDefaultJunkParams(config: ProfileItem) {
        if (!config.junkPacketCount.isNullOrBlank() ||
            !config.initPacketJunkHeader.isNullOrBlank()
        ) {
            return
        }
        config.junkPacketCount = "4"
        config.junkPacketMinSize = "40"
        config.junkPacketMaxSize = "70"
        config.initPacketJunkSize = "15"
        config.responsePacketJunkSize = "20"
        config.initPacketJunkHeader = "1234567"
        config.responsePacketJunkHeader = "2345678"
        config.transportPacketJunkHeader = "3456789"
    }

    /**
     * Parses an AmneziaWG configuration file string (as produced by the
     * AmneziaVPN client) into a ProfileItem object. Understands both the
     * [Interface]/[Peer] sections and the AWG-specific junk parameters
     * (Jc, Jmin, Jmax, S1, S2, H1-H4).
     *
     * @param str the configuration file string to parse
     * @return the parsed ProfileItem object
     */
    fun parseAmneziaConfFile(str: String): ProfileItem {
        val config = ProfileItem.create(EConfigType.AMNEZIAWG)

        val interfaceParams: MutableMap<String, String> = mutableMapOf()
        val peerParams: MutableMap<String, String> = mutableMapOf()

        var currentSection: String? = null

        str.lines().forEach { line ->
            val trimmedLine = line.trim()

            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                return@forEach
            }

            when {
                trimmedLine.startsWith("[Interface]", ignoreCase = true) -> currentSection = "Interface"
                trimmedLine.startsWith("[Peer]", ignoreCase = true) -> currentSection = "Peer"
                else -> {
                    if (currentSection != null) {
                        val parts = trimmedLine.split("=", limit = 2).map { it.trim() }
                        if (parts.size == 2) {
                            val key = parts[0].lowercase()
                            val value = parts[1]
                            when (currentSection) {
                                "Interface" -> interfaceParams[key] = value
                                "Peer" -> peerParams[key] = value
                            }
                        }
                    }
                }
            }
        }

        config.secretKey = interfaceParams["privatekey"].orEmpty()
        config.remarks = interfaceParams["description"]?.takeIf { it.isNotBlank() }
            ?: System.currentTimeMillis().toString()
        config.localAddress = interfaceParams["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.mtu = Utils.parseInt(interfaceParams["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.publicKey = peerParams["publickey"].orEmpty()
        config.preSharedKey = peerParams["presharedkey"]?.nullIfBlank()
        val endpoint = peerParams["endpoint"].orEmpty()
        val endpointParts = endpoint.split(":", limit = 2)
        if (endpointParts.size == 2) {
            config.server = endpointParts[0].removePrefix("[").removeSuffix("]")
            config.serverPort = endpointParts[1]
        } else {
            config.server = endpoint
            config.serverPort = ""
        }
        config.reserved = peerParams["reserved"] ?: "0,0,0"

        config.junkPacketCount = peerParams["jc"]?.nullIfBlank()
        config.junkPacketMinSize = peerParams["jmin"]?.nullIfBlank()
        config.junkPacketMaxSize = peerParams["jmax"]?.nullIfBlank()
        config.initPacketJunkSize = peerParams["s1"]?.nullIfBlank()
        config.responsePacketJunkSize = peerParams["s2"]?.nullIfBlank()
        config.initPacketJunkHeader = peerParams["h1"]?.nullIfBlank()
        config.responsePacketJunkHeader = peerParams["h2"]?.nullIfBlank()
        config.transportPacketJunkHeader = peerParams["h3"]?.nullIfBlank()

        applyDefaultJunkParams(config)
        return config
    }

    /**
     * Converts a ProfileItem object to an awg:// URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()

        dicQuery["publickey"] = config.publicKey.orEmpty()
        if (config.reserved != null) {
            dicQuery["reserved"] = config.reserved.removeWhiteSpace().orEmpty()
        }
        dicQuery["address"] = config.localAddress.removeWhiteSpace().orEmpty()
        if (config.mtu != null) {
            dicQuery["mtu"] = config.mtu.toString()
        }
        if (config.preSharedKey != null) {
            dicQuery["presharedkey"] = config.preSharedKey.removeWhiteSpace().orEmpty()
        }
        config.junkPacketCount?.nullIfBlank()?.let { dicQuery["junkPacketCount"] = it }
        config.junkPacketMinSize?.nullIfBlank()?.let { dicQuery["junkPacketMinSize"] = it }
        config.junkPacketMaxSize?.nullIfBlank()?.let { dicQuery["junkPacketMaxSize"] = it }
        config.initPacketJunkSize?.nullIfBlank()?.let { dicQuery["initPacketJunkSize"] = it }
        config.responsePacketJunkSize?.nullIfBlank()?.let { dicQuery["responsePacketJunkSize"] = it }
        config.initPacketJunkHeader?.nullIfBlank()?.let { dicQuery["initPacketJunkHeader"] = it }
        config.responsePacketJunkHeader?.nullIfBlank()?.let { dicQuery["responsePacketJunkHeader"] = it }
        config.transportPacketJunkHeader?.nullIfBlank()?.let { dicQuery["transportPacketJunkHeader"] = it }

        return toUri(config, config.secretKey, dicQuery)
    }
}
