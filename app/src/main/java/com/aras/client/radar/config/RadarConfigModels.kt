package com.aras.client.radar.config

enum class RadarProbeStatus { INVALID, DNS_FAILED, PRIVATE_ADDRESS, TCP_FAILED, TCP_REACHABLE }

data class RadarConfigCandidate(
    val uri: String, val protocol: String, val host: String, val port: Int,
    val source: String, val remark: String = "", val countryIso: String = "", val identity: String
)

data class RadarConfigCheck(
    val candidate: RadarConfigCandidate, val status: RadarProbeStatus,
    val dnsLatencyMs: Long? = null, val tcpLatencyMs: Long? = null,
    val detail: String, val resolvedIp: String? = null
) {
    val tcpReachable: Boolean get() = status == RadarProbeStatus.TCP_REACHABLE
}

data class RadarSourceReport(val source: String, val fetchedFiles: Int, val parsedCandidates: Int, val error: String? = null)
data class RadarDiscoveryReport(val candidates: List<RadarConfigCandidate>, val sources: List<RadarSourceReport>)
