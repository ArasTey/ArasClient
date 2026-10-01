package com.aras.client.fmt

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.idnHost
import com.aras.client.extension.nullIfBlank
import com.aras.client.util.Utils
import java.net.URI

/**
 * Share-link format for a Mieru profile.
 *
 * `mieru://user:pass@host:port?mtu=1360#remarks`
 *
 * The host may be an address or a name: the mieru client is told which of the two it is,
 * and only one of them can carry a profile. The scheme is not added here —
 * AngConfigManager.shareConfig prepends the config type's own.
 */
object MieruFmt : FmtBase() {

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.MIERU)

        val uri = URI(Utils.fixIllegalUrl(str))
        val queryParam = if (uri.rawQuery.isNullOrEmpty()) emptyMap() else getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "Mieru" } }
        config.server = uri.idnHost
        config.serverPort = uri.port.takeIf { it > 0 }?.toString()

        val userInfo = Utils.decodeURIComponent(uri.userInfo.orEmpty())
        if (userInfo.isNotEmpty()) {
            val split = userInfo.indexOf(':')
            if (split > 0) {
                config.username = userInfo.substring(0, split)
                config.password = userInfo.substring(split + 1)
            } else {
                config.username = userInfo
            }
        }
        config.mtu = queryParam["mtu"]?.toIntOrNull()

        return config
    }

    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()
        config.mtu?.takeIf { it > 0 }?.let { dicQuery["mtu"] = it.toString() }

        val user = listOfNotNull(
            config.username?.nullIfBlank(),
            config.password?.nullIfBlank(),
        ).joinToString(":")
        val remarks = config.remarks.takeIf { it.isNotBlank() } ?: "Mieru"
        return toUri(config, user.ifEmpty { null }, dicQuery) + "#" + Utils.encodeURIComponent(remarks)
    }
}
