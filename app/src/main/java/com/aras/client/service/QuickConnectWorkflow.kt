package com.aras.client.service

/** Chooses only a currently indexed member with a successful real-ping delay. */
internal fun selectFastestReachableServer(
    orderedMembers: List<String>,
    delayOf: (String) -> Long?,
): String? = orderedMembers
    .asSequence()
    .mapNotNull { guid -> delayOf(guid)?.takeIf { it > 0L }?.let { guid to it } }
    .minByOrNull { it.second }
    ?.first
