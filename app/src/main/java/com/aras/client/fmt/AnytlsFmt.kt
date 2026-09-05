package com.aras.client.fmt

import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.idnHost
import com.aras.client.extension.nullIfBlank
import com.aras.client.util.Utils
import java.net.URI

object AnytlsFmt : FmtBase() {

    /**
     * Parses an anytls:// URI string into a ProfileItem object.
     * Scheme: anytls://password@host:port/?sni=&insecure=&alpn=&fp=#name
     *
     * @param str the URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.ANYTLS)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.rawQuery.isNullOrEmpty()) return null
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = (uri.port.takeIf { it > 0 } ?: 443).toString()
        config.password = Utils.decodeURIComponent(uri.userInfo.orEmpty())

        // AnyTLS always rides on TLS via the anytls transport.
        config.security = AppConfig.TLS
        config.sni = queryParam["sni"]?.nullIfBlank()
        config.insecure = queryParam["insecure"] == "1"
        config.alpn = queryParam["alpn"]?.nullIfBlank()
        config.fingerPrint = queryParam["fp"]?.nullIfBlank()

        return config
    }

    /**
     * Converts a ProfileItem object to an anytls:// URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()
        config.sni?.nullIfBlank()?.let { dicQuery["sni"] = it }
        if (config.insecure == true) {
            dicQuery["insecure"] = "1"
        }
        config.alpn?.nullIfBlank()?.let { dicQuery["alpn"] = it }
        config.fingerPrint?.nullIfBlank()?.let { dicQuery["fp"] = it }

        return toUri(config, config.password, dicQuery)
    }
}
