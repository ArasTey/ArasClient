package com.aras.client.util

import com.aras.client.AppConfig
import com.aras.client.handler.MmkvManager
import com.aras.client.util.JsonUtil
import com.aras.client.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves a config host to its country (ISO-2) and caches it per host.
 *
 * Uses the free ip-api.com batch endpoint (no key, up to 100 IPs per call):
 *   POST http://ip-api.com/batch?fields=status,countryCode,query
 * Hosts are resolved to IPs in parallel with a short timeout; results are
 * cached both in memory (per process) and MMKV (persistent), so a ping round
 * only ever geolocates each host once.
 */
object GeoIPResolver {

    private const val BATCH_URL = "http://ip-api.com/batch?fields=status,countryCode,query"
    private const val CACHE_PREFIX = "geoip_host_"
    private const val BATCH_SIZE = 100
    private const val DNS_TIMEOUT_MS = 4000L

    private val memCache = ConcurrentHashMap<String, String>()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun cached(host: String): String {
        if (host.isBlank()) return ""
        memCache[host]?.let { return it }
        val persisted = MmkvManager.decodeSettingsString(CACHE_PREFIX + host) ?: ""
        if (persisted.isNotBlank()) memCache[host] = persisted
        return persisted
    }

    /**
     * Resolves and caches countries for the given hosts. Missing entries are
     * looked up (DNS + GeoIP) in parallel batches; failures are silent.
     */
    suspend fun refresh(hosts: List<String>) {
        val missing = hosts
            .filter { it.isNotBlank() && cached(it).isBlank() }
            .distinct()
        if (missing.isEmpty()) return

        // 1) DNS: host -> IP (parallel, bounded)
        val resolved = coroutineScope {
            val semaphore = Semaphore(20)
            missing.map { host ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(DNS_TIMEOUT_MS) {
                        semaphore.withPermit {
                            try {
                                InetAddress.getByName(host).hostAddress
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }?.let { host to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        if (resolved.isEmpty()) return

        // 2) GeoIP: batch lookups of 100 IPs each.
        resolved.values.chunked(BATCH_SIZE).forEach { batch ->
            try {
                val body = JsonUtil.toJson(batch).toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(BATCH_URL).post(body).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val json = response.body?.string() ?: return@use
                    val root = JsonUtil.parseString(json) ?: return@use
                    if (!root.isJsonArray) return@use
                    root.asJsonArray.forEach { el ->
                        val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        val ip = obj.get("query")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: return@forEach
                        val code = obj.get("countryCode")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: return@forEach
                        if (code.length == 2) {
                            resolved.entries.firstOrNull { it.value == ip }?.let { (host, _) ->
                                memCache[host] = code.uppercase()
                                MmkvManager.encodeSettings(CACHE_PREFIX + host, code.uppercase())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "GeoIP batch lookup failed: ${e.message}")
            }
        }
    }
}
