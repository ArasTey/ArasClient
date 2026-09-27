package com.aras.client.enums

/**
 * The tunnel an Aether profile dials, named the way the aether core names it.
 *
 * The wire values are the ones aether share links and the Aether app itself use —
 * `wg`, not `wireguard` — so links move between ArasClient and PattNG unchanged.
 */
enum class AetherProtocol(val type: String) {
    MASQUE("masque"),
    WIREGUARD("wg"),
    GOOL("gool"),
    MIM("mim");

    /** Whether MASQUE carries the tunnel, which then uses the MASQUE transport, fragmentation and key. */
    val overMasque: Boolean get() = this == MASQUE || this == MIM

    /** Whether the tunnel is two hops, an outer and an inner one, in place of one endpoint. */
    val twoHops: Boolean get() = this == GOOL || this == MIM

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: WIREGUARD
    }
}

/** The HTTP version MASQUE is carried over. */
enum class AetherTransport(val type: String) {
    HTTP3("h3"),
    HTTP2("h2");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: HTTP3
    }
}

enum class AetherScanMode(val type: String) {
    TURBO("turbo"),
    BALANCED("balanced"),
    THOROUGH("thorough"),
    VERIFIED("verified"),
    IRONCLAD("ironclad");

    companion object {
        /** The name the verified mode had before aether 2.1; links from then still carry it. */
        const val STEALTH = "stealth"

        fun fromString(type: String?) =
            entries.find { it.type == type } ?: if (type == STEALTH) VERIFIED else BALANCED
    }
}

/**
 * The obfuscation profile, named the way the core names it. [AUTO] leaves the choice to
 * the core, which takes firewall for MASQUE and balanced for WireGuard and gool.
 */
enum class AetherObfuscation(val type: String) {
    AUTO("auto"),
    OFF("off"),
    LIGHT("light"),
    FIREWALL("firewall"),
    BALANCED("balanced"),
    GFW("gfw"),
    AGGRESSIVE("aggressive");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: AUTO
    }
}

/**
 * The IP versions the core scans and connects over. IPv4 unless a profile says
 * otherwise, so a profile connects on an IPv4-only network and a dual-stack one alike.
 */
enum class AetherIpVersion(val type: String) {
    V4("v4"),
    V6("v6"),
    DUAL("both");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: V4
    }
}
