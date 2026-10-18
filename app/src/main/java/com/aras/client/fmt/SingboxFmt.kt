package com.aras.client.fmt

import com.aras.client.util.Utils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * sing-box subscription/config JSON → share links.
 *
 * A sing-box document is an object with an "outbounds" array; dials carry
 * "server"/"server_port" and a "type" of vless | vmess | trojan | shadowsocks |
 * hysteria2. Group/meta outbounds (selector, urltest, direct, block, dns) are
 * skipped, and an outbound whose detour links it to another hop is not its own
 * server — its fields may be empty.
 *
 * The converter emits the same share links the app's own parsers read back, so
 * nothing new has to survive on the way in.
 */
object SingboxFmt {

    private const val MAX_CHARACTERS = 2_000_000
    private const val MAX_OUTBOUNDS = 10_000

    fun isSingboxJson(text: String): Boolean {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return false
        return runCatching {
            val root = JsonParser.parseString(trimmed)
            root.isJsonObject &&
                root.asJsonObject.get("outbounds")?.isJsonArray == true &&
                // A sing-box document never has a clash-style proxies list.
                root.asJsonObject.get("proxies") == null
        }.getOrDefault(false)
    }

    private val nonDialTypes = setOf("selector", "urltest", "direct", "block", "dns", "ssh", "tor")

    fun toLinks(text: String): List<String> {
        require(text.length <= MAX_CHARACTERS) { "sing-box JSON is too large" }
        val root = runCatching { JsonParser.parseString(text) }
            .getOrElse { throw IllegalArgumentException("Expected a sing-box JSON object") }
            .takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("Expected a sing-box JSON object")
        val dials = root.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonObject && it.asJsonObject.string("type") !in nonDialTypes }
            .orEmpty()
        require(dials.isNotEmpty() && dials.size <= MAX_OUTBOUNDS) { "Invalid sing-box outbound count" }
        // Validate the whole list before the caller changes subscription storage.
        return dials.mapIndexed { index, element ->
            try {
                toLink(element.asJsonObject)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Unsupported or invalid sing-box outbound at index ${index + 1}")
            }
        }
    }

    private fun out(outbound: JsonObject): String? = outbound.string("server")

    private fun alpnOf(parent: JsonObject): String? =
        parent.get("alpn")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
            ?.joinToString(",")?.takeIf { it.isNotEmpty() }
            ?: parent.string("alpn")

    private fun toLink(outbound: JsonObject): String {
        // Not a dial: a group (selector/urltest), transport (direct/block/dns), or a
        // detour-only placeholder, none of which names a server.
        require(!outbound.string("type").isNullOrEmpty()) { "Expected an outbound type" }
        val server = out(outbound) ?: throw IllegalArgumentException("Outbound names no server")
        val port = outbound.intOrNull("server_port")
        require(port != null && port in 1..65535) { "Outbound names a bad port" }
        val tag = outbound.string("tag").orEmpty().ifBlank { server }
        val tls = outbound.obj("tls") ?: JsonObject()
        val tlsEnabled = tls.bool("enabled") ?: false
        val transport = outbound.obj("transport") ?: JsonObject()
        val network = transport.string("type") ?: "tcp"
        val address = if (':' in server) "[$server]:$port" else "$server:$port"
        val name = Utils.encodeURIComponent(tag)

        return when (outbound.string("type")) {
            "vless" -> {
                val flow = outbound.string("flow").orEmpty()
                val query = linkedMapOf<String, String>()
                if (tlsEnabled) {
                    val reality = tls.obj("reality")
                    query["security"] = if (reality?.bool("enabled") == true) "reality" else "tls"
                    if (reality != null) {
                        reality.string("public_key")?.let { query["pbk"] = it }
                        reality.string("short_id")?.let { query["sid"] = it }
                    }
                    tls.obj("utls")?.string("fingerprint")?.let { query["fp"] = it }
                    tls.string("server_name")?.let { query["sni"] = it }
                    alpnOf(tls)?.let { query["alpn"] = it }
                    tls.string("cipher_suites")?.let { query["cs"] = it }
                }
                if (network == "ws") {
                    query["type"] = "ws"
                    transport.string("path")?.takeIf { it.isNotBlank() }?.let { query["path"] = it }
                    (transport.obj("headers")?.string("Host"))?.let { query["host"] = it }
                } else if (network == "grpc") {
                    query["type"] = "grpc"
                    transport.string("service_name")?.let { query["serviceName"] = it }
                }
                if (flow.isNotBlank()) query["flow"] = flow
                val q = "?" + query.entries.joinToString("&") { (k, v) -> "$k=${Utils.encodeURIComponent(v)}" }
                "vless://${outbound.string("uuid")}@$address$q#$name"
            }

            "vmess" -> {
                // Standard vmess QR code JSON; VmessFmt reads it back as-is.
                val qr = JsonObject().apply {
                    addProperty("v", "2")
                    addProperty("ps", tag)
                    addProperty("add", server)
                    addProperty("port", port)
                    addProperty("id", outbound.string("uuid").orEmpty())
                    addProperty("aid", outbound.intOrNull("alter_id") ?: 0)
                    addProperty("net", if (network == "grpc") "grpc" else network)
                    addProperty("scy", outbound.string("method").orEmpty())
                    if (tlsEnabled) {
                        addProperty("tls", "tls")
                        tls.string("server_name")?.let { addProperty("sni", it) }
                        alpnOf(tls)?.let { addProperty("alpn", it) }
                        tls.string("fingerprint")?.let { addProperty("fp", it) }
                    }
                    transport.string("path")?.let { addProperty("path", it) }
                    transport.obj("headers")?.string("Host")?.let { addProperty("host", it) }
                    transport.string("service_name")?.let { addProperty("path", it) }
                }
                "vmess://" + base64Of(qr.toString())
            }

            "trojan" -> {
                val query = linkedMapOf<String, String>()
                if (tlsEnabled) tls.string("server_name")?.let { query["sni"] = it }
                if (network == "grpc") {
                    query["type"] = "grpc"
                    transport.string("service_name")?.let { query["serviceName"] = it }
                } else if (network == "ws") {
                    query["type"] = "ws"
                    transport.string("path")?.let { query["path"] = it }
                }
                val q = if (query.isEmpty()) "" else
                    "?" + query.entries.joinToString("&") { (k, v) -> "$k=${Utils.encodeURIComponent(v)}" }
                "trojan://${outbound.string("password")}@$address$q#$name"
            }

            "shadowsocks" -> {
                val cipher = outbound.string("method")
                    ?: throw IllegalArgumentException("shadowsocks outbound names no method")
                val password = outbound.string("password")
                    ?: throw IllegalArgumentException("shadowsocks outbound names no password")
                "ss://${base64Of("$cipher:$password")}@$address#$name"
            }

            "hysteria2" -> {
                val query = linkedMapOf<String, String>()
                if (tlsEnabled) {
                    tls.string("server_name")?.let { query["sni"] = it }
                    if (tls.bool("insecure") == true) query["insecure"] = "1"
                }
                val q = if (query.isEmpty()) "" else
                    "?" + query.entries.joinToString("&") { (k, v) -> "$k=${Utils.encodeURIComponent(v)}" }
                "hysteria2://${outbound.string("password")}@$address$q#$name"
            }

            else -> throw IllegalArgumentException("Unsupported sing-box outbound type")
        }
    }

    private fun base64Of(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asBoolean

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.tryAsInt()

    private fun com.google.gson.JsonPrimitive.tryAsInt(): Int? =
        runCatching { asInt }.getOrNull()

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
