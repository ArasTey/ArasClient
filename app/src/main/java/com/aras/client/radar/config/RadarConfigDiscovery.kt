package com.aras.client.radar.config

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Multi-source discovery tuned for DPI-resistant protocols.
 * Prefer Reality / Hysteria2 / TUIC / XHTTP / Amnezia-style feeds when possible.
 */
class RadarConfigDiscovery(private val client: OkHttpClient = defaultClient()) {

    private data class SourceSpec(
        val repository: String,
        val directFeeds: List<String> = emptyList(),
        /** Extra weight for sources known to publish modern anti-DPI configs. */
        val sourceBonus: Int = 0
    )

    private data class SourceResult(
        val candidates: List<RadarConfigCandidate>,
        val report: RadarSourceReport
    )

    suspend fun discover(): RadarDiscoveryReport = withContext(Dispatchers.IO) {
        // The topic is a discovery index, not a config feed. Resolve its current
        // repositories through GitHub's API, then inspect each repository normally.
        val topicSources = fetchTopicSources()
        val activeSources = sources + topicSources
        val semaphore = Semaphore(5)
        val results = coroutineScope {
            activeSources.map { spec ->
                async { semaphore.withPermit { fetchSource(spec) } }
            }.awaitAll()
        }
        val unique = LinkedHashMap<String, RadarConfigCandidate>()
        results.flatMap { it.candidates }.forEach { c ->
            val existing = unique[c.identity]
            if (existing == null || c.dpiScore > existing.dpiScore) {
                unique[c.identity] = c
            }
        }
        val ordered = unique.values.sortedByDescending { it.dpiScore }
        val topicReport = RadarSourceReport(
            source = "github.com/topics/v2ray-config",
            fetchedFiles = if (topicSources.isNotEmpty()) 1 else 0,
            parsedCandidates = topicSources.size,
            error = if (topicSources.isEmpty()) "No repositories returned by the topic index" else null
        )
        RadarDiscoveryReport(ordered, results.map { it.report } + topicReport)
    }

    private fun fetchTopicSources(): List<SourceSpec> = runCatching {
        val json = JsonParser.parseString(
            get("https://api.github.com/search/repositories?q=topic%3Av2ray-configs&sort=updated&per_page=8")
        ).asJsonObject
        json.getAsJsonArray("items").mapNotNull { item ->
            val repo = item.asJsonObject
            val owner = repo.getAsJsonObject("owner")?.get("login")?.asString
            val name = repo.get("name")?.asString
            if (owner.isNullOrBlank() || name.isNullOrBlank()) null
            else SourceSpec("$owner/$name", sourceBonus = 4)
        }.distinctBy { it.repository }
    }.getOrElse { emptyList() }

    private fun fetchSource(spec: SourceSpec): SourceResult {
        val label = spec.repository
        var fetched = 0
        val found = LinkedHashMap<String, RadarConfigCandidate>()
        val errors = mutableListOf<String>()

        spec.directFeeds.forEach { feed ->
            runCatching { get(feed) }
                .onSuccess { body ->
                    fetched++
                    val feedName = feed.substringAfterLast('/')
                    RadarConfigParser.extract(body, label + "/" + feedName).forEach { raw ->
                        val scored = applyDpiScore(raw, spec.sourceBonus)
                        found.putIfAbsent(scored.identity, scored)
                    }
                }
                .onFailure { errors += it.message ?: "feed fetch failed" }
        }

        try {
            val metadata = JsonParser.parseString(get("https://api.github.com/repos/" + label)).asJsonObject
            val branch = metadata.get("default_branch")?.asString?.takeIf { it.isNotBlank() } ?: "main"
            val encodedBranch = encodePathSegment(branch)
            val tree = JsonParser.parseString(
                get("https://api.github.com/repos/" + label + "/git/trees/" + encodedBranch + "?recursive=1")
            ).asJsonObject
            val entries = tree.getAsJsonArray("tree") ?: return SourceResult(
                found.values.toList(),
                RadarSourceReport(label, fetched, found.size, errors.take(3).joinToString("; ").ifBlank { null })
            )

            val candidatePaths = entries.mapNotNull { el ->
                val obj = el.asJsonObject
                if (obj.get("type")?.asString != "blob") return@mapNotNull null
                val path = obj.get("path")?.asString ?: return@mapNotNull null
                val size = obj.get("size")?.asLong ?: 0L
                if (size <= 0L || size > MAX_FILE_BYTES) return@mapNotNull null
                if (!isCandidateFeed(path)) return@mapNotNull null
                path
            }.sortedBy { feedPriority(it) }.take(MAX_FILES_PER_REPOSITORY)

            candidatePaths.forEach { path ->
                val rawUrl = "https://raw.githubusercontent.com/" + label + "/" + branch + "/" + path
                runCatching { get(rawUrl) }
                    .onSuccess { body ->
                        fetched++
                        RadarConfigParser.extract(body, label + "/" + path).forEach { raw ->
                            val scored = applyDpiScore(raw, spec.sourceBonus)
                            found.putIfAbsent(scored.identity, scored)
                        }
                    }
                    .onFailure { errors += path + ": " + (it.message ?: "fetch failed") }
            }

            if (tree.get("truncated")?.asBoolean == true) {
                errors += "GitHub tree was truncated; only a partial file list was scanned"
            }
        } catch (error: Exception) {
            errors += error.message ?: "repository discovery failed"
        }

        return SourceResult(
            found.values.toList(),
            RadarSourceReport(
                label, fetched, found.size,
                errors.take(4).joinToString("; ").takeIf { errors.isNotEmpty() }
            )
        )
    }

    private fun applyDpiScore(c: RadarConfigCandidate, sourceBonus: Int): RadarConfigCandidate {
        val uriLower = c.uri.lowercase(Locale.ROOT)
        val remarkLower = c.remark.lowercase(Locale.ROOT)
        val proto = c.protocol.lowercase(Locale.ROOT)

        var score = sourceBonus

        score += when (proto) {
            "hysteria2", "hy2" -> 90
            "hysteria" -> 70
            "tuic" -> 85
            "vless" -> 55
            "trojan" -> 45
            "vmess" -> 25
            "ss", "shadowsocks" -> 15
            "anytls", "masque" -> 80
            "amneziawg", "awg", "wireguard" -> 60
            "mieru" -> 65
            else -> 20
        }

        if (uriLower.contains("security=reality") || uriLower.contains("type=reality") ||
            uriLower.contains("pbk=") || uriLower.contains("sid=")
        ) score += 45
        if (uriLower.contains("flow=xtls-rprx-vision") || uriLower.contains("xtls")) score += 20
        if (uriLower.contains("type=xhttp") || uriLower.contains("xhttp")) score += 25
        if (uriLower.contains("type=grpc") || uriLower.contains("serviceName=")) score += 10
        if (uriLower.contains("type=ws") || uriLower.contains("path=")) score += 5
        if (uriLower.contains("fp=chrome") || uriLower.contains("fp=firefox") || uriLower.contains("fp=safari")) score += 8
        if (uriLower.contains("sni=") || uriLower.contains("host=")) score += 5

        if (listOf("reality", "xtls", "vision", "hysteria", "hy2", "tuic", "xhttp", "stable", "vip", "premium")
                .any { remarkLower.contains(it) }
        ) score += 12

        score += when (c.port) {
            443 -> 15
            80, 8080, 8443, 2053, 2083, 2087, 2096 -> 8
            else -> 0
        }

        return c.copy(dpiScore = score.coerceIn(0, 200))
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "G-ST-VPN-Radar/2.2-DPI")
            .header("Accept", "application/vnd.github+json, text/plain, */*")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code)
            val body = response.body ?: throw IllegalStateException("Empty response")
            if (body.contentLength() > MAX_FILE_BYTES) throw IllegalStateException("Feed exceeds size limit")
            return body.string().take(MAX_FILE_BYTES)
        }
    }

    private fun isCandidateFeed(path: String): Boolean {
        val lower = path.lowercase()
        if (lower.contains("readme") || lower.contains("license") || lower.contains(".git")) return false
        val extension = lower.endsWith(".txt") || lower.endsWith(".yaml") ||
            lower.endsWith(".yml") || lower.endsWith(".json") || lower.endsWith(".conf") ||
            lower.endsWith(".list") || lower.endsWith(".sub") || lower.endsWith(".csv") ||
            lower.endsWith(".npv") || lower.endsWith(".npvt") || lower.endsWith(".npvs")
        if (!extension) return false
        val usefulName = listOf(
            "config", "sub", "all", "country", "node", "server", "verified", "top",
            "stable", "free", "vless", "vmess", "trojan", "ss", "proxy", "mix", "latest",
            "reality", "hysteria", "hy2", "tuic", "xhttp", "not_ru", "ir", "working"
        ).any(lower::contains)
        val shallow = path.count { it == '/' } <= 2
        return usefulName || shallow
    }

    private fun feedPriority(path: String): Int {
        val lower = path.lowercase()
        return when {
            lower.contains("reality") || lower.contains("hysteria") || lower.contains("hy2") -> 0
            lower.contains("verified") || lower.contains("working") -> 1
            lower.contains("top") || lower.contains("stable") -> 2
            lower.contains("not_ru") || lower.contains("country") -> 3
            lower.contains("latest") || lower.contains("sub") -> 4
            lower.contains("mix") || lower.contains("all") -> 5
            else -> 6
        }
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val MAX_FILE_BYTES = 2_500_000
        const val MAX_FILES_PER_REPOSITORY = 10

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(14, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val sources = listOf(
            SourceSpec(
                "mahdibland/V2RayAggregator",
                listOf(
                    "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
                    "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/splitted/mixed.txt"
                ),
                sourceBonus = 15
            ),
            SourceSpec(
                "Epodonios/v2ray-configs",
                listOf("https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt"),
                sourceBonus = 12
            ),
            SourceSpec(
                "hamedcode/port-based-v2ray-configs",
                listOf(
                    "https://raw.githubusercontent.com/hamedcode/port-based-v2ray-configs/main/sub/mixed.txt",
                    "https://raw.githubusercontent.com/hamedcode/port-based-v2ray-configs/main/sub/vless.txt"
                ),
                sourceBonus = 18
            ),
            SourceSpec(
                "rtwo2/FastNodes",
                listOf(
                    "https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/top.txt",
                    "https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/verified.txt",
                    "https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/stable.txt"
                ),
                sourceBonus = 20
            ),
            SourceSpec(
                "freefq/free",
                listOf("https://raw.githubusercontent.com/freefq/free/master/v2"),
                sourceBonus = 10
            ),
            SourceSpec("mfuu/v2ray", listOf("https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray"), sourceBonus = 8),
            SourceSpec("barry-far/V2ray-Config", sourceBonus = 10),
            SourceSpec("miladtahanian/V2RayScrapeByCountry", sourceBonus = 8),
            SourceSpec("SoliSpirit/v2ray-configs", sourceBonus = 8),
            SourceSpec("Delta-Kronecker/V2ray-Config", sourceBonus = 7),
            SourceSpec("Danialsamadi/v2go", sourceBonus = 7),
            SourceSpec("morpheusadam/v2ray-config", sourceBonus = 6),
            SourceSpec("MatinGhanbari/v2ray-configs", sourceBonus = 6),
            SourceSpec("0xRadikal/Free-v2ray-Configs", sourceBonus = 8),
            SourceSpec("drmikecrypto/PulseConfigs", sourceBonus = 7),
            SourceSpec(
                "ircfspace/tconfig",
                listOf(
                    // tconfig's UI serves the current mixed subscription from its upstream.
                    "https://raw.githubusercontent.com/SoliSpirit/v2ray-configs/main/all_configs.txt",
                    "https://raw.githubusercontent.com/SoliSpirit/v2ray-configs/main/Protocols/vless.txt",
                    "https://raw.githubusercontent.com/SoliSpirit/v2ray-configs/main/Protocols/vmess.txt"
                ),
                sourceBonus = 8
            ),
            SourceSpec(
                "vlesscollector/vlesscollector",
                listOf("https://raw.githubusercontent.com/vlesscollector/vlesscollector/main/vless_configs.txt"),
                sourceBonus = 9
            ),
            SourceSpec(
                "Edsel-Rahimi/Ariston",
                listOf(
                    "https://raw.githubusercontent.com/Edsel-Rahimi/Ariston/data/validated.txt",
                    "https://raw.githubusercontent.com/Edsel-Rahimi/Ariston/data/gold.txt"
                ),
                sourceBonus = 18
            ),
            SourceSpec(
                "patterniha/free-configs",
                listOf(
                    "https://raw.githubusercontent.com/patterniha/free-configs/main/configs.txt",
                    "https://raw.githubusercontent.com/patterniha/free-configs/main/configs_base64.txt"
                ),
                sourceBonus = 12
            ),
            SourceSpec("Argh94/V2RayAutoConfig", sourceBonus = 6),
            SourceSpec("aiboboxx/v2rayfree", sourceBonus = 5),
            SourceSpec("Pawdroid/Free-servers", sourceBonus = 5),
            SourceSpec(
                "nikita29a/FreeProxyList",
                sourceBonus = 22
            ),
            SourceSpec(
                "Subzio/subzio",
                sourceBonus = 20
            ),
            SourceSpec(
                "3inker/v2ray-subscription",
                sourceBonus = 18
            ),
            SourceSpec(
                "TelegaVPN/free-vpn-keys",
                sourceBonus = 22
            ),
            SourceSpec(
                "chaishpish/free_server_v2ray",
                sourceBonus = 12
            ),
            SourceSpec(
                "hysteria350/free-jichang",
                sourceBonus = 15
            ),
            SourceSpec(
                "PrinceVSFX/Hysteria2-Configs",
                listOf("https://raw.githubusercontent.com/PrinceVSFX/Hysteria2-Configs/main/Configs_list.txt"),
                sourceBonus = 25
            )
        )
    }
}
