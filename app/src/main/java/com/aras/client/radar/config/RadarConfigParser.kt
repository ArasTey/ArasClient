package com.aras.client.radar.config

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/** Parsing is separate from reachability: a valid URI is not a working tunnel. */
object RadarConfigParser {
    private val linkRegex = Regex(
        """(?i)(?:vmess|vless|trojan|ss|hy2|hysteria|hysteria2|tuic|socks|socks5|http|anytls|mieru|masque|wireguard|amneziawg|awg)://[^\s"'<>]+"""
    )
    private val supported = setOf(
        "vmess", "vless", "trojan", "ss", "hy2", "hysteria", "hysteria2",
        "tuic", "socks", "socks5", "http", "anytls", "mieru", "masque",
        "wireguard", "amneziawg", "awg"
    )

    fun extract(raw: String, source: String): List<RadarConfigCandidate> {
        if (raw.isBlank()) return emptyList()

        // Some feeds mix readable links with base64 blocks or JSON-wrapped
        // subscriptions. Parse both forms; returning after the first direct link
        // used to silently discard every encoded entry later in the same feed.
        val found = LinkedHashMap<String, RadarConfigCandidate>()
        fun collect(text: String) {
            linkRegex.findAll(text).forEach { match ->
                parse(match.value.trimEnd(',', ';', ')', ']', '}'), source)?.let { candidate ->
                    found.putIfAbsent(candidate.identity, candidate)
                }
            }
        }

        collect(raw)
        val encodedInputs = buildList {
            val compact = raw.filterNot(Char::isWhitespace)
            if (compact.length in 24..2_000_000) add(compact)
            raw.lineSequence()
                .map { it.trim() }
                .filter { it.length in 24..2_000_000 && !it.contains("://") }
                .forEach { add(it) }
        }.distinct()

        encodedInputs.forEach { encoded ->
            val decoded = decodeBase64(encoded) ?: return@forEach
            if (decoded != raw && decoded.contains("://")) collect(decoded)
        }
        return found.values.toList()
    }

    fun parse(rawLink: String, source: String): RadarConfigCandidate? {
        val link = rawLink.trim().trimEnd(',', ';', ')', ']', '}')
        val scheme = link.substringBefore("://", "").lowercase(Locale.ROOT)
        if (scheme !in supported) return null
        val endpoint = if (scheme == "vmess") parseVmess(link) else parseUriEndpoint(link, scheme)
        val (host, port, remark) = endpoint ?: return null
        if (host.isBlank() || host.length > 253 || port !in 1..65535) return null
        return RadarConfigCandidate(
            uri = link,
            protocol = if (scheme == "hy2") "hysteria2" else scheme,
            host = host,
            port = port,
            source = source,
            remark = remark,
            identity = canonicalIdentity(link)
        )
    }

    private fun parseVmess(link: String): Triple<String, Int, String>? {
        val encoded = link.substringAfter("://").substringBefore('#')
        val json = decodeBase64(encoded) ?: return null
        return runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            val host = obj.get("add")?.asString?.trim().orEmpty()
            val port = obj.get("port")?.let {
                if (it.isJsonPrimitive) it.asString.toIntOrNull() else null
            } ?: return null
            val remark = obj.get("ps")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            Triple(host, port, remark)
        }.getOrNull()
    }

    private fun parseUriEndpoint(link: String, scheme: String): Triple<String, Int, String>? {
        val parsed = runCatching { URI(link) }.getOrNull() ?: return null
        val authority = parsed.rawAuthority ?: return null
        var hostPort = authority.substringAfterLast('@')

        // SIP002 supports both encoded user-info@host:port and legacy whole-authority Base64.
        if (scheme == "ss" && !hostPort.contains(':')) {
            val decoded = decodeBase64(hostPort) ?: return null
            hostPort = decoded.substringAfterLast('@')
        }

        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close <= 1 || close + 2 > hostPort.length || hostPort[close + 1] != ':') return null
            host = hostPort.substring(1, close)
            port = hostPort.substring(close + 2).toIntOrNull() ?: return null
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon <= 0 || colon == hostPort.lastIndex) return null
            host = hostPort.substring(0, colon)
            port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        }
        val remark = parsed.rawFragment?.let {
            runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it)
        }.orEmpty()
        return Triple(host.trim().trim('[', ']'), port, remark)
    }

    private fun canonicalIdentity(link: String): String {
        val withoutRemark = link.substringBefore('#')
        val scheme = withoutRemark.substringBefore("://", "").lowercase(Locale.ROOT)
        val rest = withoutRemark.substringAfter("://")
        val authorityEnd = listOf(rest.indexOf('/'), rest.indexOf('?')).filter { it >= 0 }.minOrNull() ?: rest.length
        val authority = rest.substring(0, authorityEnd)
        val normalizedAuthority = if (authority.contains('@')) {
            authority.substringBeforeLast('@') + "@" + authority.substringAfterLast('@').lowercase(Locale.ROOT)
        } else authority.lowercase(Locale.ROOT)
        return "$scheme://$normalizedAuthority" + rest.substring(authorityEnd)
    }

    private fun decodeBase64(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null
        val decoders = listOf(Base64.getDecoder(), Base64.getUrlDecoder(), Base64.getMimeDecoder())
        for (decoder in decoders) {
            val decoded = runCatching { decoder.decode(normalized).toString(StandardCharsets.UTF_8) }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        return null
    }
}
