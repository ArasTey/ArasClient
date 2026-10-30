package com.aras.client.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which geo entries a rule can keep given the data installed on the device.
 *
 * Xray aborts the config when a rule names a code the file does not carry, so
 * presets must be checked against the real file. The Iran source has no gfw,
 * Loyalsoldier has no phishing, and both have google — the behaviour these pin.
 */
class GeoDataCodesTest {

    private fun varint(value: Int): ByteArray {
        var rest = value
        val out = ArrayList<Byte>()
        while (true) {
            if (rest and 0x7F.inv() == 0) {
                out.add(rest.toByte())
                break
            }
            out.add(((rest and 0x7F) or 0x80).toByte())
            rest = rest ushr 7
        }
        return out.toByteArray()
    }

    private fun field(number: Int, payload: ByteArray): ByteArray =
        varint((number shl 3) or 2) + varint(payload.size) + payload

    private fun text(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    private fun entry(code: String, vararg domains: String): ByteArray {
        var payload = field(1, text(code))
        domains.forEach { payload += field(2, field(2, text(it))) }
        return payload
    }

    private fun list(vararg entries: ByteArray): ByteArray =
        entries.fold(ByteArray(0)) { acc, item -> acc + field(1, item) }

    @Test
    fun `codes are read from a geosite list`() {
        val data = list(
            entry("IR", "example.ir", "shop.example.ir"),
            entry("GOOGLE"),
        )

        assertEquals(setOf("ir", "google"), GeoDataCodes.parse(data))
    }

    @Test
    fun `rule tags match the installed codes ignoring case`() {
        assertEquals(
            listOf("geosite:Google"),
            GeoDataCodes.keepAvailableGeosite(listOf("geosite:Google"), setOf("google")),
        )
    }

    @Test
    fun `only the absent codes are dropped`() {
        val kept = GeoDataCodes.keepAvailableGeosite(
            listOf("geosite:gfw", "geosite:greatfire", "domain:example.com"),
            setOf("greatfire", "google"),
        )

        assertEquals(listOf("geosite:greatfire", "domain:example.com"), kept)
    }

    @Test
    fun `a rule whose every geo entry is missing disappears`() {
        assertNull(
            GeoDataCodes.keepAvailableGeosite(
                listOf("geosite:phishing", "geosite:malware"),
                setOf("google"),
            ),
        )
    }

    @Test
    fun `attribute selectors are checked on the code alone`() {
        assertEquals(
            listOf("geosite:google@cn"),
            GeoDataCodes.keepAvailableGeosite(listOf("geosite:google@cn"), setOf("google")),
        )
    }

    @Test
    fun `unknown availability leaves rules untouched`() {
        assertEquals(
            listOf("geosite:gfw"),
            GeoDataCodes.keepAvailableGeosite(listOf("geosite:gfw"), null),
        )
        assertEquals(
            listOf("geoip:facebook"),
            GeoDataCodes.keepAvailableGeoip(listOf("geoip:facebook"), null),
        )
    }

    @Test
    fun `ip entries are judged against the geoip file`() {
        val kept = GeoDataCodes.keepAvailableGeoip(
            listOf("geoip:facebook", "ext:geoip-only-cn-private.dat:cn", "1.1.1.1"),
            setOf("google", "cn"),
        )

        assertEquals(listOf("ext:geoip-only-cn-private.dat:cn", "1.1.1.1"), kept)
    }
}
