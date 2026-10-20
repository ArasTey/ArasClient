package com.aras.client.fmt

import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.idnHost
import com.aras.client.extension.nullIfBlank
import com.aras.client.extension.removeWhiteSpace
import com.aras.client.util.MimicPackets
import com.aras.client.util.Utils
import java.net.URI
import java.util.Locale

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
        // A link without a port cannot be dialled; -1 from the URI parser would
        // otherwise become an endpoint of "host:-1".
        if (uri.port <= 0) return null
        config.serverPort = uri.port.toString()

        config.secretKey = uri.userInfo.orEmpty()
        config.localAddress = queryParam["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.publicKey = queryParam["publickey"].orEmpty()
        config.preSharedKey = queryParam["presharedkey"]?.nullIfBlank()
        config.mtu = Utils.parseInt(queryParam["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.reserved = queryParam["reserved"] ?: "0,0,0"
        // The keep-alive is the app's own addition to the link: without it the
        // peer's mapping expires after a few minutes and the tunnel goes quiet.
        config.keepAlive = queryParam["keepalive"]?.nullIfBlank()

        config.junkPacketCount = queryParam["junkPacketCount"]?.nullIfBlank()
        config.junkPacketMinSize = queryParam["junkPacketMinSize"]?.nullIfBlank()
        config.junkPacketMaxSize = queryParam["junkPacketMaxSize"]?.nullIfBlank()
        config.initPacketJunkSize = queryParam["initPacketJunkSize"]?.nullIfBlank()
        config.responsePacketJunkSize = queryParam["responsePacketJunkSize"]?.nullIfBlank()
        config.initPacketJunkHeader = queryParam["initPacketJunkHeader"]?.nullIfBlank()
        config.responsePacketJunkHeader = queryParam["responsePacketJunkHeader"]?.nullIfBlank()
        config.transportPacketJunkHeader = queryParam["transportPacketJunkHeader"]?.nullIfBlank()
        // H3 was previously only read from a .conf peer block (as "h3") and never
        // carried in a share link, so it was silently dropped on export/import.
        config.cookiePacketJunkHeader = queryParam["cookiePacketJunkHeader"]?.nullIfBlank()
        // Mimic packets travel as i1..i5 — the same names the AmneziaWG UAPI uses,
        // so a link can be read against either without translation.
        config.mimicPackets = (1..MimicPackets.MAX_PACKETS)
            .mapNotNull { queryParam["i$it"]?.nullIfBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")

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
        config.initPacketJunkHeader = "1"
        config.responsePacketJunkHeader = "2"
        config.cookiePacketJunkHeader = "3"
        config.transportPacketJunkHeader = "4"
    }

    /**
     * Every key that only AmneziaWG writes, in either case.
     *
     * S3/S4 and the I1-I5 mimic packets count: a config carrying only those is
     * still AmneziaWG, and treating it as plain WireGuard throws the whole
     * obfuscation setup away.
     */
    private val AMNEZIA_ONLY_KEYS = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5",
        "junkpacketcount", "junkpacketminsize", "junkpacketmaxsize",
        "initpacketjunksize", "responsepacketjunksize",
    )

    /**
     * Whether a `.conf` is AmneziaWG rather than plain WireGuard.
     *
     * Deciding on the key set is case-insensitive and complete. The previous
     * test was `server.contains("Jc", ignoreCase = false)`, so a file writing
     * `jc` or `JC` was imported as stock WireGuard: the profile kept its keys
     * but none of the obfuscation parameters were applied, so it dialled as
     * plain WireGuard, the server dropped every packet, and the editor offered
     * no AmneziaWG fields to show that anything was missing.
     */
    fun looksLikeAmneziaConf(text: String): Boolean = text.lineSequence().any { line ->
        val key = line.substringBefore('=', missingDelimiterValue = "")
            .trim()
            .lowercase(Locale.US)
        key.isNotEmpty() && key in AMNEZIA_ONLY_KEYS
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

            if (trimmedLine.isEmpty()) {
                return@forEach
            }

            // Real-world .conf files often arrive wrapped in channel
            // signatures / ads (e.g. "Telegram/@...") before [Interface];
            // ignore everything outside the sections.
            when {
                trimmedLine.startsWith("[Interface]", ignoreCase = true) -> currentSection = "Interface"
                trimmedLine.startsWith("[Peer]", ignoreCase = true) -> currentSection = "Peer"
                currentSection == null -> return@forEach
                trimmedLine.startsWith("#") || trimmedLine.startsWith("//") -> return@forEach
                else -> {
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

        config.secretKey = interfaceParams["privatekey"].orEmpty()
        config.remarks = interfaceParams["description"]?.takeIf { it.isNotBlank() }
            ?: System.currentTimeMillis().toString()
        config.localAddress = interfaceParams["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.mtu = Utils.parseInt(interfaceParams["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.publicKey = peerParams["publickey"].orEmpty()
        config.preSharedKey = peerParams["presharedkey"]?.nullIfBlank()
        val endpoint = peerParams["endpoint"].orEmpty()
        val (server, port) = parseEndpoint(endpoint)
        config.server = server
        config.serverPort = port
        config.reserved = peerParams["reserved"] ?: "0,0,0"
        config.keepAlive = peerParams["persistentkeepalive"]?.nullIfBlank()

        // The obfuscation parameters are DEVICE-level, so AmneziaVPN writes them under
// [Interface] — they have to, because the UAPI parser is in peer context after
        // the first "public_key=" line. Reading only [Peer] found nothing in a
        // normal export, applyDefaultJunkParams then filled in AmneziaWG's stock
        // values, and the server silently dropped every packet: the tunnel came
        // up carrying nothing. Read both, [Interface] first, so a file that puts
        // them in either place works.
        val obfuscation = { key: String ->
            interfaceParams[key]?.nullIfBlank() ?: peerParams[key]?.nullIfBlank()
        }
        config.junkPacketCount = obfuscation("jc")
        config.junkPacketMinSize = obfuscation("jmin")
        config.junkPacketMaxSize = obfuscation("jmax")
        config.initPacketJunkSize = obfuscation("s1")
        config.responsePacketJunkSize = obfuscation("s2")
        config.initPacketJunkHeader = obfuscation("h1")
        config.responsePacketJunkHeader = obfuscation("h2")
        config.cookiePacketJunkHeader = obfuscation("h3")
        config.transportPacketJunkHeader = obfuscation("h4")
        config.mimicPackets = (1..MimicPackets.MAX_PACKETS)
            .mapNotNull { obfuscation("i$it") }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")

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
        config.keepAlive?.nullIfBlank()?.let { dicQuery["keepalive"] = it }
        config.junkPacketCount?.nullIfBlank()?.let { dicQuery["junkPacketCount"] = it }
        config.junkPacketMinSize?.nullIfBlank()?.let { dicQuery["junkPacketMinSize"] = it }
        config.junkPacketMaxSize?.nullIfBlank()?.let { dicQuery["junkPacketMaxSize"] = it }
        config.initPacketJunkSize?.nullIfBlank()?.let { dicQuery["initPacketJunkSize"] = it }
        config.responsePacketJunkSize?.nullIfBlank()?.let { dicQuery["responsePacketJunkSize"] = it }
        config.initPacketJunkHeader?.nullIfBlank()?.let { dicQuery["initPacketJunkHeader"] = it }
        config.responsePacketJunkHeader?.nullIfBlank()?.let { dicQuery["responsePacketJunkHeader"] = it }
        config.transportPacketJunkHeader?.nullIfBlank()?.let { dicQuery["transportPacketJunkHeader"] = it }
        config.cookiePacketJunkHeader?.nullIfBlank()?.let { dicQuery["cookiePacketJunkHeader"] = it }
        MimicPackets.specs(config.mimicPackets).forEachIndexed { index, spec ->
            dicQuery["i${index + 1}"] = spec
        }

        return toUri(config, config.secretKey, dicQuery)
    }
}
