package com.aras.client.dto.entities

import com.aras.client.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAffiliationInfoTest {

    @Test
    fun `connection country survives affiliation persistence`() {
        val original = ServerAffiliationInfo(
            testDelayMillis = 42L,
            countryCode = "DE",
            ipAddress = "192.0.2.1",
            countryTestedAt = 123456L,
        )

        val restored = JsonUtil.fromJsonSafe(
            JsonUtil.toJson(original),
            ServerAffiliationInfo::class.java,
        )!!

        assertEquals(42L, restored.testDelayMillis)
        assertEquals("DE", restored.countryCode)
        assertEquals("192.0.2.1", restored.ipAddress)
        assertEquals(123456L, restored.countryTestedAt)
    }

    @Test
    fun `legacy delay records remain compatible`() {
        val restored = JsonUtil.fromJsonSafe(
            "{\"testDelayMillis\":42}",
            ServerAffiliationInfo::class.java,
        )!!

        assertEquals(42L, restored.testDelayMillis)
        assertNull(restored.countryCode)
    }
}
