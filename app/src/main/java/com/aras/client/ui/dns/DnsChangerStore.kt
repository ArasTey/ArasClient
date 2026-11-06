package com.aras.client.ui.dns

import com.aras.client.handler.MmkvManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

data class DnsProfile(
    val name: String,
    val primary: String,
    val secondary: String,
    val region: String = "Global",
    val custom: Boolean = false,
    val port: Int = 53,
)

object DnsChangerStore {
    private const val PROFILES_KEY = "dns_changer_profiles"
    const val ENABLED_KEY = "dns_changer_enabled"
    const val PRIMARY_KEY = "dns_changer_primary"
    const val SECONDARY_KEY = "dns_changer_secondary"

    // Curated resolver catalogue inspired by the category and validation approach
    // documented by Hadooshi/Iran-DNS-Toolbox. Regional resolvers are ISP-dependent.
    val presets = listOf(
        DnsProfile("Radar Game", "10.202.10.10", "10.202.10.11", "Iran • Gaming"),
        DnsProfile("Electro", "78.157.42.100", "78.157.42.101", "Iran • Gaming"),
        DnsProfile("Shecan", "178.22.122.100", "185.51.200.2", "Iran • Anti-sanction"),
        DnsProfile("Shecan Pro", "178.22.122.101", "185.51.200.1", "Iran • Account / IP registration"),
        DnsProfile("Begzar", "185.55.226.26", "185.55.225.25", "Iran • Gaming / Web"),
        DnsProfile("Begzar 2", "185.55.224.24", "185.55.225.25", "Iran • Gaming / Web"),
        DnsProfile("403 Online", "10.202.10.202", "10.202.10.102", "Iran • Regional"),
        DnsProfile("Vanilla", "10.139.177.21", "10.139.177.22", "Iran • Regional"),
        DnsProfile("HostIran", "172.29.0.100", "172.29.2.100", "Iran • Regional"),
        DnsProfile("Shelter", "94.103.125.157", "94.103.125.158", "Iran • Gaming"),
        DnsProfile("Beshkan", "181.41.194.177", "181.41.194.186", "Iran • Anti-sanction"),
        DnsProfile("Pishgaman", "5.202.100.100", "5.202.100.101", "Iran • ISP"),
        DnsProfile("Shatel", "85.15.1.14", "85.15.1.15", "Iran • ISP"),
        DnsProfile("Pars Online", "91.99.101.12", "", "Iran • ISP"),
        DnsProfile("AbrBaran", "172.16.1.100", "172.16.2.100", "Iran • Regional"),
        DnsProfile("Cloudflare", "1.1.1.1", "1.0.0.1", "Global • Public"),
        DnsProfile("Cloudflare Malware", "1.1.1.2", "1.0.0.2", "Global • Security"),
        DnsProfile("Cloudflare Family", "1.1.1.3", "1.0.0.3", "Global • Family"),
        DnsProfile("Google", "8.8.8.8", "8.8.4.4", "Global • Public"),
        DnsProfile("OpenDNS", "208.67.222.222", "208.67.220.220", "Global • Security"),
        DnsProfile("Quad9", "9.9.9.9", "149.112.112.112", "Global • Security"),
        DnsProfile("Quad9 No Filter", "9.9.9.10", "149.112.112.10", "Global • Privacy"),
        DnsProfile("AdGuard DNS", "94.140.14.14", "94.140.15.15", "Global • Ad blocking"),
        DnsProfile("AdGuard Family", "94.140.14.15", "94.140.15.16", "Global • Family"),
        DnsProfile("Control D", "76.76.2.0", "76.76.10.0", "Global • Public"),
        DnsProfile("CleanBrowsing Security", "185.228.168.9", "185.228.169.9", "Global • Security"),
        DnsProfile("DNS.WATCH", "84.200.69.80", "84.200.70.40", "Global • Privacy"),
        DnsProfile("Verisign", "64.6.64.6", "64.6.65.6", "Global • Public"),
        DnsProfile("Mullvad", "194.242.2.2", "", "Global • Privacy"),
        DnsProfile("LibreDNS", "116.202.176.26", "", "Global • Privacy"),
        DnsProfile("DNS.SB", "185.222.222.222", "45.11.45.11", "Global • Privacy"),
    )

    fun customProfiles(): List<DnsProfile> {
        val raw = MmkvManager.decodeSettingsString(PROFILES_KEY).orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val primary = item.optString("primary")
                if (!isIpAddress(primary)) return@mapNotNull null
                DnsProfile(
                    name = item.optString("name", "Custom DNS"),
                    primary = primary,
                    secondary = item.optString("secondary"),
                    region = "Custom",
                    custom = true,
                    port = item.optInt("port", 53).takeIf { it in 1..65535 } ?: 53,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustom(profile: DnsProfile) {
        val profiles = customProfiles().filterNot { it.name.equals(profile.name, true) }.toMutableList()
        profiles.add(profile.copy(custom = true))
        val array = JSONArray()
        profiles.forEach { item ->
            array.put(JSONObject().put("name", item.name).put("primary", item.primary).put("secondary", item.secondary).put("port", item.port))
        }
        MmkvManager.encodeSettings(PROFILES_KEY, array.toString())
    }

    fun removeCustom(profile: DnsProfile) {
        val array = JSONArray()
        customProfiles().filterNot { it == profile }.forEach { item ->
            array.put(JSONObject().put("name", item.name).put("primary", item.primary).put("secondary", item.secondary).put("port", item.port))
        }
        MmkvManager.encodeSettings(PROFILES_KEY, array.toString())
    }

    fun activate(profile: DnsProfile) {
        require(profile.port == 53) { "Android DNS Changer supports standard DNS port 53 only" }
        MmkvManager.encodeSettings(PRIMARY_KEY, profile.primary)
        MmkvManager.encodeSettings(SECONDARY_KEY, profile.secondary)
        MmkvManager.encodeSettings(ENABLED_KEY, true)
    }

    fun deactivate() {
        MmkvManager.encodeSettings(ENABLED_KEY, false)
    }

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(ENABLED_KEY, false)

    fun activeProfile(): DnsProfile? {
        if (!isEnabled()) return null
        val primary = MmkvManager.decodeSettingsString(PRIMARY_KEY).orEmpty()
        if (!isIpAddress(primary)) return null
        return DnsProfile(
            "Active DNS",
            primary,
            MmkvManager.decodeSettingsString(SECONDARY_KEY).orEmpty(),
            "Active",
        )
    }

    /** Persist a bounded scan history without storing arbitrary scan input text. */
    fun saveScanHistory(results: List<DnsResolverScanResult>, timestamp: Long = System.currentTimeMillis()) {
        val history = loadScanHistory().toMutableList()
        history.add(0, DnsScanHistoryEntry(timestamp, results))
        val array = JSONArray()
        history.distinctBy { it.timestamp }.take(20).forEach { entry ->
            val resultArray = JSONArray()
            entry.results.forEach { result ->
                val checks = JSONArray()
                result.checks.forEach { check ->
                    checks.put(JSONObject()
                        .put("name", check.name)
                        .put("passed", check.passed)
                        .put("responseCode", check.responseCode ?: JSONObject.NULL)
                        .put("latencyMs", check.latencyMs ?: JSONObject.NULL)
                        .put("transport", check.transport)
                        .put("detail", check.detail.take(300)))
                }
                resultArray.put(JSONObject()
                    .put("name", result.resolver.name)
                    .put("primary", result.resolver.primary)
                    .put("secondary", result.resolver.secondary)
                    .put("port", result.resolver.port)
                    .put("region", result.resolver.region)
                    .put("custom", result.resolver.custom)
                    .put("checks", checks))
            }
            array.put(JSONObject().put("timestamp", entry.timestamp).put("results", resultArray))
        }
        MmkvManager.encodeSettings(SCAN_HISTORY_KEY, array.toString())
    }

    fun loadScanHistory(): List<DnsScanHistoryEntry> {
        val raw = MmkvManager.decodeSettingsString(SCAN_HISTORY_KEY).orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val resultArray = item.optJSONArray("results") ?: JSONArray()
                val results = (0 until resultArray.length()).mapNotNull { resultIndex ->
                    val result = resultArray.optJSONObject(resultIndex) ?: return@mapNotNull null
                    val primary = result.optString("primary")
                    if (!isIpAddress(primary)) return@mapNotNull null
                    val checksArray = result.optJSONArray("checks") ?: JSONArray()
                    val checks = (0 until checksArray.length()).mapNotNull { checkIndex ->
                        val check = checksArray.optJSONObject(checkIndex) ?: return@mapNotNull null
                        DnsScanCheck(
                            name = check.optString("name"),
                            passed = check.optBoolean("passed"),
                            responseCode = check.opt("responseCode")?.takeUnless { it == JSONObject.NULL }?.toString()?.toIntOrNull(),
                            latencyMs = check.opt("latencyMs")?.takeUnless { it == JSONObject.NULL }?.toString()?.toLongOrNull(),
                            transport = check.optString("transport"),
                            detail = check.optString("detail"),
                        )
                    }
                    DnsResolverScanResult(
                        resolver = DnsProfile(
                            name = result.optString("name", primary),
                            primary = primary,
                            secondary = result.optString("secondary"),
                            region = result.optString("region", "History"),
                            custom = result.optBoolean("custom"),
                            port = result.optInt("port", 53).takeIf { it in 1..65535 } ?: 53,
                        ),
                        checks = checks,
                    )
                }
                DnsScanHistoryEntry(item.optLong("timestamp"), results)
            }.filter { it.timestamp > 0L }
        }.getOrDefault(emptyList())
    }

    private const val SCAN_HISTORY_KEY = "dns_scanner_history_v1"
    fun isIpAddress(value: String): Boolean = runCatching {
        if (value.isBlank() || value.any { it.isWhitespace() }) return false
        if (value.contains(':')) {
            if (!value.matches(Regex("[0-9a-fA-F:.%]+"))) return false
            InetAddress.getByName(value).address.size == 16
        } else {
            val parts = value.split('.')
            parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.toIntOrNull()?.let { it in 0..255 } == true }
        }
    }.getOrDefault(false)
}

/** Sends a real DNS A query directly to the selected resolver over UDP/53. */
object DnsLatencyProbe {
    fun test(server: String, timeoutMs: Int = 1800): Long {
        require(DnsChangerStore.isIpAddress(server)) { "Invalid DNS IP address" }
        val id = Random.nextInt(0, 65536)
        val query = ByteArray(12 + 13 + 4).apply {
            this[0] = (id shr 8).toByte()
            this[1] = id.toByte()
            this[2] = 0x01
            this[5] = 0x01
            val name = "example.com"
            var offset = 12
            name.split('.').forEach { label ->
                this[offset++] = label.length.toByte()
                label.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
                offset += label.length
            }
            this[offset++] = 0
            this[offset++] = 0
            this[offset++] = 1
            this[offset++] = 0
            this[offset] = 1
        }
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(server)
            val packet = DatagramPacket(query, query.size, address, 53)
            val started = System.nanoTime()
            socket.send(packet)
            val response = ByteArray(1500)
            val reply = DatagramPacket(response, response.size)
            socket.receive(reply)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            if (reply.length < 12) error("Incomplete DNS response")
            val responseId = ((response[0].toInt() and 0xff) shl 8) or (response[1].toInt() and 0xff)
            if (responseId != id || (response[2].toInt() and 0x80) == 0) error("Invalid DNS response")
            val rcode = response[3].toInt() and 0x0f
            if (rcode != 0) error("DNS server returned error code $rcode")
            return elapsed.coerceAtLeast(1)
        }
    }
}
