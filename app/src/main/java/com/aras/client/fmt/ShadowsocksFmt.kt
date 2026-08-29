package com.aras.client.fmt

import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.enums.NetworkType
import com.aras.client.extension.idnHost
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils
import java.net.URI

object ShadowsocksFmt : FmtBase() {
    /**
     * Parses a Shadowsocks URI string into a ProfileItem object.
     *
     * @param str the Shadowsocks URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        return parseSip002(str) ?: parseLegacy(str)
    }

    /**
     * Parses a SIP002 Shadowsocks URI string into a ProfileItem object.
     *
     * @param str the SIP002 Shadowsocks URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parseSip002(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.SHADOWSOCKS)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.idnHost.isEmpty()) return null
        if (uri.port <= 0) return null
        if (uri.userInfo.isNullOrEmpty()) return null

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()

        val result = if (uri.userInfo.contains(":")) {
            uri.userInfo.split(":", limit = 2)
        } else {
            Utils.decode(uri.userInfo).split(":", limit = 2)
        }
        if (result.count() == 2) {
            config.method = result.first()
            config.password = result.last()
        }

        if (!uri.rawQuery.isNullOrEmpty()) {
            val queryParam = getQueryParam(uri)
            val plugin = queryParam["plugin"]
            if (plugin != null) {
                val queryPairs = HashMap<String, String>()
                val flags = HashSet<String>()
                for (pair in plugin.split(";")) {
                    val idx = pair.split("=", limit = 2)
                    if (idx.count() == 2) {
                        queryPairs.put(idx.first(), idx.last())
                    } else if (pair.isNotEmpty()) {
                        flags.add(pair)
                    }
                }
                val mode = queryPairs["mode"]
                if (flags.contains("v2ray-plugin") && (mode == null || mode == "websocket")) {
                    // v2ray-plugin websocket mode (the default) maps to native SS + WS transport
                    config.network = NetworkType.WS.type
                    config.path = queryPairs["path"] ?: "/"
                    config.host = queryPairs["host"]
                    if (flags.contains("tls")) {
                        config.security = AppConfig.TLS
                        if (queryPairs["host"] != null && config.sni.isNullOrEmpty()) {
                            config.sni = queryPairs["host"]
                        }
                    }
                } else if (plugin.contains("obfs=http")) {
                    config.network = NetworkType.TCP.type
                    config.headerType = "http"
                    config.host = queryPairs["obfs-host"]
                    config.path = queryPairs["path"]
                }
            }
        }

        return config
    }

    /**
     * Parses a legacy Shadowsocks URI string into a ProfileItem object.
     *
     * @param str the legacy Shadowsocks URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parseLegacy(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.SHADOWSOCKS)
        var result = str.replace(EConfigType.SHADOWSOCKS.protocolScheme, "")
        val indexSplit = result.indexOf("#")
        if (indexSplit > 0) {
            try {
                config.remarks =
                    Utils.decodeURIComponent(result.substring(indexSplit + 1, result.length))
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to decode remarks in SS legacy URL", e)
            }

            result = result.substring(0, indexSplit)
        }

        //part decode
        val indexS = result.indexOf("@")
        result = if (indexS > 0) {
            Utils.decode(result.substring(0, indexS)) + result.substring(
                indexS,
                result.length
            )
        } else {
            Utils.decode(result)
        }

        val legacyPattern = "^(.+?):(.*)@(.+?):(\\d+?)/?$".toRegex()
        val match = legacyPattern.matchEntire(result) ?: return null

        config.server = match.groupValues[3].removeSurrounding("[", "]")
        config.serverPort = match.groupValues[4]
        config.password = match.groupValues[2]
        config.method = match.groupValues[1].lowercase()

        return config
    }

    /**
     * Converts a ProfileItem object to a URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val pw = "${config.method}:${config.password}"

        return toUri(config, Utils.encode(pw, true), null)
    }
}