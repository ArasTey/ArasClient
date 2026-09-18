package com.aras.client.fmt

import com.google.gson.Gson
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URLEncoder
import java.util.Base64

/** Imports proxy definitions only; Clash rules, groups and providers are not executed. */
object ClashYamlFmt {
    private const val MAX_CHARACTERS = 2_000_000
    private val proxyKey = Regex("(?m)^\\s*(?:[\"']?proxies[\"']?)\\s*:")

    fun isClashYaml(text: String): Boolean = proxyKey.containsMatchIn(text) ||
        Regex("^\\s*\\{\\s*[\"']?proxies[\"']?\\s*:").containsMatchIn(text)

    fun toLinks(text: String): List<String> {
        require(text.length <= MAX_CHARACTERS) { "Clash YAML is too large" }
        val options = LoaderOptions().apply {
            codePointLimit = MAX_CHARACTERS
            nestingDepthLimit = 30
            maxAliasesForCollections = 20
            isAllowDuplicateKeys = false
            setAllowRecursiveKeys(false)
        }
        val root = Yaml(SafeConstructor(options)).load<Any>(text) as? Map<*, *>
            ?: throw IllegalArgumentException("Expected a Clash YAML mapping")
        val proxies = root["proxies"] as? List<*>
            ?: throw IllegalArgumentException("Clash YAML must contain a proxies list")
        require(proxies.isNotEmpty() && proxies.size <= 10_000) { "Invalid Clash proxy count" }
        // Validate the whole list before the caller changes subscription storage.
        return proxies.mapIndexed { index, value ->
            try {
                toLink(value as? Map<*, *> ?: error("Expected proxy mapping"))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Unsupported or invalid Clash proxy at index ${index + 1}")
            }
        }
    }

    private fun toLink(proxy: Map<*, *>): String {
        val commonKeys = setOf("name", "type", "server", "port", "udp", "tfo", "ip-version")
        val type = proxy.string("type")
        val typeKeys = when (type) {
            "ss" -> setOf("cipher", "password")
            "vmess" -> setOf("uuid", "alterId", "cipher")
            "vless" -> setOf("uuid", "flow", "encryption")
            "trojan" -> setOf("password")
            else -> throw IllegalArgumentException("Unsupported proxy type")
        }
        val transportKeys = setOf("tls", "servername", "sni", "skip-cert-verify", "client-fingerprint", "alpn", "network", "ws-opts", "grpc-opts", "reality-opts")
        require(proxy.keys.all { it in commonKeys + typeKeys + if (type == "ss") emptySet() else transportKeys })
        require(proxy["ip-version"] == null || proxy["ip-version"] == "dual")
        val host = proxy.string("server").removeSurrounding("[", "]")
        require(host.none { it.isWhitespace() || it in "/?#@" })
        val port = proxy.string("port").toIntOrNull()
        require(port != null && port in 1..65535)
        val address = if (':' in host) "[$host]:$port" else "$host:$port"
        val name = proxy.optional("name") ?: host
        val credential = proxy.string(if (type in setOf("vmess", "vless")) "uuid" else "password")
        if (type == "ss") {
            val cipher = proxy.string("cipher")
            return "ss://${base64("$cipher:$credential")}@$address#${encode(name)}"
        }
        val network = proxy.optional("network") ?: "tcp"
        require(network in setOf("tcp", "ws", "grpc"))
        val tls = proxy.boolean("tls", type == "trojan")
        require(type != "trojan" || tls)
        val reality = proxy["reality-opts"] as? Map<*, *>
        require(proxy["reality-opts"] == null || (type == "vless" && reality != null && tls))
        val query = linkedMapOf("type" to network, "security" to if (reality != null) "reality" else if (tls) "tls" else "none")
        query["allowInsecure"] = if (proxy.boolean("skip-cert-verify", false)) "1" else "0"
        (proxy.optional("servername") ?: proxy.optional("sni"))?.let { query["sni"] = it }
        proxy.optional("client-fingerprint")?.let { query["fp"] = it }
        proxy["alpn"]?.let { value ->
            require(value is List<*> && value.all { it is String })
            query["alpn"] = value.joinToString(",")
        }
        reality?.let {
            require(it.keys.all { key -> key in setOf("public-key", "short-id") })
            query["pbk"] = it.string("public-key")
            it.optional("short-id")?.let { id -> query["sid"] = id }
        }
        require(proxy["ws-opts"] == null || network == "ws")
        require(proxy["grpc-opts"] == null || network == "grpc")
        proxy["ws-opts"]?.let { value ->
            val ws = value as? Map<*, *> ?: throw IllegalArgumentException()
            require(ws.keys.all { it in setOf("path", "headers") })
            ws.optional("path")?.let { query["path"] = it }
            ws["headers"]?.let { headers ->
                require(headers is Map<*, *> && headers.keys.all { it.toString().equals("Host", true) })
                headers.values.firstOrNull()?.let { require(it is String); query["host"] = it }
            }
        }
        proxy["grpc-opts"]?.let { value ->
            val grpc = value as? Map<*, *> ?: throw IllegalArgumentException()
            require(grpc.keys.all { it == "grpc-service-name" })
            grpc.optional("grpc-service-name")?.let { query["serviceName"] = it }
        }
        if (type == "vmess") {
            require((proxy.optional("alterId") ?: "0").toIntOrNull() == 0)
            val json = linkedMapOf(
                "v" to "2", "ps" to name, "add" to host, "port" to port.toString(),
                "id" to credential, "aid" to "0", "scy" to (proxy.optional("cipher") ?: "auto"),
                "net" to network, "type" to "none", "tls" to if (tls) "tls" else "",
                "host" to query["host"].orEmpty(),
                "path" to (if (network == "grpc") query["serviceName"] else query["path"]).orEmpty(),
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

    private fun Map<*, *>.optional(key: String): String? = get(key)?.let {
        require(it is String || it is Number)
        it.toString()
    }

    private fun Map<*, *>.string(key: String): String = optional(key)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing required field")

    private fun Map<*, *>.boolean(key: String, default: Boolean): Boolean = get(key)?.let {
        require(it is Boolean)
        it
    } ?: default

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun base64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
