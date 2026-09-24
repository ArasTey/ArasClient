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

        assertEquals("🇳🇱", CountryResolver.resolve(profile, geoIso = "NL", preferGeoIp = true))
    }

    @Test
    fun `actual connection ping country overrides host geoip on card`() {
        val profile = ProfileItem.create(EConfigType.VMESS).apply {
            remarks = "Germany"
            server = "node.example"
        }

        assertEquals(
            "🇩🇪",
            CountryResolver.resolveCardCountry(
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

        assertEquals("🇩🇪", CountryResolver.resolve(profile, geoIso = "", preferGeoIp = true))
    }

    @Test
    fun `invalid geoip does not create malformed flag`() {
        val profile = ProfileItem.create(EConfigType.VMESS).apply {
            remarks = "United States"
            server = "node.example"
        }

        assertEquals("🇺🇸", CountryResolver.resolve(profile, geoIso = "12", preferGeoIp = true))
    }

    @Test
    fun `embedded provider flags are removed from card titles`() {
        assertEquals("ArasClient", CountryResolver.withoutFlags("🇹🇷 ArasClient"))
        assertEquals("US node", CountryResolver.withoutFlags("🇺🇸 US 🇩🇪 node"))
    }

    @Test
    fun `ping country accepts code name and existing flag`() {
        assertEquals("🇩🇪", CountryResolver.flagForCountry("DE"))
        assertEquals("🇩🇪", CountryResolver.flagForCountry("Germany"))
        assertEquals("🇩🇪", CountryResolver.flagForCountry("🇩🇪"))
        assertEquals("", CountryResolver.flagForCountry("12"))
    }
}
