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

/** Multi-source discovery. Each public repository is isolated so one dead feed cannot abort a scan. */
class RadarConfigDiscovery(private val client: OkHttpClient = defaultClient()) {
    private data class SourceSpec(val repository: String, val directFeeds: List<String> = emptyList())
    private data class SourceResult(val candidates: List<RadarConfigCandidate>, val report: RadarSourceReport)

    suspend fun discover(): RadarDiscoveryReport = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(3)
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
            val metadata = JsonParser.parseString(get("https://api.github.com/repos/$label")).asJsonObject
            val branch = metadata.get("default_branch")?.asString?.takeIf { it.isNotBlank() } ?: "main"
            val encodedBranch = encodePathSegment(branch)
            val tree = JsonParser.parseString(
                get("https://api.github.com/repos/$label/git/trees/$encodedBranch?recursive=1")
            ).asJsonObject
            val entries = tree.getAsJsonArray("tree") ?: return SourceResult(
                found.values.toList(), RadarSourceReport(label, fetched, found.size, "Repository tree is missing")
            )
            val paths = entries.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val path = obj.get("path")?.asString ?: return@mapNotNull null
                val type = obj.get("type")?.asString
                val size = obj.get("size")?.asLong ?: 0L
                if (type != "blob" || size > MAX_FILE_BYTES || !isCandidateFeed(path)) null else path
            }.sortedBy { feedPriority(it) }.take(MAX_FILES_PER_REPOSITORY)

            paths.forEach { path ->
                val rawUrl = "https://raw.githubusercontent.com/$label/$encodedBranch/" +
                    path.split('/').joinToString("/") { encodePathSegment(it) }
                runCatching { get(rawUrl) }
                    .onSuccess { body ->
                        fetched++
                        RadarConfigParser.extract(body, label + "/" + path).forEach {
                            found.putIfAbsent(it.identity, it)
                        }
                    }
                    .onFailure { errors += "$path: ${it.message ?: "fetch failed"}" }
            }
            if (tree.get("truncated")?.asBoolean == true) errors += "GitHub tree was truncated; only a partial file list was scanned"
        } catch (error: Exception) {
            errors += error.message ?: "repository discovery failed"
        }

        SourceResult(
            found.values.toList(),
            RadarSourceReport(label, fetched, found.size, errors.take(3).joinToString("; ").takeIf { errors.isNotEmpty() })
        )
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "G-ST-VPN-Radar/2.0")
            .header("Accept", "application/vnd.github+json")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Empty response")
            if (body.contentLength() > MAX_FILE_BYTES) throw IllegalStateException("Feed exceeds size limit")
            return body.string().take(MAX_FILE_BYTES)
        }
    }

    private fun isCandidateFeed(path: String): Boolean {
        val lower = path.lowercase()
        val extension = lower.endsWith(".txt") || lower.endsWith(".yaml") ||
            lower.endsWith(".yml") || lower.endsWith(".json") || lower.endsWith(".conf")
        val usefulName = listOf("config", "sub", "all", "country", "node", "server", "verified", "top", "stable")
            .any(lower::contains)
        return extension && usefulName && !lower.contains("readme")
    }

    private fun feedPriority(path: String): Int {
        val lower = path.lowercase()
        return when {
            lower.contains("verified") -> 0
            lower.contains("top") -> 1
            lower.contains("country") -> 2
            lower.contains("stable") -> 3
            lower.contains("sub") -> 4
            else -> 5
        }
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val MAX_FILE_BYTES = 2_000_000
        const val MAX_FILES_PER_REPOSITORY = 5

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val sources = listOf(
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
            SourceSpec("Argh94/V2RayAutoConfig")
        )
    }
}
