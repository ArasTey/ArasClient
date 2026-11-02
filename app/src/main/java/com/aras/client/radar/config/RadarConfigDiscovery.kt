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
import java.util.concurrent.TimeUnit

/** Multi-source config discovery for Radar. Upgraded feeds, matching, timeouts. */
class RadarConfigDiscovery(private val client: OkHttpClient = defaultClient()) {
    private data class SourceSpec(val repository: String, val directFeeds: List<String> = emptyList())
    private data class SourceResult(val candidates: List<RadarConfigCandidate>, val report: RadarSourceReport)

    suspend fun discover(): RadarDiscoveryReport = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(4)
        val results = coroutineScope {
            sources.map { spec -> async { semaphore.withPermit { fetchSource(spec) } } }.awaitAll()
        }
        val unique = LinkedHashMap<String, RadarConfigCandidate>()
        results.flatMap { it.candidates }.forEach { unique.putIfAbsent(it.identity, it) }
        RadarDiscoveryReport(unique.values.toList(), results.map { it.report })
    }

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
                    RadarConfigParser.extract(body, label + "/" + feedName).forEach {
                        found.putIfAbsent(it.identity, it)
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
                        RadarConfigParser.extract(body, label + "/" + path).forEach {
                            found.putIfAbsent(it.identity, it)
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

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "G-ST-VPN-Radar/2.1")
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
            "stable", "free", "vless", "vmess", "trojan", "ss", "proxy", "mix", "latest"
        ).any(lower::contains)
        val shallow = path.count { it == '/' } <= 2
        return usefulName || shallow
    }

    private fun feedPriority(path: String): Int {
        val lower = path.lowercase()
        return when {
            lower.contains("verified") -> 0
            lower.contains("top") -> 1
            lower.contains("country") -> 2
            lower.contains("stable") -> 3
            lower.contains("latest") -> 4
            lower.contains("sub") -> 5
            lower.contains("mix") || lower.contains("all") -> 6
            else -> 7
        }
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val MAX_FILE_BYTES = 2_500_000
        const val MAX_FILES_PER_REPOSITORY = 8

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val sources = listOf(
            SourceSpec("mahdibland/V2RayAggregator", listOf(
                "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
                "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/splitted/mixed.txt"
            )),
            SourceSpec("Epodonios/v2ray-configs", listOf(
                "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt"
            )),
            SourceSpec("mfuu/v2ray", listOf(
                "https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray"
            )),
            SourceSpec("miladtahanian/V2RayScrapeByCountry"),
            SourceSpec("SoliSpirit/v2ray-configs"),
            SourceSpec("Delta-Kronecker/V2ray-Config"),
            SourceSpec("Danialsamadi/v2go"),
            SourceSpec("morpheusadam/v2ray-config"),
            SourceSpec("MatinGhanbari/v2ray-configs"),
            SourceSpec("0xRadikal/Free-v2ray-Configs"),
            SourceSpec("drmikecrypto/PulseConfigs"),
            SourceSpec("rtwo2/FastNodes", listOf(
                "https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/top.txt",
                "https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/verified.txt",
                "https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/stable.txt"
            )),
            SourceSpec("barry-far/V2ray-Config"),
            SourceSpec("ircfspace/tconfig"),
            SourceSpec("Argh94/V2RayAutoConfig"),
            SourceSpec("freefq/free", listOf(
                "https://raw.githubusercontent.com/freefq/free/master/v2"
            )),
            SourceSpec("aiboboxx/v2rayfree"),
            SourceSpec("Pawdroid/Free-servers")
        )
    }
}
