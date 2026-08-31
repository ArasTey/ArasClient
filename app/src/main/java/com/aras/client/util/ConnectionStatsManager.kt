package com.aras.client.util

import android.net.TrafficStats
import com.aras.client.AppConfig
import com.aras.client.handler.MmkvManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tracks connection statistics: session duration and device-level traffic
 * sampled while the VPN is running, with lifetime totals persisted in MMKV.
 *
 * TrafficStats counts whole-device traffic; while the VPN tunnel is up,
 * app traffic flows through the tunnel so the deltas are the on-VPN bytes.
 * Sampling the deltas only while running avoids counting background usage
 * outside the VPN.
 */
object ConnectionStatsManager {

    data class Snapshot(
        val sessionMillis: Long = 0,
        val sessionDown: Long = 0,
        val sessionUp: Long = 0,
        val totalDown: Long = 0,
        val totalUp: Long = 0,
        val connectCount: Long = 0,
    )

    private const val KEY_TOTAL_DOWN = "stats_total_down"
    private const val KEY_TOTAL_UP = "stats_total_up"
    private const val KEY_SESSION_MS = "stats_session_ms"
    private const val KEY_CONNECT_COUNT = "stats_connect_count"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running = false
    @Volatile private var sessionStart = 0L
    @Volatile private var lastDown = -1L
    @Volatile private var lastUp = -1L
    @Volatile private var sessionDown = 0L
    @Volatile private var sessionUp = 0L
    @Volatile private var lastSnapshot: Snapshot = Snapshot()

    private fun rxCounter(): Long {
        val uid = android.os.Process.myUid()
        val v = TrafficStats.getUidRxBytes(uid)
        return if (v >= 0) v else TrafficStats.getTotalRxBytes()
    }

    private fun txCounter(): Long {
        val uid = android.os.Process.myUid()
        val v = TrafficStats.getUidTxBytes(uid)
        return if (v >= 0) v else TrafficStats.getTotalTxBytes()
    }

    /** Called when the VPN tunnel is actually up. */
    fun onSessionStarted() {
        if (running) return
        running = true
        sessionStart = System.currentTimeMillis()
        sessionDown = 0
        sessionUp = 0
        lastDown = rxCounter()
        lastUp = txCounter()
        MmkvManager.encodeSettings(
            KEY_CONNECT_COUNT,
            (MmkvManager.decodeSettingsLong(KEY_CONNECT_COUNT, 0) + 1).toInt()
        )
        scope.launch {
            while (running) {
                delay(2000)
                sample()
            }
        }
    }

    /** Called when the VPN tunnel goes down; flushes session into totals. */
    fun onSessionStopped() {
        if (!running) return
        running = false
        sample()
        val totalDown = MmkvManager.decodeSettingsLong(KEY_TOTAL_DOWN, 0) + sessionDown
        val totalUp = MmkvManager.decodeSettingsLong(KEY_TOTAL_UP, 0) + sessionUp
        val session = System.currentTimeMillis() - sessionStart
        MmkvManager.encodeSettings(KEY_TOTAL_DOWN, totalDown)
        MmkvManager.encodeSettings(KEY_TOTAL_UP, totalUp)
        MmkvManager.encodeSettings(KEY_SESSION_MS, session)
    }

    private fun sample() {
        try {
            val nowDown = rxCounter()
            val nowUp = txCounter()
            if (lastDown >= 0 && nowDown >= lastDown) {
                sessionDown += nowDown - lastDown
            }
            if (lastUp >= 0 && nowUp >= lastUp) {
                sessionUp += nowUp - lastUp
            }
            lastDown = nowDown
            lastUp = nowUp
            lastSnapshot = Snapshot(
                sessionMillis = System.currentTimeMillis() - sessionStart,
                sessionDown = sessionDown,
                sessionUp = sessionUp,
                totalDown = MmkvManager.decodeSettingsLong(KEY_TOTAL_DOWN, 0) + sessionDown,
                totalUp = MmkvManager.decodeSettingsLong(KEY_TOTAL_UP, 0) + sessionUp,
                connectCount = MmkvManager.decodeSettingsLong(KEY_CONNECT_COUNT, 0),
            )
        } catch (_: Exception) {
            // TrafficStats can be unavailable on exotic builds; stats are best-effort.
        }
    }

    /** Current cumulative numbers (session + lifetime). */
    fun snapshot(): Snapshot = lastSnapshot
}
