package com.aras.client.ui.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsScanInputTest {
    @Test
    fun parsesSingleRangeAndPort() {
        val p = DnsScanInput.parse("9.9.9.9\n1.1.1.1-1.1.1.3, 1.2.3.4:53")
        assertEquals(listOf("9.9.9.9", "1.1.1.1", "1.1.1.2", "1.1.1.3", "1.2.3.4"), p.ips)
        assertTrue(p.invalid.isEmpty())
    }

    @Test
    fun parsesShortRangeAndCidr() {
        assertEquals(listOf("5.5.5.1", "5.5.5.2"), DnsScanInput.parse("5.5.5.1-2").ips)
        val c = DnsScanInput.parse("8.8.8.0/30").ips
        assertEquals(listOf("8.8.8.1", "8.8.8.2"), c)
    }

    @Test
    fun reportsInvalidAndDeduplicates() {
        val p = DnsScanInput.parse("1.1.1.1 1.1.1.1 foo 300.1.1.1")
        assertEquals(listOf("1.1.1.1"), p.ips)
        assertEquals(listOf("foo", "300.1.1.1"), p.invalid)
    }

    @Test
    fun capsTargets() {
        val p = DnsScanInput.parse("10.0.0.0/16", limit = 100)
        assertEquals(100, p.ips.size)
        assertTrue(p.truncated)
    }
}
