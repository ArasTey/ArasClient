package dev.cluvex.zedsecure.domain.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object SingBoxConfigs {
    const val TUN_TAG = "zed-tun"

    const val LOCAL_TAG = "zed-local"

    data class Prepared(
        val json: String,

        val socksPort: Int,

        val addedTun: Boolean,
    )

    fun prepareForDevice(
        json: String,
        socksPort: Int = LocalProxy.SOCKS_PORT,
        ipv6: Boolean = true,

        device: DeviceOptions = DeviceOptions(),
    ): Prepared {
        val root = SingBoxJson.parse(json) as? JsonObject
            ?: throw IllegalArgumentException("the sing-box config is not a JSON object")
        val inbounds = (root["inbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        val hasTun = inbounds.any { it.string("type") == "tun" }
        val localPort = inbounds.firstNotNullOfOrNull { inbound ->
            val type = inbound.string("type")
            val listen = inbound.string("listen")
            if ((type == "mixed" || type == "socks") && listen != null && isLoopback(listen)) {
                inbound.int("listen_port")
            } else null
        }

        val newInbounds = buildJsonArray {
            if (!hasTun) add(tunInbound(ipv6, device))

            inbounds.forEach { add(if (it.string("type") == "tun") withDeviceOptions(it, device) else it) }
            if (localPort == null) add(localInbound(socksPort))
        }

        val route = root["route"] as? JsonObject
        val newRoute = if (hasTun) route else routeForAddedTun(route)

        val prepared = JsonObject(buildMap {
            putAll(root)
            put("inbounds", newInbounds)
            if (newRoute != null) put("route", newRoute)
            experimentalWithCache(root, device)?.let { put("experimental", it) }
            ntp(root, device)?.let { put("ntp", it) }
        })
        return Prepared(SingBoxJson.encode(prepared), localPort ?: socksPort, addedTun = !hasTun)
    }

    data class PreparedDesktop(
        val json: String,

        val socksPort: Int,

        val clashApi: String,

        val clashSecret: String?,
    )

    fun prepareForDesktop(json: String, socksPort: Int, clashApiPort: Int): PreparedDesktop {
        val root = SingBoxJson.parse(json) as? JsonObject
            ?: throw IllegalArgumentException("the sing-box config is not a JSON object")
        val inbounds = (root["inbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            .filter { it.string("type") != "tun" }
        val localPort = inbounds.firstNotNullOfOrNull { inbound ->
            val type = inbound.string("type")
            val listen = inbound.string("listen")
            if ((type == "mixed" || type == "socks") && listen != null && isLoopback(listen)) inbound.int("listen_port") else null
        }
        val newInbounds = buildJsonArray {
            inbounds.forEach { add(it) }
            if (localPort == null) add(localInbound(socksPort))
        }

        val experimental = root["experimental"] as? JsonObject
        val clash = experimental?.get("clash_api") as? JsonObject
        val existingController = clash?.string("external_controller")?.takeIf { it.isNotBlank() }
        val controller = existingController ?: "127.0.0.1:$clashApiPort"
        val newExperimental = if (existingController != null) experimental else JsonObject(buildMap<String, JsonElement> {
            experimental?.let { putAll(it) }
            put("clash_api", JsonObject(buildMap {
                clash?.let { putAll(it) }
                put("external_controller", JsonPrimitive(controller))
            }))
        })

        val route = root["route"] as? JsonObject
        val rules = (route?.get("rules") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val newRules = buildJsonArray {
            if (rules.none { it.string("action") == "sniff" && it["inbound"] == null }) {
                add(buildJsonObject { put("action", "sniff") })
            }
            if (rules.none { it.string("action") == "hijack-dns" }) add(buildJsonObject {
                put("protocol", "dns")
                put("action", "hijack-dns")
            })
            rules.forEach { add(it) }
        }
        val newRoute = JsonObject(buildMap<String, JsonElement> {
            route?.let { putAll(it) }
            put("rules", newRules)
        })

        val prepared = JsonObject(buildMap {
            putAll(root)
            put("inbounds", newInbounds)
            put("route", newRoute)
            put("experimental", newExperimental)
        })
        return PreparedDesktop(
            json = SingBoxJson.encode(prepared),
            socksPort = localPort ?: socksPort,
            clashApi = controller.replace("0.0.0.0", "127.0.0.1"),
            clashSecret = clash?.string("secret")?.takeIf { it.isNotBlank() },
        )
    }

    data class DeviceOptions(

        val stack: String = "",
        val strictRoute: Boolean = false,
        val endpointIndependentNat: Boolean = false,

        val mtu: Int = 0,

        val storeCache: Boolean = false,

        val ntpServer: String = "",
    )

    private fun tunInbound(ipv6: Boolean, device: DeviceOptions) = buildJsonObject {
        put("type", "tun")
        put("tag", TUN_TAG)
        putJsonArray("address") {
            add(JsonPrimitive("172.19.0.1/30"))
            if (ipv6) add(JsonPrimitive("fdfe:dcba:9876::1/126"))
        }
        put("auto_route", true)

        put("stack", device.stack.ifBlank { "mixed" })
        if (device.mtu > 0) put("mtu", device.mtu)
        if (device.strictRoute) put("strict_route", true)
        if (device.endpointIndependentNat) put("endpoint_independent_nat", true)
    }

    private fun withDeviceOptions(tun: JsonObject, device: DeviceOptions): JsonObject {
        val changes = buildMap<String, JsonElement> {
            if (device.stack.isNotBlank()) put("stack", JsonPrimitive(device.stack))
            if (device.strictRoute) put("strict_route", JsonPrimitive(true))
            if (device.endpointIndependentNat) put("endpoint_independent_nat", JsonPrimitive(true))
        }
        return if (changes.isEmpty()) tun else JsonObject(tun + changes)
    }

    private fun experimentalWithCache(root: JsonObject, device: DeviceOptions): JsonElement? {
        if (!device.storeCache) return null
        val experimental = root["experimental"] as? JsonObject
        if ((experimental?.get("cache_file") as? JsonObject) != null) return null
        return JsonObject(
            buildMap<String, JsonElement> {
                experimental?.let { putAll(it) }
                put("cache_file", buildJsonObject { put("enabled", true) })
            },
        )
    }

    private fun ntp(root: JsonObject, device: DeviceOptions): JsonElement? {
        if (device.ntpServer.isBlank() || root["ntp"] != null) return null
        return buildJsonObject {
            put("enabled", true)
            put("server", device.ntpServer)
            put("interval", "30m")
        }
    }

    private fun localInbound(port: Int) = buildJsonObject {
        put("type", "mixed")
        put("tag", LOCAL_TAG)
        put("listen", "127.0.0.1")
        put("listen_port", port)
    }

    private fun routeForAddedTun(route: JsonObject?): JsonObject {
        val rules = (route?.get("rules") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val sniffs = rules.any { it.string("action") == "sniff" }
        val hijacks = rules.any { it.string("action") == "hijack-dns" }
        val newRules = buildJsonArray {
            if (!sniffs) add(buildJsonObject {
                put("inbound", TUN_TAG)
                put("action", "sniff")
            })
            if (!hijacks) add(buildJsonObject {
                put("protocol", "dns")
                put("action", "hijack-dns")
            })
            rules.forEach { add(it) }
        }
        return JsonObject(buildMap<String, JsonElement> {
            route?.let { putAll(it) }
            put("rules", newRules)
        })
    }

    private fun isLoopback(listen: String): Boolean =
        listen == "127.0.0.1" || listen == "::1" || listen == "localhost" || listen.startsWith("127.")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
}
