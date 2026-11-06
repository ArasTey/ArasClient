package dev.cluvex.zedsecure.core.tor

import dev.cluvex.zedsecure.platform.tcpConnectMillis
import dev.cluvex.zedsecure.shared.resources.Res

object TorBridges {
    data class Bridge(val transport: String, val address: String, val line: String)

    suspend fun load(transport: String): List<Bridge> {
        val text = runCatching { Res.readBytes("files/tor/bridges_default.lst").decodeToString() }
            .getOrDefault("")
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        return lines.mapNotNull { line ->
            val first = line.substringBefore(' ')
            if (transport == "vanilla") {
                if (first.contains(':') && !first.any { it.isLetter() }) Bridge("vanilla", first, line) else null
            } else if (line.startsWith("$transport ")) {
                Bridge(transport, addressToken(line), line)
            } else {
                null
            }
        }
    }

    private fun addressToken(line: String): String {
        val parts = line.split(' ')
        return parts.getOrNull(1)?.takeIf { it.contains(':') && !it.contains('=') }
            ?: parts.firstOrNull { it.contains(':') && !it.contains('=') && !it.startsWith("http") }
            ?: parts.getOrElse(1) { "" }
    }

    suspend fun ping(bridge: Bridge, rendezvous: String = "cdn77", timeoutMs: Int = 4000): Long {
        val (host, port) = reachabilityTarget(bridge, rendezvous) ?: return -1L
        return tcpConnectMillis(host, port, timeoutMs)
    }

    private fun reachabilityTarget(bridge: Bridge, rendezvous: String): Pair<String, Int>? {
        if (bridge.transport == "snowflake") return snowflakeTarget(rendezvous)

        val addr = splitHostPort(bridge.address)

        if (addr != null && !isPlaceholderHost(addr.first)) return addr

        val front = firstHost(valueOf(bridge.line, "front="), valueOf(bridge.line, "fronts="))
        if (front != null) return front to 443
        val urlHost = hostOfUrl(valueOf(bridge.line, "url="))
        if (urlHost != null) return urlHost to 443
        return addr
    }

    private fun snowflakeTarget(rendezvous: String): Pair<String, Int> = when (rendezvous) {
        "cdn77" -> "www.cdn77.com" to 443
        "amazon" -> "sqs.us-east-1.amazonaws.com" to 443
        else -> "cdn.ampproject.org" to 443
    }

    private fun isPlaceholderHost(host: String): Boolean =
        host.startsWith("192.0.2.") || host.startsWith("198.51.100.") || host.startsWith("203.0.113.") ||
            host.startsWith("2001:db8:", ignoreCase = true) || host.startsWith("2001:0db8:", ignoreCase = true)

    private fun valueOf(line: String, key: String): String? =
        line.split(' ').firstOrNull { it.startsWith(key) }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    private fun firstHost(vararg lists: String?): String? =
        lists.firstOrNull { !it.isNullOrEmpty() }?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun hostOfUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val hostAndRest = if ("://" in url) url.substringAfter("://") else url
        return hostAndRest.substringBefore('/').substringBefore(':').trim().takeIf { it.isNotEmpty() }
    }

    private fun splitHostPort(addr: String): Pair<String, Int>? {
        return if (addr.startsWith("[")) {
            val end = addr.indexOf("]:")
            if (end < 0) return null
            addr.substring(1, end) to (addr.substring(end + 2).toIntOrNull() ?: return null)
        } else {
            val i = addr.lastIndexOf(':')
            if (i < 0) return null
            addr.substring(0, i) to (addr.substring(i + 1).toIntOrNull() ?: return null)
        }
    }
}
