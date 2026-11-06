package com.aras.client.ui.dns

/**
 * Parses resolver targets: single IPv4/IPv6 addresses, ip:port, IPv4 ranges and CIDR.
 * A bare IPv6 address is accepted; bracketed IPv6 is required when a port is supplied.
 */
object DnsScanInput {
    const val MAX_TARGETS = 4096

    data class Target(val ip: String, val port: Int = 53)

    data class Parsed(
        val targets: List<Target>,
        val invalid: List<String>,
        val truncated: Boolean,
    ) {
        val ips: List<String> get() = targets.map { it.ip }
    }

    fun parse(text: String, limit: Int = MAX_TARGETS): Parsed {
        val targets = LinkedHashMap<String, Target>()
        val invalid = mutableListOf<String>()
        var truncated = false
        text.split('\n', ',', ';', ' ', '\t', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { token ->
                val expanded = expand(token)
                if (expanded == null) {
                    invalid += token
                } else {
                    for (target in expanded) {
                        if (targets.size >= limit) {
                            truncated = true
                            break
                        }
                        targets.putIfAbsent("${target.ip}:${target.port}", target)
                    }
                }
            }
        return Parsed(targets.values.toList(), invalid, truncated)
    }

    fun toProfiles(targets: List<Target>): List<DnsProfile> =
        targets.map { target ->
            DnsProfile(
                name = if (target.port == 53) target.ip else "${target.ip}:${target.port}",
                primary = target.ip,
                secondary = "",
                region = "Scan range",
                custom = true,
                port = target.port,
            )
        }

    private fun expand(token: String): List<Target>? {
        val (address, port) = splitAddressPort(token) ?: return null
        val ips = when {
            address.contains('/') -> cidr(address)
            address.contains('-') -> range(address)
            DnsChangerStore.isIpAddress(address) -> listOf(address)
            else -> null
        } ?: return null
        return ips.map { Target(it, port) }
    }

    private fun splitAddressPort(token: String): Pair<String, Int>? {
        if (token.startsWith("[")) {
            val close = token.indexOf(']')
            if (close <= 1) return null
            val address = token.substring(1, close)
            val suffix = token.substring(close + 1)
            val port = when {
                suffix.isEmpty() -> 53
                suffix.startsWith(":") -> suffix.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
            if (port !in 1..65535 || !DnsChangerStore.isIpAddress(address)) return null
            return address to port
        }

        // A single colon is an IPv4/hostname-style host:port separator.
        // Unbracketed IPv6 contains multiple colons and is treated as an address.
        if (token.count { it == ':' } == 1) {
            val address = token.substringBefore(':')
            val port = token.substringAfter(':').toIntOrNull() ?: return null
            if (port !in 1..65535 || !DnsChangerStore.isIpAddress(address)) return null
            return address to port
        }
        return token to 53
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
