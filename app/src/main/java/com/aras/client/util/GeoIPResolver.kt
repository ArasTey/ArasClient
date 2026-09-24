package com.aras.client.util

import com.aras.client.AppConfig
import com.aras.client.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Resolves config hosts to an IP country and caches the ISO code per hostname. */
object GeoIPResolver {

    private const val BATCH_URL = "http://ip-api.com/batch?fields=status,countryCode,query"
    private const val CACHE_PREFIX = "geoip_host_"
    private const val BATCH_SIZE = 100
    private const val DNS_TIMEOUT_MS = 4000L

    private val memCache = ConcurrentHashMap<String, String>()
    private val refreshMutex = Mutex()
    private val validIsoCodes by lazy {
        Locale.getISOCountries().map { it.uppercase(Locale.ROOT) }.toSet()
    }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun cached(host: String): String {
        if (host.isBlank()) return ""
        memCache[host]?.let { return it }
        val persisted = normalizeIso(
            MmkvManager.decodeSettingsString(CACHE_PREFIX + host)
        ).orEmpty()
        if (persisted.isNotBlank()) memCache[host] = persisted
        return persisted
    }

    /** Concurrent callers share one refresh pass; the second sees the first pass's cache. */
    suspend fun refresh(hosts: List<String>) = refreshMutex.withLock {
        val missing = hosts
            .filter { it.isNotBlank() && cached(it).isBlank() }
            .distinct()
        if (missing.isEmpty()) return@withLock

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
        if (resolved.isEmpty()) return@withLock

        resolved.values.distinct().chunked(BATCH_SIZE).forEach { batch ->
            try {
                val body = JsonUtil.toJson(batch).toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(BATCH_URL).post(body).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val json = response.body?.string() ?: return@use
                    val root = try {
                        com.google.gson.JsonParser.parseString(json)
                    } catch (e: Exception) {
                        LogUtil.w(AppConfig.TAG, "GeoIP: bad response: ${e.message}")
                        return@use
                    }
                    if (!root.isJsonArray) return@use

                    val codeByIp = mutableMapOf<String, String>()
                    root.asJsonArray.forEach { element ->
                        val obj = element.takeIf { it.isJsonObject }?.asJsonObject
                            ?: return@forEach
                        val ip = obj.get("query")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: return@forEach
                        val code = normalizeIso(
                            obj.get("countryCode")?.takeIf { it.isJsonPrimitive }?.asString
                        ) ?: return@forEach
                        codeByIp[ip] = code
                    }
                    resolved.forEach { (host, ip) ->
                        codeByIp[ip]?.let { code ->
                            memCache[host] = code
                            MmkvManager.encodeSettings(CACHE_PREFIX + host, code)
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "GeoIP batch lookup failed: ${e.message}")
            }
        }
    }

    private fun normalizeIso(value: String?): String? = value
        ?.trim()
        ?.uppercase(Locale.ROOT)
        ?.takeIf { it.length == 2 && it in validIsoCodes }
}
