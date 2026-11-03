package com.aras.client.fmt

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URLEncoder
import java.util.Base64

/** Imports proxy definitions only; Clash rules, groups and providers are not executed. */
object ClashYamlFmt {
    private const val MAX_CHARACTERS = 2_000_000
    private val proxyKey = Regex("(?m)^\\s*(?:[\"']?proxies[\"']?)\\s*:")
    private val leadingProxyKey = Regex("^\\s*\\{\\s*[\"']?proxies[\"']?\\s*:\\s*\\[")

    /** The proxies this app can dial, and how many entries were left out. */
    data class ParseResult(val links: List<String>, val skipped: Int)

    private val commonKeys = setOf("name", "type", "server", "port")

    /** Provider metadata that does not affect how the proxy is dialled. */
    private val annotationKeys = setOf("country")

    private val supportedTypes =
        setOf("ss", "vmess", "vless", "trojan", "hysteria2", "anytls", "socks5")

    /**
     * Keys that only tune a session (UDP relay, TCP fast open, multiplexing,
     * outbound binding) or annotate the proxy. Xray dials the same endpoint
     * without them, so they are accepted and ignored rather than rejecting a
     * whole mihomo document over a field this app cannot express.
     */
    private val tuningKeys = setOf(
        "udp", "tfo", "mptcp", "xudp", "packet-encoding", "smux", "smux-opts",
        "ip-version", "interface-name", "routing-mark", "dialer-proxy",
        "fingerprint", "global-client-fingerprint",
        "idle-session-check-interval", "idle-session-timeout", "min-idle-session",
    )

    private val tlsKeys =
        setOf("tls", "servername", "sni", "skip-cert-verify", "client-fingerprint", "alpn")

    private val transportKeys =
        setOf("network", "ws-opts", "grpc-opts", "h2-opts", "xhttp-opts")

    private fun typeKeys(type: String): Set<String> = when (type) {
        "ss" -> setOf("cipher", "password", "plugin", "plugin-opts")
        "vmess" -> setOf("uuid", "alterId", "cipher")
        "vless" -> setOf("uuid", "flow", "encryption", "reality-opts")
        "trojan" -> setOf("password")
        "hysteria2" -> setOf(
            "password", "obfs", "obfs-password", "up", "down", "ports", "hop-interval"
        )
        "anytls" -> setOf("password")
        "socks5" -> setOf("username", "password")
        else -> emptySet()
    }

    fun isClashYaml(text: String): Boolean {
        val normalized = normalize(text)
        if (proxyKey.containsMatchIn(normalized) || leadingProxyKey.containsMatchIn(normalized)) {
            return true
        }
        if (!normalized.trimStart().startsWith('{')) return false
        return runCatching {
            val root = JsonParser.parseString(normalized)
            root.isJsonObject && root.asJsonObject.get("proxies")?.isJsonArray == true
        }.getOrDefault(false)
    }

    fun toLinks(text: String): ParseResult {
        require(text.length <= MAX_CHARACTERS) { "Clash YAML is too large" }
        val options = LoaderOptions().apply {
            codePointLimit = MAX_CHARACTERS
            nestingDepthLimit = 30
            maxAliasesForCollections = 20
            isAllowDuplicateKeys = false
            setAllowRecursiveKeys(false)
        }
        val root = Yaml(SafeConstructor(options)).load<Any>(normalize(text)) as? Map<*, *>
            ?: throw IllegalArgumentException("Expected a Clash YAML mapping")
        val proxies = root["proxies"] as? List<*>
            ?: throw IllegalArgumentException("Clash YAML must contain a proxies list")
        require(proxies.isNotEmpty() && proxies.size <= 10_000) { "Invalid Clash proxy count" }
        // Third-party subscriptions routinely mix in proxies this client cannot
        // dial; one of them must not discard the whole list. A proxy is still
        // rejected as a unit rather than downgraded, so no dial option is lost.
        var skipped = 0
        val links = proxies.mapNotNull { value ->
            try {
                toLink(value as? Map<*, *> ?: throw IllegalArgumentException("Expected proxy mapping"))
            } catch (e: Exception) {
                skipped++
                null
            }
        }
        return ParseResult(links, skipped)
    }

    private fun toLink(proxy: Map<*, *>): String {
        val type = proxy.string("type")
        require(type in supportedTypes) { "Unsupported proxy type" }
        require(proxy.keys.all { it in commonKeys + typeKeys(type) + tlsKeys + transportKeys + tuningKeys + annotationKeys })
        val host = proxy.string("server").removeSurrounding("[", "]")
        require(host.none { it.isWhitespace() || it in "/?#@" })
        val port = proxy.string("port").toIntOrNull()
        require(port != null && port in 1..65535)
        val address = if (':' in host) "[$host]:$port" else "$host:$port"
        val name = proxy.optional("name") ?: host

        if (type == "socks5") {
            // The core's SOCKS outbound has no TLS layer; a TLS-wrapped SOCKS
            // proxy would silently be dialled in plaintext.
            require(!proxy.boolean("tls", false)) { "TLS SOCKS proxy is unsupported" }
            val username = proxy.optional("username")
            val userInfo = if (username == null) "" else
                "${encode(username)}:${encode(proxy.optional("password").orEmpty())}@"
            return "socks5://$userInfo$address#${encode(name)}"
        }

        val credential = proxy.string(if (type in setOf("vmess", "vless")) "uuid" else "password")

        if (type == "ss") {
            val cipher = proxy.string("cipher")
            val plugin = shadowsocksPlugin(proxy)
            val query = plugin?.let { "?plugin=${encode(it)}" }.orEmpty()
            return "ss://${base64("$cipher:$credential")}@$address$query#${encode(name)}"
        }
        if (type == "hysteria2") {
            val query = linkedMapOf("security" to "tls")
            (proxy.optional("servername") ?: proxy.optional("sni"))?.let { query["sni"] = it }
            if (proxy.boolean("skip-cert-verify", false)) query["insecure"] = "1"
            proxy["alpn"]?.let { value ->
                require(value is List<*> && value.all { it is String })
                query["alpn"] = value.joinToString(",")
            }
            proxy.optional("obfs")?.let {
                require(it.equals("salamander", ignoreCase = true)) {
                    "Unsupported hysteria2 obfs type"
                }
                query["obfs"] = "salamander"
            }
            proxy.optional("obfs-password")?.let { query["obfs-password"] = it }
            proxy.optional("ports")?.let { query["mport"] = it }
            proxy.optional("hop-interval")?.let { query["hop-interval"] = it }
            proxy.optional("up")?.let { bandwidth(it)?.let { v -> query["upmbps"] = v } }
            proxy.optional("down")?.let { bandwidth(it)?.let { v -> query["downmbps"] = v } }
            val q = "?" + query.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            return "hysteria2://${encode(credential)}@$address$q#${encode(name)}"
        }
        if (type == "anytls") {
            val query = linkedMapOf<String, String>()
            (proxy.optional("servername") ?: proxy.optional("sni"))?.let { query["sni"] = it }
            if (proxy.boolean("skip-cert-verify", false)) query["insecure"] = "1"
            proxy["alpn"]?.let { value ->
                require(value is List<*> && value.all { it is String })
                query["alpn"] = value.joinToString(",")
            }
            proxy.optional("client-fingerprint")?.let { query["fp"] = it }
            val q = if (query.isEmpty()) "" else
                "?" + query.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            return "anytls://${encode(credential)}@$address$q#${encode(name)}"
        }

        // mihomo renamed tcp to raw; the app's transport still calls it tcp.
        val network = (proxy.optional("network") ?: "tcp").lowercase()
            .let { if (it == "raw") "tcp" else it }
        require(network in setOf("tcp", "ws", "grpc", "h2", "http", "xhttp"))
        val tls = proxy.boolean("tls", type == "trojan")
        require(type != "trojan" || tls)
        val reality = proxy["reality-opts"] as? Map<*, *>
        require(proxy["reality-opts"] == null || (type == "vless" && reality != null && tls))
        val query = linkedMapOf<String, String>()
        query["security"] = if (reality != null) "reality" else if (tls) "tls" else "none"
        query["allowInsecure"] = if (proxy.boolean("skip-cert-verify", false)) "1" else "0"
        (proxy.optional("servername") ?: proxy.optional("sni"))?.let { query["sni"] = it }
        proxy.optional("client-fingerprint")?.let { query["fp"] = it }
        proxy["alpn"]?.let { value ->
            require(value is List<*> && value.all { it is String })
            query["alpn"] = value.joinToString(",")
        }
        reality?.let {
            require(it.keys.all { key -> key in setOf("public-key", "short-id", "spider-x") })
            query["pbk"] = it.string("public-key")
            it.optional("short-id")?.let { id -> query["sid"] = id }
            it.optional("spider-x")?.let { x -> query["spx"] = x }
        }
        require(proxy["ws-opts"] == null || network == "ws")
        require(proxy["grpc-opts"] == null || network == "grpc")
        require(proxy["h2-opts"] == null || network in setOf("h2", "http"))
        require(proxy["xhttp-opts"] == null || network == "xhttp")
        proxy["ws-opts"]?.let { value ->
            val ws = value as? Map<*, *> ?: throw IllegalArgumentException()
            require(
                ws.keys.all {
                    it in setOf(
                        "path", "headers", "max-early-data", "early-data-header-name",
                        "v2ray-http-upgrade", "v2ray-http-upgrade-fast-open",
                    )
                }
            )
            ws.optional("path")?.let { query["path"] = it }
            ws["headers"]?.let { headers ->
                require(headers is Map<*, *>)
                // Only the Host header affects the dial; other headers are
                // decorations that Xray's WS transport in this app cannot carry.
                headers.entries
                    .firstOrNull { it.key.toString().equals("Host", ignoreCase = true) }
                    ?.value
                    ?.let { require(it is String); query["host"] = it }
            }
        }
        proxy["grpc-opts"]?.let { value ->
            val grpc = value as? Map<*, *> ?: throw IllegalArgumentException()
            require(grpc.keys.all { it == "grpc-service-name" })
            grpc.optional("grpc-service-name")?.let { query["serviceName"] = it }
        }
        proxy["h2-opts"]?.let { value ->
            val h2 = value as? Map<*, *> ?: throw IllegalArgumentException()
            require(h2.keys.all { it in setOf("host", "path") })
            when (val hosts = h2["host"]) {
                is List<*> -> hosts.firstOrNull { it is String }?.let { query["host"] = it as String }
                is String -> query["host"] = hosts
                null -> {}
                else -> throw IllegalArgumentException()
            }
            h2.optional("path")?.let { query["path"] = it }
        }
        proxy["xhttp-opts"]?.let { value ->
            val xhttp = value as? Map<*, *> ?: throw IllegalArgumentException()
            require(xhttp.keys.all { it in setOf("host", "path", "mode", "extra") })
            when (val hosts = xhttp["host"]) {
                is List<*> -> hosts.firstOrNull { it is String }?.let { query["host"] = it as String }
                is String -> query["host"] = hosts
                null -> {}
                else -> throw IllegalArgumentException()
            }
            xhttp.optional("path")?.let { query["path"] = it }
            xhttp.optional("mode")?.let { query["mode"] = it }
            xhttp.optional("extra")?.let { query["extra"] = it }
        }
        query["type"] = when (network) {
            "ws" -> if (proxy["ws-opts"].let { (it as? Map<*, *>)?.get("v2ray-http-upgrade") == true }) {
                "httpupgrade"
            } else {
                "ws"
            }
            "h2", "http" -> "http"
            else -> network
        }
        if (type == "vmess") {
            require((proxy.optional("alterId") ?: "0").toIntOrNull() == 0)
            val json = linkedMapOf(
                "v" to "2", "ps" to name, "add" to host, "port" to port.toString(),
                "id" to credential, "aid" to "0", "scy" to (proxy.optional("cipher") ?: "auto"),
                "net" to query.getValue("type"), "type" to "none",
                "tls" to if (tls) "tls" else "",
                "host" to query["host"].orEmpty(),
                "path" to (if (query["type"] == "grpc") query["serviceName"] else query["path"]).orEmpty(),
                "sni" to query["sni"].orEmpty(), "fp" to query["fp"].orEmpty(),
                "alpn" to query["alpn"].orEmpty(), "insecure" to query.getValue("allowInsecure")
            )
            return "vmess://${base64(Gson().toJson(json))}"
        }
        if (type == "vless") {
            require(proxy.optional("encryption") in listOf(null, "none"))
            query["encryption"] = "none"
            proxy.optional("flow")?.let { query["flow"] = it }
        }
        return "$type://${encode(credential)}@$address?${query.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }}#${encode(name)}"
    }

    private fun shadowsocksPlugin(proxy: Map<*, *>): String? {
        val plugin = proxy.optional("plugin") ?: run {
            require(proxy["plugin-opts"] == null) { "Shadowsocks plugin-opts requires plugin" }
            return null
        }
        val options = proxy["plugin-opts"]?.let {
            it as? Map<*, *> ?: throw IllegalArgumentException("Invalid plugin-opts")
        } ?: emptyMap<Any, Any>()
        if (plugin.equals("obfs", ignoreCase = true)) {
            require(options.keys.all { it in setOf("mode", "host") })
            val mode = options.optional("mode") ?: "http"
            require(mode == "http") { "Unsupported Shadowsocks obfs mode" }
            val parts = mutableListOf("obfs-local", "obfs=http")
            options.optional("host")?.let { parts += "obfs-host=$it" }
            return parts.joinToString(";")
        }
        require(plugin.equals("v2ray-plugin", ignoreCase = true)) {
            "Unsupported Shadowsocks plugin"
        }
        require(options.keys.all { it in setOf("mode", "host", "path", "tls") })

        val parts = mutableListOf("v2ray-plugin")
        val mode = options.optional("mode") ?: "websocket"
        require(mode == "websocket")
        parts += "mode=$mode"
        options.optional("host")?.let { parts += "host=$it" }
        options.optional("path")?.let { parts += "path=$it" }
        if (options.boolean("tls", false)) parts += "tls"
        proxy.optional("client-fingerprint")?.let { parts += "fp=$it" }
        return parts.joinToString(";")
    }

    /** mihomo writes bandwidth as a number or "100 Mbps"; the app stores the number. */
    private fun bandwidth(value: String): String? = Regex("\\d+").find(value)?.value

    private fun Map<*, *>.optional(key: String): String? = get(key)?.let {
        require(it is String || it is Number)
        it.toString().takeIf { value -> value.isNotBlank() }
    }

    private fun Map<*, *>.string(key: String): String = optional(key)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing required field")

    private fun Map<*, *>.boolean(key: String, default: Boolean): Boolean = get(key)?.let {
        require(it is Boolean)
        it
    } ?: default

    private fun normalize(text: String): String = text.removePrefix("\uFEFF")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun base64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
