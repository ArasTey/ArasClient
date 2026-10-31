package com.aras.client.util

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class FreePortTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun pickReturnsAPortThatCanBeBound() {
        val port = FreePort.pick()
        assertTrue(port in 1..65535)
        ServerSocket(port, 1, loopback).use { }
    }

    @Test
    fun pickLeavesTakenPortsAlone() {
        val taken = (20000..20050).toSet()
        repeat(50) {
            assertTrue(FreePort.pick(taken) !in taken)
        }
    }

    @Test
    fun pickAvoidsAPortAnotherProcessHolds() {
        ServerSocket(0, 1, loopback).use { held ->
            assertNotEquals(held.localPort, FreePort.pick(setOf(held.localPort)))
        }
    }
}
