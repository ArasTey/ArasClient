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
)

object DnsChangerStore {
    private const val PROFILES_KEY = "dns_changer_profiles"
    const val ENABLED_KEY = "dns_changer_enabled"
    const val PRIMARY_KEY = "dns_changer_primary"
    const val SECONDARY_KEY = "dns_changer_secondary"

    val presets = listOf(
        DnsProfile("Cloudflare", "1.1.1.1", "1.0.0.1", "Global"),
        DnsProfile("Google", "8.8.8.8", "8.8.4.4", "Global"),
        DnsProfile("Quad9", "9.9.9.9", "149.112.112.112", "Security"),
        DnsProfile("AdGuard DNS", "94.140.14.14", "94.140.15.15", "Ad blocking"),
        DnsProfile("Shecan", "178.22.122.100", "185.51.200.2", "Iran"),
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
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustom(profile: DnsProfile) {
        val profiles = customProfiles().filterNot { it.name.equals(profile.name, true) }.toMutableList()
        profiles.add(profile.copy(custom = true))
        val array = JSONArray()
        profiles.forEach { item ->
            array.put(JSONObject().put("name", item.name).put("primary", item.primary).put("secondary", item.secondary))
        }
        MmkvManager.encodeSettings(PROFILES_KEY, array.toString())
    }

    fun removeCustom(profile: DnsProfile) {
        val array = JSONArray()
        customProfiles().filterNot { it == profile }.forEach { item ->
            array.put(JSONObject().put("name", item.name).put("primary", item.primary).put("secondary", item.secondary))
        }
        MmkvManager.encodeSettings(PROFILES_KEY, array.toString())
    }

    fun activate(profile: DnsProfile) {
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

    fun isIpAddress(value: String): Boolean = runCatching {
        if (value.isBlank() || value.any { it.isWhitespace() }) return false
        val address = InetAddress.getByName(value)
        address.hostAddress.equals(value, ignoreCase = true) ||
            (address.hostAddress.substringBefore('%') == value.substringBefore('%'))
    }.getOrDefault(false)
}

/** Sends a real DNS A query directly to the selected resolver over UDP/53. */
object DnsLatencyProbe {
    fun test(server: String, timeoutMs: Int = 1800): Long {
        require(DnsChangerStore.isIpAddress(server)) { "Invalid DNS IP address" }
        val id = Random.nextInt(0, 65536)
        val query = ByteArray(12 + 2 + 4 + 4).apply {
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
