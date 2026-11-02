package com.aras.client.radar.config

import java.util.concurrent.ConcurrentHashMap

/** Short-lived in-memory probe cache; credentials are not written to persistent storage. */
object RadarConfigProbeCache {
    private data class Entry(val check: RadarConfigCheck, val savedAtMs: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(candidate: RadarConfigCandidate, nowMs: Long = System.currentTimeMillis()): RadarConfigCheck? {
        val entry = entries[candidate.identity] ?: return null
        val ttl = if (entry.check.tcpReachable) SUCCESS_TTL_MS else FAILURE_TTL_MS
        if (nowMs - entry.savedAtMs > ttl) {
            entries.remove(candidate.identity, entry)
            return null
        }
        return entry.check.copy(candidate = candidate)
    }

    fun put(check: RadarConfigCheck, nowMs: Long = System.currentTimeMillis()) {
        entries[check.candidate.identity] = Entry(check, nowMs)
        if (entries.size > MAX_ENTRIES) {
            val oldest = entries.entries.minByOrNull { it.value.savedAtMs }?.key
            if (oldest != null) entries.remove(oldest)
        }
    }

    fun clear() = entries.clear()
    private const val SUCCESS_TTL_MS = 3 * 60 * 1000L
    private const val FAILURE_TTL_MS = 45 * 1000L
    private const val MAX_ENTRIES = 2_000
}
