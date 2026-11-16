package com.aras.client.fmt

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.util.Utils
import java.net.URI

/**
 * Share-link format for an IKEv2/IPsec profile. The tunnel is built by the
 * platform's own IKE engine, so the link carries exactly what an
 * Ikev2VpnProfile needs and nothing else:
 *
 * - `ikev2://user:pass@vpn.example.com?identity=user@vpn.example.com&remote_id=vpn.example.com#Name`
 *
 * The userinfo part is the username/password auth pair and may be omitted for
 * certificate-less setups that authenticate by the identity alone. The port is
 * not shared: IKE always dials UDP 500/4500 and the platform takes no port.
 */
object Ikev2Fmt : FmtBase() {

    fun parse(str: String): ProfileItem? {
        // A hand-written link can be unparseable, and an import must read that as
        // a link this format does not take rather than stop.
        val uri = runCatching { URI(Utils.fixIllegalUrl(str)) }.getOrNull() ?: return null
        if (!uri.scheme.equals("ikev2", ignoreCase = true)) return null

        val server = uri.host ?: return null
        val config = ProfileItem.create(EConfigType.IKEV2)
        config.server = server
        // The port is cosmetic: the platform IKE dials UDP 500/4500 regardless.
        config.serverPort = "500"

        val userInfo = uri.rawUserInfo
        if (!userInfo.isNullOrEmpty()) {
            val split = userInfo.indexOf(':')
            if (split >= 0) {
                config.username = Utils.decodeURIComponent(userInfo.substring(0, split))
                config.password = Utils.decodeURIComponent(userInfo.substring(split + 1))
            } else {
                config.username = Utils.decodeURIComponent(userInfo)
            }
        }

        val query = if (uri.rawQuery.isNullOrEmpty()) emptyMap() else getQueryParam(uri)
        config.ikev2LocalId = query["identity"]?.trim()?.ifEmpty { null }
            ?: config.username?.takeIf { it.isNotBlank() && Utils.isDomainName(it) && !Utils.isPureIpAddress(it) }
        config.ikev2RemoteId = query["remote_id"]?.trim()?.ifEmpty { null }
        config.ikev2AuthMode = query["auth"]?.trim()?.lowercase()?.takeIf { it == "psk" }
        // A private CA travels as base64 DER. Whitespace or padding quirks from
        // hand-written links are tolerated by the strict-decoder's input filter.
        config.ikev2CaCert = query["ca"]?.trim()?.replace("\n", "")?.ifEmpty { null }
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty())
            .ifEmpty { defaultRemarks(config) }
        return config
    }

    fun toUri(config: ProfileItem): String {
        val query = linkedMapOf<String, String>()
        config.ikev2LocalId?.takeIf { it.isNotBlank() }?.let { query["identity"] = it }
        config.ikev2RemoteId?.takeIf { it.isNotBlank() }?.let { query["remote_id"] = it }
        if (config.ikev2AuthMode == "psk") query["auth"] = "psk"
        config.ikev2CaCert?.takeIf { it.isNotBlank() }?.let { query["ca"] = it }

        val userInfo = when {
            config.username.isNullOrBlank() && config.password.isNullOrBlank() -> null
            config.password.isNullOrBlank() -> Utils.encodeURIComponent(config.username.orEmpty())
            else -> "${Utils.encodeURIComponent(config.username.orEmpty())}:" +
                Utils.encodeURIComponent(config.password.orEmpty())
        }
        val queryText = query.entries.joinToString("&") {
            "${it.key}=${Utils.encodeURIComponent(it.value)}"
        }
        val remarks = config.remarks.ifBlank { defaultRemarks(config) }
        return buildString {
            if (userInfo != null) append("$userInfo@")
            append(config.server.orEmpty())
            if (queryText.isNotEmpty()) append("?$queryText")
            append("#${Utils.encodeURIComponent(remarks)}")
        }
    }

    /** The gateway and, when set, the local identity — the two names one IKEv2 profile goes by. */
    fun defaultRemarks(config: ProfileItem): String {
        val id = config.ikev2LocalId?.takeIf { it.isNotBlank() && it != config.server }
        return listOfNotNull("IKEv2", config.server, id).joinToString(" ")
    }
}
