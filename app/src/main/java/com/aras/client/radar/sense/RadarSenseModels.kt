package com.aras.client.radar.sense

/**
 * On-device network atmosphere classification.
 * Not a legal proof of DPI — a practical signal for protocol strategy.
 */
enum class NetworkAtmosphere {
    /** Domestic + global targets mostly reachable. */
    CLEAR,
    /** Partial failures; soft filtering or congestion likely. */
    TURBULENT,
    /** Global / TLS paths fail while base connectivity exists. */
    HEAVY_FILTER,
    /** Almost nothing responds. */
    OFFLINE,
    UNKNOWN
}

data class SenseProbe(
    val id: String,
    val label: String,
    val category: String,
    val ok: Boolean,
    val latencyMs: Long?,
    val detail: String
)

data class ProtocolHint(
    val primary: String,
    val alternatives: List<String>,
    val reason: String
)

data class RadarSenseReport(
    val atmosphere: NetworkAtmosphere,
    val score: Int,
    /** Stable short fingerprint of this network condition (for future Smart Connect). */
    val fingerprint: String,
    val probes: List<SenseProbe>,
    val hint: ProtocolHint,
    val summaryFa: String,
    val elapsedMs: Long
)
