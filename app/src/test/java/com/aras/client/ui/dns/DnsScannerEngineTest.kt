package com.aras.client.ui.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsScannerEngineTest {
    @Test
    fun buildsAQueryWithRecursionDesired() {
        val packet = DnsScannerEngine.buildQuery(0x1234, "example.com", 1)
        assertEquals(0x12, packet[0].toInt() and 0xff)
        assertEquals(0x34, packet[1].toInt() and 0xff)
        assertEquals(0x01, packet[2].toInt() and 0xff)
        assertEquals(1, packet[5].toInt() and 0xff)
        assertEquals(1, packet[packet.size - 3].toInt() and 0xff)
    }

    @Test
    fun addsEdnsOptRecord() {
        val packet = DnsScannerEngine.buildQuery(7, "example.com", 28, edns = true)
        assertEquals(1, packet[11].toInt() and 0xff)
        assertEquals(0, packet[packet.size - 11].toInt() and 0xff)
        assertEquals(41, packet[packet.size - 9].toInt() and 0xff)
    }

    @Test
    fun validatesNormalAndNxDomainResponses() {
        val normal = ByteArray(12).apply {
            this[0] = 0x12
            this[1] = 0x34
            this[2] = 0x80.toByte()
        }
        assertEquals(0, DnsScannerEngine.parseResponse(normal, 0x1234))
        val nxdomain = normal.copyOf().apply { this[3] = 3 }
        assertEquals(3, DnsScannerEngine.parseResponse(nxdomain, 0x1234, true))
    }

    @Test
    fun rejectsMismatchedIdsAndUnexpectedRcodes() {
        val response = ByteArray(12).apply {
            this[0] = 1
            this[2] = 0x80.toByte()
        }
        assertFalse(runCatching { DnsScannerEngine.parseResponse(response, 2) }.isSuccess)
        response[3] = 2
        assertFalse(runCatching { DnsScannerEngine.parseResponse(response, 1) }.isSuccess)
    }

    @Test
    fun rejectsMalformedDomainNames() {
        assertFalse(runCatching {
            DnsScannerEngine.buildQuery(1, "bad..example", 1)
        }.isSuccess)
        assertTrue(runCatching {
            DnsScannerEngine.buildQuery(1, "example.com", 16)
        }.isSuccess)
    }
}
