package com.aras.client.repair

import android.net.Uri
import java.net.InetAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Client-side config repair shop.
 * Inspired by offline optimizers (cf-optimizor / v2ray-config-modifier).
 * Does NOT invent UUID / pbk / sid.
 */
object ConfigRepairEngine {

    private val CF_IPS = listOf(
        "104.16.132.229",
        "104.17.80.12",
        "104.18.2.2",
        "172.67.74.133",
        "188.114.97.3"
    )

    fun repair(rawInput: String): ConfigRepairResult {
        val line = rawInput.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("://") }
            ?: rawInput.trim()

        require(line.contains("://")) { "No proxy URI found" }

        val schemeRaw = line.substringBefore("://").lowercase(Locale.ROOT)
        val scheme = when (schemeRaw) {
            "hy2" -> "hysteria2"
            else -> schemeRaw
        }

        return when (scheme) {
            "vless", "trojan", "ss", "hysteria2", "tuic" -> repairShareUri(line, scheme)
            "vmess" -> repairVmess(line)
            else -> throw IllegalArgumentException("Unsupported protocol: $scheme")
        }
    }

    private fun repairShareUri(line: String, scheme: String): ConfigRepairResult {
        val uri = Uri.parse(line)
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing host")
        val port = if (uri.port > 0) uri.port else 443
        val userInfo = uri.userInfo.orEmpty()
        val fragment = uri.fragment.orEmpty()
        val params = linkedMapOf<String, String>()
        uri.queryParameterNames.forEach { key ->
            val v = uri.getQueryParameter(key) ?: return@forEach
            params[key] = v
        }

        val warnings = mutableListOf<String>()
        val baseChanges = mutableListOf<RepairChange>()
        normalizeParams(scheme, params, baseChanges, warnings)

        val variants = mutableListOf<RepairedConfig>()

        val quickUri = buildShareUri(scheme, userInfo, host, port, params, fragment.ifBlank { "Repaired" })
        variants += RepairedConfig("تعمیر سریع", quickUri, baseChanges.toList())

        if (scheme == "vless" || scheme == "trojan") {
            val fp = params["fp"]?.lowercase(Locale.ROOT)
            val altFp = when (fp) {
                "chrome", null, "" -> "firefox"
                else -> "chrome"
            }
            if (altFp != fp) {
                val p2 = LinkedHashMap(params)
                val before = p2["fp"].orEmpty()
                p2["fp"] = altFp
                val changes = baseChanges.toMutableList()
                changes += RepairChange("fp", before.ifBlank { "(empty)" }, altFp, "Fingerprint alternate")
                variants += RepairedConfig(
                    "واریانت fingerprint ($altFp)",
                    buildShareUri(scheme, userInfo, host, port, p2, (fragment.ifBlank { "Node" }) + "-fp"),
                    changes
                )
            }
        }

        val type = (params["type"] ?: params["network"] ?: "tcp").lowercase(Locale.ROOT)
        val security = (params["security"] ?: "").lowercase(Locale.ROOT)
        val looksCdn = type in setOf("ws", "http", "h2", "grpc", "xhttp") &&
            (security == "tls" || security == "reality" || params.containsKey("sni") || params.containsKey("host"))
        if (looksCdn && hostIsDomain(host)) {
            val cfIp = CF_IPS[kotlin.math.abs(host.hashCode()) % CF_IPS.size]
            val p3 = LinkedHashMap(params)
            if (p3["sni"].isNullOrBlank()) p3["sni"] = host
            if (type == "ws" || type == "http" || type == "xhttp") {
                if (p3["host"].isNullOrBlank()) p3["host"] = host
            }
            val changes = baseChanges.toMutableList()
            changes += RepairChange("address", host, cfIp, "CDN anycast overlay")
            variants += RepairedConfig(
                "واریانت CDN IP",
                buildShareUri(scheme, userInfo, cfIp, port, p3, (fragment.ifBlank { "Node" }) + "-cdn"),
                changes
            )
        }

        if (hostIsDomain(host)) {
            val resolved = tryResolve(host)
            if (!resolved.isNullOrBlank() && resolved != host) {
                val p4 = LinkedHashMap(params)
                if (p4["sni"].isNullOrBlank()) p4["sni"] = host
                if ((type == "ws" || type == "http" || type == "xhttp") && p4["host"].isNullOrBlank()) {
                    p4["host"] = host
                }
                val changes = baseChanges.toMutableList()
                changes += RepairChange("address", host, resolved, "Resolved IP (DNS bypass)")
                variants += RepairedConfig(
                    "واریانت IP مستقیم",
                    buildShareUri(scheme, userInfo, resolved, port, p4, (fragment.ifBlank { "Node" }) + "-ip"),
                    changes
                )
            } else {
                warnings += "DNS resolve skipped or failed; IP variant not created."
            }
        }

        if (baseChanges.isEmpty() && variants.size == 1) {
            warnings += "Link looked mostly valid; only light normalization applied."
        }
        warnings += "Cannot fix wrong UUID/password/pbk/sid or a dead server."

        return ConfigRepairResult(line, scheme, host, port, variants.distinctBy { it.uri }, warnings)
    }

    private fun normalizeParams(
        scheme: String,
        params: MutableMap<String, String>,
        changes: MutableList<RepairChange>,
        warnings: MutableList<String>
    ) {
        fun set(key: String, value: String, note: String) {
            val old = params[key]
            if (old != value) {
                changes += RepairChange(key, old?.ifBlank { "(empty)" } ?: "(missing)", value, note)
                params[key] = value
            }
        }

        if (scheme == "vless") {
            val enc = params["encryption"]
            if (enc.isNullOrBlank() || enc.equals("null", true)) {
                set("encryption", "none", "VLESS requires encryption=none")
            }
        }

        if (scheme == "vless" || scheme == "trojan") {
            val security = (params["security"] ?: "").lowercase(Locale.ROOT)
            if (security in setOf("tls", "reality", "xtls") || params.containsKey("sni") || params.containsKey("pbk")) {
                if (params["fp"].isNullOrBlank()) {
                    set("fp", "chrome", "Default browser fingerprint")
                }
            }
        }

        if (scheme == "vless") {
            val security = (params["security"] ?: "").lowercase(Locale.ROOT)
            val type = (params["type"] ?: "tcp").lowercase(Locale.ROOT)
            val flow = params["flow"].orEmpty()
            if (security == "reality" && type in setOf("tcp", "raw") && flow.isBlank()) {
                set("flow", "xtls-rprx-vision", "Common Reality+TCP flow (server must allow it)")
                warnings += "flow=xtls-rprx-vision added for Reality/TCP — remove if server rejects Vision."
            }
            if (flow.contains(" ") || flow.contains("%20")) {
                set("flow", flow.replace(" ", "").replace("%20", ""), "Strip invalid spaces in flow")
            }
        }

        if (params.containsKey("network") && !params.containsKey("type")) {
            val n = params.remove("network")!!
            set("type", n, "Normalize network→type")
        }

        val type = (params["type"] ?: "").lowercase(Locale.ROOT)
        if (type == "ws" && params["path"].isNullOrBlank()) {
            set("path", "/", "Default WS path")
        }

        if ((scheme == "vless" || scheme == "trojan") &&
            !params["sni"].isNullOrBlank() &&
            params["security"].isNullOrBlank() &&
            params["pbk"].isNullOrBlank()
        ) {
            set("security", "tls", "SNI present without security → tls")
        }

        params["alpn"]?.let { alpn ->
            val cleaned = alpn.replace(" ", "")
            if (cleaned != alpn) set("alpn", cleaned, "Normalize ALPN")
        }
    }

    private fun repairVmess(line: String): ConfigRepairResult =
        ConfigRepairResult(
            original = line,
            protocol = "vmess",
            host = "?",
            port = 0,
            variants = listOf(RepairedConfig("بدون تغییر (VMess)", line, emptyList())),
            warnings = listOf(
                "VMess repair is limited offline. Prefer VLESS/Trojan/Hysteria2 links.",
                "Cannot fix wrong UUID or a dead server."
            )
        )

    private fun buildShareUri(
        scheme: String,
        userInfo: String,
        host: String,
        port: Int,
        params: Map<String, String>,
        remark: String
    ): String {
        val auth = if (userInfo.isNotBlank()) "${encodeUser(userInfo)}@" else ""
        val query = params.entries
            .filter { it.value.isNotBlank() }
            .joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val frag = if (remark.isNotBlank()) "#${enc(remark)}" else ""
        val q = if (query.isNotBlank()) "?$query" else ""
        return "$scheme://$auth$host:$port$q$frag"
    }

    private fun encodeUser(userInfo: String): String =
        userInfo.split(":").joinToString(":") { enc(it) }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun hostIsDomain(host: String): Boolean {
        if (host.isBlank()) return false
        if (host.matches(Regex("""^\\d{1,3}(\\.\\d{1,3}){3}$"""))) return false
        if (host.contains(":")) return false
        return host.any { it.isLetter() }
    }

    private fun tryResolve(host: String): String? = try {
        InetAddress.getAllByName(host).firstOrNull()?.hostAddress
    } catch (_: Exception) {
        null
    }
}
