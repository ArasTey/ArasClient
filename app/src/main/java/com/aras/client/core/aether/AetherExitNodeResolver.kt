package com.aras.client.core.aether

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.handler.MmkvManager

/**
 * Resolves the exit node of an Aether profile: the name of exactly one other
 * profile whose outbound the aether core dials out through (PattNG's
 * AetherExit.node). A name no profile has, or that several have, resolves to
 * nothing — the core keeps its direct exit and the UI says why.
 */
object AetherExitNodeResolver {

    data class Resolved(
        val matches: List<ProfileItem>,
        val name: String,
    ) {
        /** Exactly one selectable profile, and it is one the app can build an outbound for. */
        val ok: Boolean
            get() = matches.size == 1 && !matches[0].configType.isCarrierOnly && matches[0].configType != EConfigType.AETHER

        val single: ProfileItem? get() = if (ok) matches[0] else null
    }

    fun resolve(name: String?): Resolved {
        val wanted = name?.trim().orEmpty()
        if (wanted.isEmpty()) return Resolved(emptyList(), "")
        val matches = buildList {
            (listOf("") + MmkvManager.decodeSubsList()).forEach { subId ->
                MmkvManager.decodeServerList(subId).forEach { guid ->
                    MmkvManager.decodeServerConfig(guid)
                        ?.takeIf { !it.configType.isCarrierOnly && it.configType != EConfigType.AETHER && it.remarks == wanted }
                        ?.let(::add)
                }
            }
        }
        // The matches stay whole: two profiles of the same name are exactly the
        // ambiguity this resolver exists to refuse, not duplicates to collapse.
        return Resolved(matches, wanted)
    }
}
