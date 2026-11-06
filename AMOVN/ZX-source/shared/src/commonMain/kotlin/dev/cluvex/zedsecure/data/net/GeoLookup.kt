package dev.cluvex.zedsecure.data.net

import dev.cluvex.zedsecure.domain.config.LocalProxy
import dev.cluvex.zedsecure.platform.httpGetViaSocks
import dev.cluvex.zedsecure.platform.currentTimeMillis
import dev.cluvex.zedsecure.platform.resolveHostAddress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object GeoLookup {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cacheLock = Mutex()
    private val cache = HashMap<String, String>()

    private const val FAILURE_TTL_MS = 10 * 60_000L
    private val failures = HashMap<String, Long>()

    private const val RESOLVE_TIMEOUT_MS = 3_000L

    suspend fun countryOf(host: String): String? {
        if (host.isBlank() || host == "-") return null
        val now = currentTimeMillis()
        cacheLock.withLock {
            cache[host]?.let { return it }
            failures[host]?.let { if (now - it < FAILURE_TTL_MS) return null }
        }

        val ip = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveHostAddress(host) }
            ?: return null.also { rememberFailure(host) }

        val body = runCatching { httpGetViaSocks("https://api.ip.sb/geoip/$ip", socksPort = null, connectTimeoutMs = 6_000, readTimeoutMs = 6_000) }.getOrNull()
            ?: runCatching { httpGetViaSocks("https://api.ip.sb/geoip/$ip", socksPort = dev.cluvex.zedsecure.core.VpnManager.activeSocksPort ?: LocalProxy.SOCKS_PORT, connectTimeoutMs = 6_000, readTimeoutMs = 6_000) }.getOrNull()
            ?: return null.also { rememberFailure(host) }

        val code = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["country_code"]?.jsonPrimitive?.content
                ?: obj["countryCode"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.length == 2 }?.uppercase()

        if (code != null) cacheLock.withLock { cache[host] = code } else rememberFailure(host)
        return code
    }

    private suspend fun rememberFailure(host: String) {
        cacheLock.withLock { failures[host] = currentTimeMillis() }
    }
}
