package com.aras.client.ui.dns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

data class DnsScanCheck(
    val name: String,
    val passed: Boolean,
    val responseCode: Int?,
    val latencyMs: Long?,
    val transport: String,
    val detail: String,
)

data class DnsScanProgress(
    val completed: Int,
    val total: Int,
    val resolverName: String,
)

data class DnsResolverScanResult(
    val resolver: DnsProfile,
    val checks: List<DnsScanCheck>,
) {
    val passedCount: Int get() = checks.count { it.passed }
    val isHealthy: Boolean get() = passedCount >= 4 && checks.any { it.name == "A" && it.passed }
    val averageLatencyMs: Long?
        get() = checks.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }?.average()?.toLong()
}

enum class DnsScanMode { SIMPLE, ADVANCED }

data class DnsScanHistoryEntry(
    val timestamp: Long,
    val results: List<DnsResolverScanResult>,
)

object DnsScannerEngine {
    private const val DEFAULT_TIMEOUT_MS = 1800
    private const val MAX_RESPONSE_BYTES = 4096
    private val checkTypes = listOf("A" to 1, "AAAA" to 28, "NS" to 2, "TXT" to 16)

    suspend fun scan(
        resolvers: List<DnsProfile>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        concurrency: Int = 3,
        mode: DnsScanMode = DnsScanMode.ADVANCED,
        onProgress: (DnsScanProgress) -> Unit = {},
    ): List<DnsResolverScanResult> {
        require(timeoutMs in 300..10_000) { "Timeout must be between 300 and 10000 ms" }
        require(concurrency in 1..8) { "Concurrency must be between 1 and 8" }
        val uniqueResolvers = resolvers.distinctBy { it.primary }
        val gate = Semaphore(concurrency)
        val completed = AtomicInteger(0)
        return coroutineScope {
            uniqueResolvers.map { resolver ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        val result = scanOne(resolver, timeoutMs, mode)
                        val count = completed.incrementAndGet()
                        onProgress(DnsScanProgress(count, uniqueResolvers.size, resolver.name))
                        result
                    }
                }
            }.awaitAll()
        }
    }

    internal fun buildQuery(id: Int, domain: String, type: Int, edns: Boolean = false): ByteArray {
        require(id in 0..65535)
        require(type in 1..65535)
        val labels = domain.trimEnd('.').split('.')
        require(labels.isNotEmpty() && labels.all { it.isNotEmpty() && it.length <= 63 })
        val nameBytes = labels.fold(mutableListOf<Byte>()) { out, label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.all { (it.toInt() and 0xff) in 0x21..0x7e })
            out.add(bytes.size.toByte())
            out.addAll(bytes.toList())
            out
        }.apply { add(0) }.toByteArray()

        val header = ByteArray(12)
        header[0] = (id ushr 8).toByte()
        header[1] = id.toByte()
        header[2] = 0x01 // recursion desired
        header[5] = 0x01 // QDCOUNT
        if (edns) header[11] = 0x01 // ARCOUNT
        val question = nameBytes + byteArrayOf((type ushr 8).toByte(), type.toByte(), 0, 1)
        val opt = if (edns) byteArrayOf(
            0, 0, 41, 0x04, 0xD0.toByte(), 0, 0, 0, 0, 0, 0
        ) else byteArrayOf()
        return header + question + opt
    }

    internal fun parseResponse(packet: ByteArray, expectedId: Int, expectNxDomain: Boolean = false): Int {
        require(packet.size >= 12) { "DNS response header is truncated" }
        val id = ((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff)
        require(id == expectedId) { "DNS transaction ID mismatch" }
        require((packet[2].toInt() and 0x80) != 0) { "Packet is not a DNS response" }
        val rcode = packet[3].toInt() and 0x0f
        if (expectNxDomain) {
            require(rcode == 3) { "Expected NXDOMAIN (3), got $rcode" }
        } else {
            require(rcode == 0) { "Resolver returned DNS error code $rcode" }
        }
        return rcode
    }

    private suspend fun scanOne(resolver: DnsProfile, timeoutMs: Int, mode: DnsScanMode): DnsResolverScanResult {
        if (!DnsChangerStore.isIpAddress(resolver.primary)) {
            return DnsResolverScanResult(resolver, listOf(
                DnsScanCheck("VALIDATION", false, null, null, "none", "Invalid resolver address")
            ))
        }
        val checks = mutableListOf<DnsScanCheck>()
        val selectedChecks = if (mode == DnsScanMode.SIMPLE) listOf("A" to 1) else checkTypes
        for ((label, type) in selectedChecks) {
            currentCoroutineContext().ensureActive()
            checks += runCheck(resolver.primary, label, "example.com", type, timeoutMs, false, false)
        }
        if (mode == DnsScanMode.ADVANCED) {
            currentCoroutineContext().ensureActive()
            checks += runCheck(resolver.primary, "EDNS", "example.com", 1, timeoutMs, true, false)
            val randomLabel = "radar-" + Random.nextInt(1_000_000, 9_999_999)
            currentCoroutineContext().ensureActive()
            checks += runCheck(resolver.primary, "RND", randomLabel + ".example.com", 1, timeoutMs, false, true)
            currentCoroutineContext().ensureActive()
            checks += runCheck(resolver.primary, "NXDOMAIN", randomLabel + ".invalid", 1, timeoutMs, false, true)
            currentCoroutineContext().ensureActive()
            checks += runCheck(resolver.primary, "DPI heuristic", "probe-" + randomLabel + ".invalid", 1, timeoutMs, false, true)
            checks += runGoogleDohCheck(timeoutMs)
            checks += runCloudflareDotCheck(timeoutMs)
        }
        return DnsResolverScanResult(resolver, checks)
    }

    private fun runCheck(
        server: String,
        label: String,
        domain: String,
        type: Int,
        timeoutMs: Int,
        edns: Boolean,
        expectNxDomain: Boolean,
    ): DnsScanCheck {
        val id = Random.nextInt(0, 65536)
        val query = try { buildQuery(id, domain, type, edns) } catch (e: Exception) {
            return DnsScanCheck(label, false, null, null, "none", e.message ?: "Invalid query")
        }
        val started = System.nanoTime()
        var receivedResponse: ByteArray? = null
        return try {
            val address = InetAddress.getByName(server)
            val udp = DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(address, 53)
                socket.send(DatagramPacket(query, query.size))
                val buffer = ByteArray(MAX_RESPONSE_BYTES)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                buffer.copyOf(response.length)
            }
            val truncated = udp.size >= 4 && (udp[2].toInt() and 0x02) != 0
            val response = if (truncated) tcpQuery(address, query, timeoutMs) else udp
            receivedResponse = response
            val rcode = try {
                parseResponse(response, id, expectNxDomain)
            } catch (error: Exception) {
                if (expectNxDomain && response.size >= 12) {
                    val flags = response[2].toInt() and 0xff
                    val responseId = ((response[0].toInt() and 0xff) shl 8) or (response[1].toInt() and 0xff)
                    val responseCode = response[3].toInt() and 0x0f
                    val answerCount = ((response[6].toInt() and 0xff) shl 8) or (response[7].toInt() and 0xff)
                    if (responseId == id && (flags and 0x80) != 0 && responseCode == 0 && answerCount > 0) {
                        val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                        return DnsScanCheck(
                            label, false, responseCode, elapsed, if (truncated) "TCP" else "UDP",
                            "Possible wildcard or synthetic DNS answer; this is not proof of DPI"
                        )
                    }
                }
                throw error
            }
            val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            DnsScanCheck(
                label, true, rcode, elapsed, if (truncated) "TCP" else "UDP",
                if (expectNxDomain) "NXDOMAIN as expected; this does not rule out DPI" else "Response validated"
            )
        } catch (e: SocketTimeoutException) {
            DnsScanCheck(label, false, null, null, "UDP", "Timed out")
        } catch (e: Exception) {
            val responseCode = receivedResponse?.takeIf { it.size >= 12 }?.get(3)?.toInt()?.and(0x0f)
            DnsScanCheck(label, false, responseCode, null, "UDP", e.message ?: "Probe failed")
        }
    }

    private fun runGoogleDohCheck(timeoutMs: Int): DnsScanCheck {
        val started = System.nanoTime()
        var connection: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL("https://dns.google/resolve?name=example.com&type=A")
            connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("Accept", "application/dns-json")
                setRequestProperty("User-Agent", "G-ST-VPN-DNS-Scanner")
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val status = org.json.JSONObject(body).optInt("Status", -1)
            val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            DnsScanCheck("Google DoH (route)", code == 200 && status == 0, status, elapsed, "DoH/HTTPS", if (code == 200 && status == 0) "Secure DNS endpoint reachable" else "HTTP $code / DNS status $status")
        } catch (error: Exception) {
            DnsScanCheck("Google DoH (route)", false, null, null, "DoH/HTTPS", error.message ?: "DoH probe failed")
        } finally { connection?.disconnect() }
    }

    private fun runCloudflareDotCheck(timeoutMs: Int): DnsScanCheck {
        val started = System.nanoTime()
        val id = Random.nextInt(0, 65536)
        val query = buildQuery(id, "example.com", 1)
        return try {
            val socket = javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket
            socket.use { tls ->
                tls.soTimeout = timeoutMs
                tls.connect(InetSocketAddress("one.one.one.one", 853), timeoutMs)
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(javax.net.ssl.SNIHostName("one.one.one.one"))
                }
                tls.startHandshake()
                val output = DataOutputStream(tls.outputStream)
                output.writeShort(query.size)
                output.write(query)
                output.flush()
                val input = DataInputStream(tls.inputStream)
                val length = input.readUnsignedShort()
                require(length in 12..MAX_RESPONSE_BYTES) { "Invalid DoT response length" }
                val response = ByteArray(length).also { input.readFully(it) }
                val rcode = parseResponse(response, id)
                val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                DnsScanCheck("Cloudflare DoT (route)", true, rcode, elapsed, "DoT/TLS", "TLS certificate and DNS response validated")
            }
        } catch (error: Exception) {
            DnsScanCheck("Cloudflare DoT (route)", false, null, null, "DoT/TLS", error.message ?: "DoT probe failed")
        }
    }
    private fun tcpQuery(address: InetAddress, query: ByteArray, timeoutMs: Int): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, 53), timeoutMs)
            socket.soTimeout = timeoutMs
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            require(length in 12..MAX_RESPONSE_BYTES) { "Invalid TCP DNS response length" }
            return ByteArray(length).also { input.readFully(it) }
        }
    }
}
