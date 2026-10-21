package com.aras.client.fmt

import com.aras.client.AppConfig
import com.aras.client.core.dnstunnel.DnsTunnelEngine
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.idnHost
import com.aras.client.extension.nullIfBlank
import com.aras.client.util.Utils
import java.net.URI

/**
 * Share-link format for a DNS-tunnel profile.
 *
 * `dnstunnel://tunnel-zone?engine=storm&key=…&method=3&resolvers=…&port=10821#remarks`
 *
 * The zone is the host; everything else rides in the query. The link carries the
 * engine's own parameters, so a profile can be written by hand or shared without
 * the app having to know anything else about it.
 *
 * The scheme is not added here — AngConfigManager.shareConfig prepends the
 * config type's own.
 */
object DnsTunnelFmt : FmtBase() {

    /** The port the engine serves its SOCKS5 on, when the profile names one. */
    fun listenPortOf(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    /** Whether [text] is a port the engine can be told to bind. */
    private fun isValidPort(text: String?): Boolean =
        text?.trim()?.toIntOrNull()?.let { it in 1..65535 } == true

    /**
     * The port as a profile stores it: nothing for the default, so a profile saved
     * before the port could be chosen and one saved with the default stay the same
     * profile.
     */
    fun storedListenPort(port: Int): String? =
        port.toString().takeUnless { it == AppConfig.PORT_DNSTUNNEL_SOCKS }

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.DNSTUNNEL)

        val uri = URI(Utils.fixIllegalUrl(str))
        val queryParam = queryParams(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty())
            .let { it.ifEmpty { "DNS tunnel" } }
        config.dnsTunnelDomain = uri.idnHost

        config.dnsTunnelEngine = DnsTunnelEngine.of(queryParam["engine"]).id
        config.dnsTunnelKey = queryParam["key"]
        config.dnsTunnelMethod = queryParam["method"]?.toIntOrNull()?.takeIf { it in 0..5 }
        config.dnsTunnelResolvers = queryParam["resolvers"]
        config.dnsTunnelPort = queryParam["port"]?.takeIf { isValidPort(it) }
        config.dnsTunnelDnsPort = queryParam["dns"]?.takeIf { isValidPort(it) }
        config.dnsTunnelTarget = queryParam["target"]
        config.dnsTunnelCountry = queryParam["country"]
        config.dnsTunnelPreset = queryParam["preset"]

        return config
    }

    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()
        dicQuery["engine"] = DnsTunnelEngine.of(config.dnsTunnelEngine).id
        config.dnsTunnelKey?.nullIfBlank()?.let { dicQuery["key"] = it }
        config.dnsTunnelMethod?.let { dicQuery["method"] = it.toString() }
        config.dnsTunnelResolvers?.nullIfBlank()?.let { dicQuery["resolvers"] = it }
        config.dnsTunnelPort?.nullIfBlank()?.let { dicQuery["port"] = it }
        config.dnsTunnelDnsPort?.nullIfBlank()?.let { dicQuery["dns"] = it }
        config.dnsTunnelTarget?.nullIfBlank()?.let { dicQuery["target"] = it }
        config.dnsTunnelCountry?.nullIfBlank()?.let { dicQuery["country"] = it }
        config.dnsTunnelPreset?.nullIfBlank()?.let { dicQuery["preset"] = it }

        val host = config.dnsTunnelDomain?.trim().orEmpty()
        val query = dicQuery.entries.joinToString("&") {
            "${it.key}=${Utils.encodeURIComponent(it.value)}"
        }
        val remarks = config.remarks.takeIf { it.isNotBlank() } ?: "DNS tunnel"
        val queryPart = if (query.isEmpty()) "" else "?$query"
        return "$host$queryPart#" + Utils.encodeURIComponent(remarks)
    }

    /**
     * The query parameters of [uri], each value taken whole.
     *
     * A value may hold an "=" of its own — an encryption key does — so the
     * separator is the first one, not the last.
     */
    private fun queryParams(uri: URI): Map<String, String> =
        uri.rawQuery.orEmpty()
            .split("&")
            .mapNotNull { part ->
                if (part.isEmpty()) return@mapNotNull null
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                part.substring(0, separator) to Utils.decodeURIComponent(part.substring(separator + 1))
            }
            .toMap()
}
