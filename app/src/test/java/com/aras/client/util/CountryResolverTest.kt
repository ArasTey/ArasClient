package com.aras.client.util

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class CountryResolverTest {

    @Test
    fun `server ip country overrides provider country on cards`() {
        val profile = ProfileItem.create(EConfigType.VMESS).apply {
            remarks = "Germany"
            server = "node.example"
        }

        assertEquals("NL", CountryResolver.resolveCardIso(profile, geoIso = "NL", testedCountry = null))
    }

    @Test
    fun `actual connection ping country overrides host geoip on card`() {
        val profile = ProfileItem.create(EConfigType.VMESS).apply {
            remarks = "Germany"
            server = "node.example"
        }

        assertEquals(
            "DE",
            CountryResolver.resolveCardIso(
                profile = profile,
                geoIso = "NL",
                testedCountry = "DE",
            )
        )
    }

    @Test
    fun `provider country remains fallback when geoip is unavailable`() {
        val profile = ProfileItem.create(EConfigType.VMESS).apply {
            remarks = "Germany"
            server = "node.example"
        }

        assertEquals("DE", CountryResolver.resolveCardIso(profile, geoIso = "", testedCountry = null))
    }

    @Test
    fun `invalid geoip does not create malformed flag`() {
        val profile = ProfileItem.create(EConfigType.VMESS).apply {
            remarks = "United States"
            server = "node.example"
        }

        assertEquals("US", CountryResolver.resolveCardIso(profile, geoIso = "12", testedCountry = null))
    }

    @Test
    fun `embedded provider flags are removed from card titles`() {
        // withoutFlags stays for card titles (the card adds its own resolved flag,
        // so a stored flag would render twice). Import no longer strips remarks.
        assertEquals("ArasClient", CountryResolver.withoutFlags("🇹🇷 ArasClient"))
        assertEquals("US node", CountryResolver.withoutFlags("🇺🇸 US 🇩🇪 node"))
    }

    @Test
    fun `ping country accepts code name and existing flag`() {
        assertEquals("DE", CountryResolver.isoForCountry("DE"))
        assertEquals("DE", CountryResolver.isoForCountry("Germany"))
        assertEquals("DE", CountryResolver.isoForCountry("🇩🇪"))
        assertEquals(null, CountryResolver.isoForCountry("12"))
    }

    @Test
    fun `remarks imported with a flag keep the flag`() {
        // The card resolves its own flag; the stored remark must keep what the
        // provider wrote. (AngConfigManager no longer calls withoutFlags on import.)
        val profile = ProfileItem.create(EConfigType.VMESS).apply {
            remarks = "🇩🇪 Frankfurt 01"
            server = "node.example"
        }

        assertEquals("🇩🇪 Frankfurt 01", profile.remarks)
    }
}
