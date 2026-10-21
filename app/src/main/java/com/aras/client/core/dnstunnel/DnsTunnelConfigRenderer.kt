package com.aras.client.core.dnstunnel

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.extension.nullIfBlank

/**
 * Writes the configuration the DNS-tunnel engines read.
 *
 * Both engines take the same TOML file and the same plain-text resolver list;
 * the CottenDns fork adds keys of its own, and the StormDNS engine refuses a
 * configuration carrying keys it does not know, so each engine gets only the
 * keys it understands.
 *
 * The keys are the engines' own (StormDNS `client_config.toml`), not an
 * app-level format: everything here is a value the engine itself reads.
 */
object DnsTunnelConfigRenderer {

    /** The encryption methods the engines offer, in the engine's own numbering. */
    val ENCRYPTION_METHODS = listOf(
        "None" to 0,
        "XOR" to 1,
        "ChaCha20" to 2,
        "AES-128-GCM" to 3,
        "AES-192-GCM" to 4,
        "AES-256-GCM" to 5,
    )

    /** The resolver balancing strategies the engines offer. */
    val BALANCING_STRATEGIES = listOf(
        "Random" to 1,
        "Round robin" to 2,
        "Least loss" to 3,
        "Lowest latency" to 4,
    )

    /** The presets the CottenDns engine ships. */
    val COTTEN_PRESETS = listOf("default", "speed", "survival", "tcp-survival")

    /**
     * The engine's client configuration.
     *
     * The StormDNS family reads a TOML file; Soar reads a JSON one. Both are
     * written from the profile here, so the app does not have to know which
     * engine it is starting beyond the format it hands over.
     *
     * @param socksPort the loopback port the engine serves its SOCKS5 on
     * @param dnsPort the loopback port the engine serves its own DNS on, or null
     *   to leave the engine's local DNS off
     */
    fun renderConfig(
        profile: ProfileItem,
        engine: DnsTunnelEngine,
        socksPort: Int,
        dnsPort: Int?,
    ): String =
        if (engine.jsonConfig) {
            renderSoarJson(profile, socksPort, dnsPort)
        } else {
            renderToml(profile, engine, socksPort, dnsPort)
        }

    /**
     * The Soar client's configuration.
     *
     * The key here is the tunnel server's public key rather than a shared
     * secret: Soar authenticates the server, it does not encrypt the tunnel.
     */
    fun renderSoarJson(profile: ProfileItem, socksPort: Int, dnsPort: Int?): String {
        val zone = profile.dnsTunnelDomain?.trim().orEmpty()
        val key = profile.dnsTunnelKey?.trim().orEmpty()
        val resolvers = normalizeResolvers(profile.dnsTunnelResolvers)
        val country = profile.dnsTunnelCountry?.trim()?.uppercase()?.takeIf { it.length == 2 }
        return buildString {
            append("{\n")
            append("  \"zone\": ${quote(zone)},\n")
            append("  \"server_public_key\": ${quote(key)},\n")
            append("  \"listen\": ${quote("127.0.0.1:$socksPort")}")
            if (dnsPort != null) {
                append(",\n  \"dns_listen\": ${quote("127.0.0.1:$dnsPort")}")
            }
            append(",\n  \"resolvers\": [")
            append(resolvers.joinToString(", ") { quote(it) })
            append("]")
            if (country != null) {
                append(",\n  \"discovery\": { \"enabled\": true, \"country\": ${quote(country)} }")
            }
            append("\n}\n")
        }
    }

    /**
     * Whether [text] is a server public key the engine can use: 32 bytes, as
     * base64 or as hex.
     */
    fun isValidPublicKey(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return false
        if (value.length == 64 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return true
        return runCatching { java.util.Base64.getDecoder().decode(value) }.getOrNull()?.size == 32 ||
            runCatching { java.util.Base64.getUrlDecoder().decode(value) }.getOrNull()?.size == 32
    }

    /**
     * The engine's client configuration, in the StormDNS family's TOML.
     *
     * @param socksPort the loopback port the engine serves its SOCKS5 on
     * @param dnsPort the loopback port the engine serves its own DNS on, or null
     *   to leave the engine's local DNS off
     */
    fun renderToml(
        profile: ProfileItem,
        engine: DnsTunnelEngine,
        socksPort: Int,
        dnsPort: Int?,
    ): String {
        val domain = profile.dnsTunnelDomain?.trim().orEmpty()
        val method = profile.dnsTunnelMethod?.takeIf { it in 0..5 } ?: 1
        val key = profile.dnsTunnelKey?.trim().orEmpty()
        return buildString {
            appendLine("DOMAINS = [${quote(domain)}]")
            appendLine("DATA_ENCRYPTION_METHOD = $method")
            appendLine("ENCRYPTION_KEY = ${quote(key)}")
            appendLine("PROTOCOL_TYPE = \"SOCKS5\"")
            appendLine("LISTEN_IP = \"127.0.0.1\"")
            appendLine("LISTEN_PORT = $socksPort")
            if (dnsPort != null) {
                appendLine("LOCAL_DNS_ENABLED = true")
                appendLine("LOCAL_DNS_IP = \"127.0.0.1\"")
                appendLine("LOCAL_DNS_PORT = $dnsPort")
            } else {
                appendLine("LOCAL_DNS_ENABLED = false")
            }
            // The engine writes its own log files next to the configuration; the
            // app reads the engine's output instead and has no use for them.
            appendLine("LOG_TO_FILE = false")
            appendLine("LOG_LEVEL = \"warning\"")
            if (engine == DnsTunnelEngine.COTTEN) {
                // CottenDns can carry the tunnel over DoT and DoH as well as plain
                // UDP, which is what survives a network that throttles UDP/53.
                appendLine("RESOLVER_TRANSPORT = \"auto\"")
                // The engine's own presets: the speed, survival and tcp-survival
                // ones it ships as client_config.*.toml.
                profile.dnsTunnelPreset?.trim()?.takeIf { it in COTTEN_PRESETS }?.let {
                    appendLine("CONFIG_PRESET = \"$it\"")
                }
            }
            profile.dnsTunnelExtra?.nullIfBlank()?.let { extra ->
                appendLine()
                appendLine(extra.trim())
            }
        }
    }

    /**
     * The engine's resolver list: one resolver per line, as the engine reads it.
     *
     * Comments and blank lines are dropped, and a resolver repeated in the
     * profile is written once.
     */
    fun renderResolvers(profile: ProfileItem): String =
        normalizeResolvers(profile.dnsTunnelResolvers).joinToString("\n")

    /**
     * The resolvers of [text], in the order they were written, without comments
     * and without repeats.
     */
    fun normalizeResolvers(text: String?): List<String> {
        val seen = LinkedHashSet<String>()
        text?.lineSequence()?.forEach { line ->
            val entry = line.substringBefore('#').trim()
            if (entry.isNotEmpty()) seen.add(entry)
        }
        return seen.toList()
    }

    /**
     * Whether [text] is a resolver list the engine can read: an address, an
     * address with a port, or an address with a prefix.
     */
    fun isValidResolver(entry: String): Boolean {
        var target = entry.substringBefore('#').trim()
        if (target.isEmpty()) return false

        val slash = target.lastIndexOf('/')
        if (slash >= 0) {
            val bits = target.substring(slash + 1).toIntOrNull() ?: return false
            if (bits !in 0..32) return false
            target = target.substring(0, slash)
        }

        val host: String
        if (target.startsWith("[")) {
            val closing = target.indexOf(']')
            if (closing < 0) return false
            host = target.substring(1, closing)
            val rest = target.substring(closing + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) return false
                if (!isValidPort(rest.substring(1))) return false
            }
        } else {
            val colon = target.lastIndexOf(':')
            // A single colon is a port; several are an IPv6 address without one.
            if (colon >= 0 && target.count { it == ':' } == 1) {
                if (!isValidPort(target.substring(colon + 1))) return false
                host = target.substring(0, colon)
            } else {
                host = target
            }
        }
        return isIpv4(host) || isIpv6(host)
    }

    private fun isValidPort(text: String): Boolean =
        text.toIntOrNull()?.let { it in 1..65535 } == true

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.all { it.isDigit() } && part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun isIpv6(host: String): Boolean {
        if (!host.contains(':')) return false
        val doubleColon = host.indexOf("::")
        if (doubleColon >= 0 && host.indexOf("::", doubleColon + 1) >= 0) return false
        val groups = host.split(':').filter { it.isNotEmpty() }
        if (groups.size > 8) return false
        return groups.all { group ->
            group.length in 1..4 && group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }
    }

    /** A TOML string, quoted the way the engines' parser expects. */
    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
