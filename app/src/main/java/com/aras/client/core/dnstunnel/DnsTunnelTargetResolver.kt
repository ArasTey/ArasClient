package com.aras.client.core.dnstunnel

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.handler.MmkvManager

/**
 * Resolves the target of a DNS-tunnel profile: the name of exactly one other
 * profile whose traffic the tunnel carries.
 *
 * A DNS tunnel is a transport, not a protocol: the engine hands the bytes it
 * carries to a local SOCKS, and something has to speak over that SOCKS. The
 * target is that something — the profile the tunnel is used to reach.
 *
 * A name no profile has, or that several have, resolves to nothing: the tunnel
 * then carries nothing itself and the editor says why.
 */
object DnsTunnelTargetResolver {

    data class Resolved(
        val matches: List<ProfileItem>,
        val name: String,
    ) {
        /** Exactly one selectable profile, and it is one the app can build an outbound for. */
        val ok: Boolean
            get() = matches.size == 1 && matches[0].configType != EConfigType.DNSTUNNEL

        val single: ProfileItem? get() = if (ok) matches[0] else null
    }

    fun resolve(name: String?): Resolved {
        val wanted = name?.trim().orEmpty()
        if (wanted.isEmpty()) return Resolved(emptyList(), "")
        val matches = buildList {
            (listOf("") + MmkvManager.decodeSubsList()).forEach { subId ->
                MmkvManager.decodeServerList(subId).forEach { guid ->
                    MmkvManager.decodeServerConfig(guid)
                        ?.takeIf {
                            it.configType != EConfigType.DNSTUNNEL && it.remarks == wanted
                        }
                        ?.let(::add)
                }
            }
        }
        return Resolved(matches.distinctBy { it.remarks + it.configType.name }, wanted)
    }
}
