package com.aras.client.fmt

import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherPsiphon
import com.aras.client.enums.AetherPsiphonMode
import com.aras.client.enums.AetherTor
import com.aras.client.enums.AetherTorBridges
import com.aras.client.enums.AetherTorRelays
import com.aras.client.enums.EConfigType
import com.aras.client.util.Utils
import java.net.URI

/**
 * Share-link format for a Psiphon or Tor profile: the carrier alone, with no Aether
 * tunnel and no server of its own. The core runs it on its own listener, so the link
 * carries the carrier's settings and nothing an Aether link carries.
 *
 * - `psiphon://?region=DE&mode=auto&listen=10819#Name`
 * - `tor://?bridges=auto&relays=auto&bridges_own=...&listen=10819#Name`
 *
 * The scheme is not written by [toUri]: AngConfigManager.shareConfig prepends the
 * config type's own scheme, as it does for every other format.
 */
object CarrierFmt : FmtBase() {

    fun parse(str: String): ProfileItem? {
        // A hand-written link can be unparseable ("tor://" alone throws), and an import
        // must read that as a link this format does not take rather than stop.
        val uri = runCatching { URI(Utils.fixIllegalUrl(str)) }.getOrNull() ?: return null
        val type = when {
            uri.scheme.equals("psiphon", ignoreCase = true) -> EConfigType.PSIPHON
            uri.scheme.equals("tor", ignoreCase = true) -> EConfigType.TOR
            else -> return null
        }
        val config = ProfileItem.create(type)
        val query = if (uri.rawQuery.isNullOrEmpty()) emptyMap() else getQueryParam(uri)
        config.aetherListenPort = AetherFmt.listenPortOf(query["listen"])?.let(AetherFmt::storedListenPort)
        when (type) {
            EConfigType.PSIPHON -> {
                config.aetherPsiphon = AetherPsiphon.ONLY.type
                config.aetherPsiphonMode = AetherPsiphonMode.fromString(query["mode"]).type
                config.aetherPsiphonRegion = query["region"]?.trim()?.uppercase()?.ifEmpty { null }
            }
            else -> {
                config.aetherTor = AetherTor.ONLY.type
                config.aetherTorBridges = AetherTorBridges.fromString(query["bridges"]).type
                config.aetherTorRelays = AetherTorRelays.fromString(query["relays"]).type
                // The link separates bridge lines with a semicolon; the profile keeps one per line.
                config.aetherTorBridgeLines = AetherFmt.bridgeLines(query["bridges_own"]?.replace(';', '\n'))
                    .takeIf { it.isNotEmpty() }?.joinToString("\n")
            }
        }
        // Named after the region, so it is read only once every setting is in place.
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { defaultRemarks(config) }
        return config
    }

    fun toUri(config: ProfileItem): String {
        val query = linkedMapOf<String, String>()
        if (config.configType == EConfigType.TOR) {
            query["bridges"] = AetherTorBridges.fromString(config.aetherTorBridges).type
            query["relays"] = AetherTorRelays.fromString(config.aetherTorRelays).type
            // A bridge line never holds a semicolon: the core separates them with one.
            AetherFmt.bridgeLines(config.aetherTorBridgeLines).takeIf { it.isNotEmpty() }
                ?.let { query["bridges_own"] = it.joinToString(";") }
        } else {
            config.aetherPsiphonRegion?.takeIf { it.isNotBlank() }?.let { query["region"] = it }
            query["mode"] = AetherPsiphonMode.fromString(config.aetherPsiphonMode).type
        }
        AetherFmt.listenPortOf(config.aetherListenPort)?.let(AetherFmt::storedListenPort)
            ?.let { query["listen"] = it }
        val queryText = query.entries.joinToString("&") {
            "${it.key}=${Utils.encodeURIComponent(it.value)}"
        }
        val remarks = config.remarks.takeIf { it.isNotBlank() } ?: defaultRemarks(config)
        return "?$queryText#${Utils.encodeURIComponent(remarks)}"
    }

    /**
     * The name a profile is given when it has none of its own: the carrier, the
     * region it pins when it pins one, and the port it listens on — the settings
     * that actually tell two such profiles apart. [autoPort] says the app chose
     * that port rather than the user.
     */
    fun defaultRemarks(config: ProfileItem, autoPort: Boolean = false): String {
        val port = AetherFmt.listenPortOf(config.aetherListenPort)
            ?: AppConfig.PORT_AETHER_SOCKS.toInt()
        return listOfNotNull(
            if (config.configType == EConfigType.TOR) "Tor" else "Psiphon",
            config.aetherPsiphonRegion?.takeIf { it.isNotBlank() },
            if (autoPort) "auto port $port" else "port $port",
        ).joinToString(" ")
    }
}
