package com.aras.client.ui.dns

/**
 * Parses SlipNet-style resolver lists: one entry per line or comma separated.
 * Supported: single IPv4 ("9.9.9.9"), "ip:port" (port ignored, scanner uses 53),
 * dash range ("1.1.1.1-1.1.1.10" or "1.1.1.1-10"), and CIDR ("8.8.8.0/24").
 */
object DnsScanInput {
    const val MAX_TARGETS = 4096

    data class Parsed(val ips: List<String>, val invalid: List<String>, val truncated: Boolean)

    fun parse(text: String, limit: Int = MAX_TARGETS): Parsed {
        val ips = LinkedHashSet<String>()
        val invalid = mutableListOf<String>()
        var truncated = false
        text.split('\n', ',', ';', ' ', '\t', '\r').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            val expanded = expand(token)
            if (expanded == null) {
                invalid += token
            } else {
                for (ip in expanded) {
                    if (ips.size >= limit) { truncated = true; break }
                    ips += ip
                }
            }
        }
        return Parsed(ips.toList(), invalid, truncated)
    }

    fun toProfiles(ips: List<String>): List<DnsProfile> =
        ips.map { DnsProfile(it, it, "", "Scan range", custom = true) }

    private fun expand(token: String): List<String>? {
        val noPort = if (token.count { it == ':' } == 1) token.substringBefore(':') else token
        return when {
            noPort.contains('/') -> cidr(noPort)
            noPort.contains('-') -> range(noPort)
            DnsChangerStore.isIpAddress(noPort) -> listOf(noPort)
            else -> null
        }
    }

    private fun cidr(value: String): List<String>? {
        val base = toLong(value.substringBefore('/')) ?: return null
        val prefix = value.substringAfter('/').toIntOrNull() ?: return null
        if (prefix !in 8..32) return null
        val size = 1L shl (32 - prefix)
        val start = base and (size - 1).inv() and 0xFFFFFFFFL
        val first = if (size > 2) 1L else 0L
        val last = if (size > 2) size - 2 else size - 1
        return (first..minOf(last, first + MAX_TARGETS)).map { fromLong(start + it) }
    }

    private fun range(value: String): List<String>? {
        val left = value.substringBefore('-').trim()
        val right = value.substringAfter('-').trim()
        val start = toLong(left) ?: return null
        val end = toLong(right) ?: right.toLongOrNull()?.let { last ->
            if (last in 0..255) (start and 0xFFFFFF00L) or last else null
        } ?: return null
        if (end < start) return null
        return (start..minOf(end, start + MAX_TARGETS)).map { fromLong(it) }
    }

    private fun toLong(ip: String): Long? {
        if (!ip.contains('.') || !DnsChangerStore.isIpAddress(ip)) return null
        return ip.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
    }

    private fun fromLong(v: Long): String =
        "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
}
