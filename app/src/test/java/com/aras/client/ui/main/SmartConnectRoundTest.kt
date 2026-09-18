package com.aras.client.ui.main

import org.junit.Assert.*
import org.junit.Test

class SmartConnectRoundTest {
    @Test fun capturesCandidatesAndIgnoresFasterUntestedAndForeignServers() {
        val tracker = SmartConnectRoundTracker()
        val candidates = mutableListOf("slow", "fast", "foreign")
        val round = tracker.begin("sub", candidates, true)!!
        candidates.add("untested")
        val delays = mapOf("slow" to 90L, "fast" to 20L, "foreign" to 1L, "untested" to 2L)
        assertEquals("fast", bestSmartConnectCandidate(round, "sub", delays.keys,
            { if (it == "foreign") "other" else "sub" }, { delays[it] }))
    }

    @Test fun changedSelectionAndAllTabNeverConnectAcrossSubscriptions() {
        val tracker = SmartConnectRoundTracker()
        for (group in listOf("sub", "")) {
            val round = tracker.begin(group, listOf("server"), true)!!
            assertNull(bestSmartConnectCandidate(round, "other", setOf("server"), { group }, { 1L }))
        }
        val all = tracker.begin("", listOf("server"), true)!!
        assertNull(bestSmartConnectCandidate(all, "", setOf("server"), { "" }, { 1L }))
    }

    @Test fun deletedFailedAndUntestedCandidatesCannotWin() {
        val round = SmartConnectRoundTracker().begin("sub", listOf("deleted", "zero", "failed", "missing"), true)!!
        assertNull(bestSmartConnectCandidate(round, "sub", setOf("zero", "failed", "missing"), { "sub" },
            { when (it) { "deleted" -> 1L; "zero" -> 0L; "failed" -> -1L; else -> null } }))
    }

    @Test fun cancellationAndEmptyRoundInvalidatePendingCompletion() {
        val tracker = SmartConnectRoundTracker()
        val old = tracker.begin("sub", listOf("a"), true)!!
        tracker.dispatched(old)
        assertSame(old, tracker.claimFinish())
        tracker.invalidate()
        assertFalse(tracker.isCurrent(old))
        assertNull(tracker.claimFinish())
        tracker.begin("sub", emptyList(), true)
        assertNull(tracker.current)
        assertNull(tracker.claimFinish())
    }

    @Test fun newRoundRejectsOldLocalCompletionAndDuplicateFinish() {
        val tracker = SmartConnectRoundTracker()
        val old = tracker.begin("sub", listOf("old"), true)!!
        tracker.dispatched(old)
        val completion = tracker.claimFinish()!!
        val next = tracker.begin("sub", listOf("next"), false)!!
        assertFalse(tracker.isCurrent(completion))
        tracker.dispatched(old)
        assertNull(tracker.claimFinish())
        tracker.dispatched(next)
        assertSame(next, tracker.claimFinish())
        assertFalse(next.smartConnect)
        assertNull(tracker.claimFinish())
    }
}
