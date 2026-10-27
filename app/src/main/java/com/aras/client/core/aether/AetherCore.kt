package com.aras.client.core.aether

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherProtocol
import com.aras.client.enums.AetherPsiphon
import com.aras.client.enums.AetherTor

/**
 * The aether core a profile runs on, as the arguments its process is started with.
 *
 * The core is a separate process that dials the tunnel itself and exposes a local SOCKS5
 * listener; the app's own outbound is a SOCKS hop to that listener. The core therefore
 * holds the credentials, and the profile carries only the settings and the gateway.
 */
data class AetherCore(val arguments: List<String>) {

    /** The loopback port the app dials. */
    val port: Int get() = AetherCoreManager.listenerPortOf(arguments) ?: AetherCoreManager.socksPort

    /**
     * Every port the core listens on as its modes run, its HTTP proxies' too, see [boundListeners]: the ports it is
     * told, and its defaults for the listeners it is not told about; an inbound of the configuration cannot share one.
     */
    val ports: List<Int>
        get() = portsOf(boundListeners).distinct()

    /**
     * The first port two of the listeners the core holds at one time share, as a command written by hand may name;
     * null when each has a port of its own. The core would bind one of them and fail on the other. Ports are told
     * apart by number, as everywhere else in the app. Tor or Psiphon alone lets the core's own HTTP proxy go before
     * that carrier's is bound, so those two may share a port, see [listenerGroups].
     */
    val sharedPort: Int?
        get() = listenerGroups.firstNotNullOfOrNull { group ->
            val seen = mutableSetOf<Int>()
            portsOf(group).firstOrNull { !seen.add(it) }
        }

    /**
     * The first option of a listener the core binds whose address the core does not take; null when it takes each. The
     * core reads each as an IP address and a port, and where it cannot read one it quietly listens on its default, for
     * its own, Tor's or Psiphon's listener, or not at all, for an HTTP proxy, while the app would go by the address
     * written (aether lib.rs, tor.rs and psiphon.rs). A blank address, or "off", turns an HTTP proxy off. A message
     * names the option, not the address, which is free text the log must not carry.
     */
    val invalidListener: String?
        get() = boundListeners.firstOrNull { flag ->
            val address = AetherCoreManager.listenerAddressAfter(arguments, flag) ?: return@firstOrNull false
            !AetherCoreManager.isSocketAddress(address) &&
                !(flag in HTTP_LISTENERS && (address.isBlank() || address.trim(AetherCoreManager::isRustWhitespace) == "off"))
        }

    /**
     * The option of the listener the app dials, see [AetherCoreManager.listenerFlagOf], when the core takes its address
     * but the app does not reach it there; null when it does, or when the command names none and the app gives it one.
     * The app dials the core on 127.0.0.1 (the Xray outbound, the readiness probe, the port checks), which a listener
     * on 0.0.0.0 or :: answers as well, see [AetherCoreManager.reachesLoopback], but one on 127.0.0.2, ::1 or a network's
     * address does not: the session would stay connecting forever.
     */
    val unreachableListener: String?
        get() {
            val flag = AetherCoreManager.listenerFlagOf(arguments)
            val address = AetherCoreManager.listenerAddressAfter(arguments, flag) ?: return null
            if (!AetherCoreManager.isSocketAddress(address)) return null
            return flag.takeUnless { AetherCoreManager.reachesLoopback(address.substringBeforeLast(':')) }
        }

    /**
     * The ports of [listeners], in their order, but 0, which asks for any port: the port the arguments name, or the
     * core's default for a listener they leave out, see [AetherCoreManager.defaultListenerPorts]. A command written by
     * hand that names only Tor's listener still has the core's own on 1819.
     */
    private fun portsOf(listeners: List<String>): List<Int> =
        listeners.mapNotNull { flag ->
            if (flag in arguments) AetherCoreManager.portAfter(arguments, flag) else AetherCoreManager.defaultListenerPorts[flag]
        }.filter { it != 0 }

    /** Every listener option the core binds as [arguments] run it, in the order of [LISTENERS], see [listenerGroups]. */
    private val boundListeners: List<String>
        get() {
            val bound = listenerGroups.flatten().toSet()
            return LISTENERS.filter { it in bound }
        }

    /**
     * The listener options the core binds as [arguments] run it, in the groups it holds at one time: its own and its
     * HTTP proxy always, Tor's listener with Tor inside or around the tunnel and Tor's HTTP proxy with Tor run in any
     * way, and the same for Psiphon, all of them for the whole run. An option of a mode that does not run binds
     * nothing (aether lib.rs, tor.rs and psiphon.rs). Tor or Psiphon alone, see [AetherCoreManager.aloneOf]: the core
     * binds its own listener and its own HTTP proxy as it starts, then lets that HTTP proxy go and serves its own
     * listener with Tor or Psiphon, which binds that carrier's HTTP proxy (lib.rs, Listeners::bind and into_socks).
     */
    private val listenerGroups: List<List<String>>
        get() {
            val tor = AetherCoreManager.torModeOf(arguments)
            val psiphon = AetherCoreManager.psiphonModeOf(arguments)
            if (AetherCoreManager.aloneOf(arguments) != null) {
                val aloneHttp = if (tor == AetherTor.ONLY) AetherCoreManager.TOR_HTTP else AetherCoreManager.PSIPHON_HTTP
                return listOf(listOf("--bind", AetherCoreManager.HTTP_PROXY), listOf("--bind", aloneHttp))
            }
            return listOf(LISTENERS.filter { flag ->
                when (flag) {
                    AetherCoreManager.TOR_BIND -> tor == AetherTor.CHAIN || tor == AetherTor.REVERSE
                    AetherCoreManager.TOR_HTTP -> tor != AetherTor.OFF
                    AetherCoreManager.PSIPHON_BIND -> psiphon == AetherPsiphon.CHAIN || psiphon == AetherPsiphon.REVERSE
                    AetherCoreManager.PSIPHON_HTTP -> psiphon != AetherPsiphon.OFF
                    else -> true
                }
            })
        }

    /** The protocol, which tells whose identity files the core uses. */
    val protocol: AetherProtocol get() = AetherCoreManager.protocolOf(arguments)

    /** The command line this core is, as the Aether app shows it under Advanced settings. */
    val command: String
        get() = (listOf(AetherCoreManager.COMMAND_NAME) + arguments)
            .joinToString(" ", transform = ::quoted)

    /** This core dialled on [port] instead — a latency test opens its own listener. */
    fun on(port: Int): AetherCore = AetherCore(
        AetherCoreManager.withListener(arguments, port)
    )

    companion object {

        /** The name a command line starts with; the app runs its own copy of the core whatever the name says. */
        const val COMMAND_NAME = "aether"

        /**
         * The listeners a core may be told to bind: the core's own, Tor's, Psiphon's, and the HTTP proxies a command
         * written by hand may open beside them.
         */
        private val LISTENERS = listOf(
            "--bind",
            AetherCoreManager.TOR_BIND,
            AetherCoreManager.PSIPHON_BIND,
            AetherCoreManager.HTTP_PROXY,
            AetherCoreManager.TOR_HTTP,
            AetherCoreManager.PSIPHON_HTTP,
        )

        /** The HTTP proxies among [LISTENERS], which a blank address or "off" turns off. */
        private val HTTP_LISTENERS = setOf(AetherCoreManager.HTTP_PROXY, AetherCoreManager.TOR_HTTP, AetherCoreManager.PSIPHON_HTTP)

        /**
         * The core of [profile]: the command line the profile carries in their place,
         * or its settings as arguments on its listen port.
         */
        fun of(profile: ProfileItem): AetherCore =
            profile.aetherCommand?.takeIf { it.isNotBlank() }?.let(::ofCommand)
                ?: AetherCore(
                    AetherCoreManager.withoutOption(
                        AetherCoreManager.buildArguments(profile, AetherCoreManager.listenPort(profile)),
                        "--log-level",
                    )
                )

        /**
         * The core a hand-written command line asks for, or null when it is not one we
         * can run.
         *
         * The program is dropped: the app runs its own copy of the core whatever the
         * name says, so only the arguments matter. A command with no arguments is not
         * one - it would start a core on the default port, which is the very collision
         * the profile's own port is there to avoid.
         */
        fun ofCommand(command: String): AetherCore? {
            val parts = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            if (parts.none { it.startsWith("--") }) return null
            return AetherCore(parts.drop(1))
        }

        private fun quoted(argument: String): String =
            if (argument.isEmpty() || argument.any { it.isWhitespace() || it == '"' }) {
                "\"" + argument.replace("\"", "\\\"") + "\""
            } else {
                argument
            }
    }
}
