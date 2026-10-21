package com.aras.client.core.dnstunnel

/**
 * The DNS-tunnel client engines this app can run.
 *
 * Each engine is a separate Go program (the StormDNS family) that tunnels TCP
 * streams through ordinary DNS queries and answers with a local SOCKS5. The
 * programs are built from their own upstream sources and shipped with the app
 * like the aether and mieru clients already are, so a profile is dialled by
 * the app's outbound hopping to that local listener.
 *
 * The engines are separate programs with separate configuration formats, so
 * each one renders its own configuration file.
 */
enum class DnsTunnelEngine(
    /** The id stored in a profile. */
    val id: String,
    /** The program, shipped as a native library and run from the app's native library dir. */
    val binaryName: String,
    /** What the editor calls it. */
    val displayName: String,
    /** Whether the engine's configuration is JSON rather than the StormDNS TOML. */
    val jsonConfig: Boolean = false,
) {
    STORM("storm", "libstormdns_client.so", "StormDNS"),
    COTTEN("cotten", "libcottendns_client.so", "CottenDns"),
    SOAR("soar", "libsoar_client.so", "Soar", jsonConfig = true),
    ;

    companion object {
        fun of(id: String?): DnsTunnelEngine =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: STORM
    }
}
