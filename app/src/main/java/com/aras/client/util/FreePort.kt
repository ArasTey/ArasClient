package com.aras.client.util

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.random.Random

/**
 * Picks a loopback port that is free right now. A Tor or Psiphon profile runs
 * its carrier on a listener of its own, so the profile can name that listener
 * up front instead of leaving every carrier on the app's one default port.
 */
object FreePort {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    /** A free loopback port, never one of [taken]. */
    fun pick(taken: Set<Int> = emptySet()): Int {
        // An ephemeral bind is the cheapest proof that a port is free.
        repeat(EPHEMERAL_ATTEMPTS) {
            val port = ephemeral()
            if (port != null && port !in taken) return port
        }
        repeat(RANDOM_ATTEMPTS) {
            val port = Random.nextInt(PORT_MIN, PORT_MAX + 1)
            if (port !in taken && canBind(port)) return port
        }
        return FALLBACK_PORT
    }

    private fun ephemeral(): Int? =
        runCatching { ServerSocket(0, 1, loopback).use { it.localPort } }.getOrNull()

    private fun canBind(port: Int): Boolean =
        runCatching { ServerSocket(port, 1, loopback).use { true } }.getOrDefault(false)

    private const val EPHEMERAL_ATTEMPTS = 8
    private const val RANDOM_ATTEMPTS = 24
    private const val PORT_MIN = 20000
    private const val PORT_MAX = 60000
    private const val FALLBACK_PORT = 10819
}
