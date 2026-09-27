package com.aras.client.fmt

import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.idnHost
import com.aras.client.extension.nullIfBlank
import com.aras.client.util.Utils
import java.net.URI

/**
 * Share-link format for MASQUE (IETF CONNECT-IP, RFC 9484).
 *
 * `masque://host:port?sni=..&path=..&host=..&remoteDNS=..#remarks`
 *
 * MASQUE carries a CONNECT-IP capsule inside an HTTP/3 request and has no
 * user/password authentication in the core config, so there is no userinfo part.
 * The transport is always TLS.
 */
object MasqueFmt : FmtBase() {

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.MASQUE)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.rawQuery.isNullOrEmpty()) return null
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = (uri.port.takeIf { it > 0 } ?: 443).toString()

        config.security = AppConfig.TLS
        config.sni = queryParam["sni"]?.nullIfBlank()
        config.insecure = queryParam["insecure"] == "1"
        config.alpn = queryParam["alpn"]?.nullIfBlank()
        config.fingerPrint = queryParam["fp"]?.nullIfBlank()
        // masqueSettings host/path — `host` doubles as the CONNECT authority.
        config.host = queryParam["host"]?.nullIfBlank()
        config.path = queryParam["path"]?.nullIfBlank()
        config.remoteDNS = queryParam["remoteDNS"]?.nullIfBlank()
        config.pinnedCA256 = queryParam["pcs"]?.nullIfBlank()
        config.verifyPeerCertByName = queryParam["vcn"]?.nullIfBlank()

        return config
    }

    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()
        config.sni?.nullIfBlank()?.let { dicQuery["sni"] = it }
        if (config.insecure == true) {
            dicQuery["insecure"] = "1"
        }
        config.alpn?.nullIfBlank()?.let { dicQuery["alpn"] = it }
        config.fingerPrint?.nullIfBlank()?.let { dicQuery["fp"] = it }
        config.host?.nullIfBlank()?.let { dicQuery["host"] = it }
        config.path?.nullIfBlank()?.let { dicQuery["path"] = it }
        config.remoteDNS?.nullIfBlank()?.let { dicQuery["remoteDNS"] = it }
        config.pinnedCA256?.nullIfBlank()?.let { dicQuery["pcs"] = it }
        config.verifyPeerCertByName?.nullIfBlank()?.let { dicQuery["vcn"] = it }

        return toUri(config, null, dicQuery)
    }
}
