package com.aras.client.fmt

import com.aras.client.dto.XrayConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.util.JsonUtil
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object CustomFmt : FmtBase() {
    /**
     * Parses a JSON string into a ProfileItem object.
     *
     * @param str the JSON string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem {
        val config = ProfileItem.create(EConfigType.CUSTOM)

        val fullConfig = JsonUtil.fromJson(str, XrayConfig::class.java)
        val outbound = fullConfig?.getProxyOutbound()

        config.remarks = fullConfig?.remarks ?: System.currentTimeMillis().toString()
        config.server = outbound?.getServerAddress()
        config.serverPort = outbound?.getServerPort()?.toString()

        return config
    }

    /**
     * The highest core version an imported custom-config text demands, read from
     * its `"version": { "min": "26.11.1" }` markers — a single config object or a
     * subscription array of them. Null when the text demands nothing.
     */
    fun minCoreVersionRequired(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val element = try {
            JsonParser.parseString(raw)
        } catch (e: Exception) {
            return null
        }
        fun minOf(e: Any?): String? =
            (e as? JsonObject)?.getAsJsonObject("version")?.get("min")
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        var best = minOf(element)
        (element as? JsonArray)?.forEach { best = maxVersion(best, minOf(it)) }
        return best
    }

    /**
     * True when [coreVersion] is older than the dotted numeric [minimum]. Text
     * without a dotted number ("Unknown") never warns, and extra suffixes are
     * ignored: "26.11.1-P71" reads as 26.11.1.
     */
    fun isCoreOlderThan(coreVersion: String, minimum: String): Boolean {
        fun parts(v: String): List<Int> =
            Regex("\\d+(\\.\\d+)*").find(v)?.value?.split('.')?.mapNotNull { it.toIntOrNull() }
                ?: emptyList()
        val core = parts(coreVersion)
        val min = parts(minimum)
        if (core.isEmpty() || min.isEmpty()) return false
        for (i in 0 until maxOf(core.size, min.size)) {
            val left = core.getOrElse(i) { 0 }
            val right = min.getOrElse(i) { 0 }
            if (left != right) return left < right
        }
        return false
    }

    /** The greater of two dotted versions; [a] wins ties, nulls fall through. */
    fun maxVersion(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        isCoreOlderThan(a, b) -> b
        else -> a
    }

    /**
     * Prepares a full custom config for the outbound-delay probe, which keeps only the
     * outbounds and drops routing and DNS — leaving the FIRST outbound as the only path.
     * Serverless-style configs open that slot with `block`, so every probe is blocked and
     * the card reads -1 while the same profile connects fine. Returns the config with the
     * outbound its own routing picks for a TLS request to port 443 moved to the front, or
     * null when the config already tests through a usable outbound.
     */
    fun rewriteForOutboundTest(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val config = try {
            JsonParser.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        } ?: return null

        val outbounds = config.getAsJsonArray("outbounds")?.mapNotNull {
            it as? JsonObject
        } ?: return null
        if (outbounds.isEmpty()) return null
        if (isTestableOutbound(outbounds.first())) return null

        val byTag = outbounds
            .mapNotNull { outbound ->
                outbound.get("tag")?.asStringOrNull()?.let { it to outbound }
            }
            .toMap()
        val routingRules = config.getAsJsonObject("routing")?.getAsJsonArray("rules")
        val target = testOutboundOfRule(routingRules, byTag)
            ?: outbounds.firstOrNull { isTestableOutbound(it) }
            ?: return null

        val reordered = JsonArray().apply {
            add(target)
            outbounds.forEach { if (it !== target) add(it) }
        }
        config.add("outbounds", reordered)
        return config.toString()
    }

    private fun isTestableOutbound(outbound: JsonObject?): Boolean {
        val protocol = outbound?.get("protocol")?.asStringOrNull() ?: return false
        return protocol != "block" && protocol != "dns"
    }

    /**
     * The first routing rule that a bare TLS/443 dial with no inbound and no resolved
     * domain would hit. Rules keyed on inbound tags or domains cannot match that dial,
     * and IP-keyed rules only when they cover every address.
     */
    private fun testOutboundOfRule(rules: JsonArray?, byTag: Map<String, JsonObject>): JsonObject? {
        rules ?: return null
        for (element in rules) {
            val rule = element as? JsonObject ?: continue
            if (rule.has("inboundTag") || rule.has("domain") || rule.has("balancerTag")) continue
            if (!portIncludes(rule.get("port"), 443)) continue
            if (!listContains(rule.get("network"), "tcp")) continue
            if (!listContains(rule.get("protocol"), "tls")) continue
            if (!isCatchAllIp(rule.get("ip"))) continue
            val tag = rule.get("outboundTag")?.asStringOrNull() ?: continue
            val outbound = byTag[tag] ?: continue
            if (isTestableOutbound(outbound)) return outbound
        }
        return null
    }

    private fun portIncludes(port: JsonElement?, tested: Int): Boolean {
        val text = port?.asStringOrNull() ?: return true
        return text.split(',').any { part ->
            val range = part.trim()
            if (range.contains('-')) {
                val bounds = range.split('-', limit = 2)
                val from = bounds[0].trim().toLongOrNull()
                val to = bounds.getOrNull(1)?.trim()?.toLongOrNull()
                from != null && to != null && tested >= from && tested <= to
            } else {
                range.toLongOrNull() == tested.toLong()
            }
        }
    }

    /** Absent or empty means unrestricted, so the rule still applies to the probe. */
    private fun listContains(value: JsonElement?, wanted: String): Boolean {
        if (value == null || value.isJsonNull) return true
        val values = when {
            value.isJsonArray -> value.asJsonArray.mapNotNull { it.asStringOrNull() }
            else -> value.asStringOrNull()?.split(',')?.map { it.trim() }
        } ?: return true
        return values.isNullOrEmpty() || values.any { it.equals(wanted, ignoreCase = true) }
    }

    private fun isCatchAllIp(ip: JsonElement?): Boolean {
        if (ip == null || ip.isJsonNull) return true
        val entries = ip.asJsonArray.mapNotNull { it.asStringOrNull() }
        return entries.isNotEmpty() && entries.all { it == "0.0.0.0/0" || it == "::/0" }
    }

    private fun JsonElement.asStringOrNull(): String? =
        takeIf { isJsonPrimitive }?.asString
}