package com.aras.client.util

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class CountryResolverTest {

    @Test
    fun `card country comes only from a real connection test`() {
        // The card calls isoForCountry(testCountryCode) directly: no measured
        // country means no flag, whatever the name or the host GeoIP claims.
        assertEquals(null, CountryResolver.isoForCountry(null))
        assertEquals(null, CountryResolver.isoForCountry(""))
        assertEquals("DE", CountryResolver.isoForCountry("DE"))
        assertEquals("DE", CountryResolver.isoForCountry("Germany"))
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
