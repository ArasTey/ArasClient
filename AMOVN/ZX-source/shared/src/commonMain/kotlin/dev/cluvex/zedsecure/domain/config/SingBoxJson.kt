package dev.cluvex.zedsecure.domain.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

object SingBoxJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prettyJson = Json { prettyPrint = true }

    enum class Shape {
        None,

        Outbound,

        OutboundList,

        Fragment,

        FullConfig,
    }

    val PROXY_OUTBOUND_TYPES = setOf(
        "socks", "http", "shadowsocks", "vmess", "trojan", "naive", "hysteria", "shadowtls", "vless",
        "tuic", "hysteria2", "anytls", "tor", "ssh", "snell", "shadowsocksr",
    )

    const val OPENVPN_TYPE = "openvpn-client"

    val ENDPOINT_TYPES = setOf(
        "wireguard", "tailscale", OPENVPN_TYPE, "openconnect",

        "openvpn",
    )

    val NON_SERVER_OUTBOUND_TYPES = setOf("direct", "block", "dns", "selector", "urltest", "bridge")

    val UDP_TYPES = setOf("tuic", "hysteria", "hysteria2", "wireguard", "tailscale", OPENVPN_TYPE, "openvpn")

    private val KNOWN_TYPES = PROXY_OUTBOUND_TYPES + ENDPOINT_TYPES + NON_SERVER_OUTBOUND_TYPES

    private val FULL_CONFIG_KEYS = setOf("inbounds", "route", "dns", "experimental", "services", "ntp", "certificate")

    fun shapeOf(text: String): Shape {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return Shape.None
        val root = parse(trimmed) ?: return Shape.None
        return when (root) {
            is JsonArray -> {
                val objects = root.mapNotNull { it as? JsonObject }
                if (objects.isNotEmpty() && objects.all { isSingBoxOutbound(it) }) Shape.OutboundList else Shape.None
            }
            is JsonObject -> when {
                isSingBoxOutbound(root) && root["server"] != null || root.type() in ENDPOINT_TYPES && root["peers"] != null ->
                    Shape.Outbound
                !isSingBoxConfig(root) -> Shape.None
                FULL_CONFIG_KEYS.any { root.containsKey(it) } -> Shape.FullConfig
                else -> Shape.Fragment
            }
            else -> Shape.None
        }
    }

    fun isSingBox(text: String): Boolean = shapeOf(text) != Shape.None

    private fun isSingBoxConfig(root: JsonObject): Boolean {
        val entries = listOfNotNull(root["outbounds"] as? JsonArray, root["endpoints"] as? JsonArray)
            .flatten()
            .mapNotNull { it as? JsonObject }
        if (entries.isEmpty()) {
            val inbounds = (root["inbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            return inbounds.isNotEmpty() && inbounds.all { it.containsKey("type") && !it.containsKey("protocol") }
        }
        return entries.all { isSingBoxOutbound(it) }
    }

    private fun isSingBoxOutbound(o: JsonObject): Boolean {
        if (o.containsKey("protocol")) return false
        val type = o.type() ?: return false
        return type in KNOWN_TYPES
    }

    fun retypeOpenVpn(text: String): String? {
        val root = parse(text) as? JsonObject ?: return null
        val endpoints = root["endpoints"] as? JsonArray ?: return null
        var changed = false
        val fixed = buildJsonArray {
            endpoints.forEach { element ->
                val endpoint = element as? JsonObject
                if (endpoint != null && endpoint.type() == "openvpn") {
                    changed = true
                    add(JsonObject(endpoint + ("type" to JsonPrimitive(OPENVPN_TYPE))))
                } else {
                    add(element)
                }
            }
        }
        if (!changed) return null
        return encode(JsonObject(root + ("endpoints" to fixed)))
    }

    data class Server(

        val tag: String,
        val type: String,
        val address: String?,
        val port: Int?,

        val fragment: String,
    ) {
        val label: String get() = typeLabel(type)
        val udp: Boolean get() = type in UDP_TYPES
    }

    fun servers(text: String): List<Server> {
        val root = parse(text.trim()) ?: return emptyList()
        val (outbounds, endpoints) = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }.partition { it.type() !in ENDPOINT_TYPES }
            is JsonObject -> if (root.containsKey("outbounds") || root.containsKey("endpoints")) {
                ((root["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()) to
                    ((root["endpoints"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty())
            } else if (root.type() in ENDPOINT_TYPES) {
                emptyList<JsonObject>() to listOf(root)
            } else {
                listOf(root) to emptyList()
            }
            else -> return emptyList()
        }
        val byTag = (outbounds + endpoints).mapNotNull { o -> o.tag()?.let { it to o } }.toMap()

        val servers = mutableListOf<Server>()
        val entries = outbounds.map { it to false } + endpoints.map { it to true }
        entries.forEachIndexed { index, (entry, isEndpoint) ->
            val type = entry.type() ?: return@forEachIndexed
            if (!isEndpoint && type !in PROXY_OUTBOUND_TYPES) return@forEachIndexed

            val tag = entry.tag() ?: "${type}-$index"
            if (outbounds.any { it !== entry && it.detour() == entry.tag() && entry.tag() != null }) return@forEachIndexed
            val chain = dependencyChain(entry, byTag) ?: return@forEachIndexed
            val chainOutbounds = chain.filter { it.type() !in ENDPOINT_TYPES }
            val chainEndpoints = chain.filter { it.type() in ENDPOINT_TYPES }
            val fragment = buildJsonObject {
                if (chainOutbounds.isNotEmpty()) put("outbounds", buildJsonArray {
                    chainOutbounds.forEach { add(if (it === entry) it.withTag(tag) else it) }
                })
                if (chainEndpoints.isNotEmpty()) put("endpoints", buildJsonArray {
                    chainEndpoints.forEach { add(if (it === entry) it.withTag(tag) else it) }
                })
            }

            val (address, port) = chain.asSequence().map { serverAddress(it) }.firstOrNull { it.first != null }
                ?: (null to null)
            servers += Server(
                tag = tag,
                type = type,
                address = address,
                port = port,
                fragment = prettyJson.encodeToString(JsonObject.serializer(), fragment),
            )
        }
        return servers
    }

    private fun dependencyChain(entry: JsonObject, byTag: Map<String, JsonObject>): List<JsonObject>? {
        val chain = mutableListOf(entry)
        var cursor = entry
        while (true) {
            val detour = cursor.detour() ?: break
            val next = byTag[detour] ?: return null
            if (chain.any { it === next }) break
            chain += next
            cursor = next
        }
        return chain
    }

    fun serverAddress(entry: JsonObject): Pair<String?, Int?> {
        val server = entry.string("server")
        if (server != null) return server to entry.int("server_port")
        val peer = (entry["peers"] as? JsonArray)?.firstOrNull() as? JsonObject
        return peer?.string("address") to peer?.int("port")
    }

    fun probeServer(configJson: String): Server? {
        val root = parse(configJson) as? JsonObject ?: return null
        val servers = servers(configJson)
        if (servers.isEmpty()) return null
        val outbounds = (root["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val byTag = outbounds.mapNotNull { o -> o.tag()?.let { it to o } }.toMap()
        var target = (root["route"] as? JsonObject)?.string("final") ?: outbounds.firstOrNull()?.tag()
        val seen = mutableSetOf<String>()
        while (target != null && seen.add(target)) {
            servers.firstOrNull { it.tag == target }?.let { return it }
            val group = byTag[target] ?: break
            target = when (group.type()) {
                "selector" -> group.string("default") ?: group.stringList("outbounds").firstOrNull()
                "urltest" -> group.stringList("outbounds").firstOrNull()
                else -> null
            }
        }
        return servers.first()
    }

    fun typeLabel(type: String): String = when (type.lowercase()) {
        "shadowsocks" -> "Shadowsocks"
        "shadowsocksr" -> "ShadowsocksR"
        "vmess" -> "VMess"
        "vless" -> "VLESS"
        "trojan" -> "Trojan"
        "socks" -> "SOCKS"
        "http" -> "HTTP"
        "naive" -> "Naive"
        "hysteria" -> "Hysteria"
        "hysteria2" -> "Hysteria2"
        "shadowtls" -> "ShadowTLS"
        "tuic" -> "TUIC"
        "anytls" -> "AnyTLS"
        "tor" -> "Tor"
        "ssh" -> "SSH"
        "snell" -> "Snell"
        "wireguard" -> "WireGuard"
        "tailscale" -> "Tailscale"
        "openvpn", OPENVPN_TYPE -> "OpenVPN"
        "openconnect" -> "OpenConnect"
        else -> type.uppercase()
    }

    internal fun parse(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(Jsonc.strip(text)) }.getOrNull()

    internal fun encode(obj: JsonObject): String = prettyJson.encodeToString(JsonObject.serializer(), obj)

    private fun JsonObject.type(): String? = string("type")?.lowercase()
    private fun JsonObject.tag(): String? = string("tag")?.takeIf { it.isNotBlank() }
    private fun JsonObject.detour(): String? = string("detour")?.takeIf { it.isNotBlank() }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun JsonObject.withTag(tag: String): JsonObject =
        if (string("tag") == tag) this else JsonObject(this + ("tag" to JsonPrimitive(tag)))
}
