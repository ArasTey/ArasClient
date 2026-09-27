package com.aras.client.core.aether

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherProtocol

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
