package com.aras.client.ui.main

/** Local round identity only: the test service broadcasts do not carry request IDs. */
internal class SmartConnectRoundTracker {
    class Round(val groupId: String, val candidates: List<String>, val smartConnect: Boolean) {
        internal var dispatched = false
        internal var finishing = false
    }

    var current: Round? = null
        private set

    fun begin(groupId: String, candidates: List<String>, smartConnect: Boolean): Round? {
        current = candidates.takeIf { it.isNotEmpty() }?.let {
            Round(groupId, it.distinct(), smartConnect)
        }
        return current
    }

    fun invalidate() { current = null }
    fun isCurrent(round: Round): Boolean = current === round

    fun dispatched(round: Round) {
        if (isCurrent(round)) round.dispatched = true
    }

    fun claimFinish(): Round? = current?.takeIf { it.dispatched && !it.finishing }?.also {
        it.finishing = true
    }
}

/** Never consider cached pings outside the actual request or outside its subscription. */
internal fun bestSmartConnectCandidate(
    round: SmartConnectRoundTracker.Round,
    selectedGroupId: String,
    currentMembers: Set<String>,
    subscriptionOf: (String) -> String?,
    delayOf: (String) -> Long?
): String? {
    if (!round.smartConnect || round.groupId.isEmpty() || selectedGroupId != round.groupId) return null
    return round.candidates.asSequence()
        .filter { it in currentMembers && subscriptionOf(it) == round.groupId }
        .mapNotNull { guid -> delayOf(guid)?.takeIf { it > 0L }?.let { guid to it } }
        .minByOrNull { it.second }?.first
}
