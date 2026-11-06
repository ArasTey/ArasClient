package dev.cluvex.zedsecure.core.tor

import dev.cluvex.zedsecure.domain.model.AppSettings
import java.io.File

object TorConfigBuilder {
    const val SOCKS_PORT = dev.cluvex.zedsecure.domain.config.LocalPorts.TOR_SOCKS

    data class Transports(
        val obfs: String?,
        val snowflake: String?,
        val conjure: String?,
        val dnstt: String?,
    ) {
        companion object {
            fun android(nativeLibDir: String) = Transports(
                obfs = "$nativeLibDir/libobfs4proxy.so",
                snowflake = "$nativeLibDir/libsnowflake.so",
                conjure = "$nativeLibDir/libconjure.so",
                dnstt = "$nativeLibDir/libdnstt.so",
            )
        }
    }

    private val PROXY_CAPABLE_TRANSPORTS = setOf("obfs4", "obfs3", "scramblesuit", "meek_lite", "webtunnel", "snowflake", "vanilla")

    fun supportsUpstreamProxy(settings: AppSettings): Boolean =
        settings.torBridgesMode == "none" || settings.torBridgeTransport in PROXY_CAPABLE_TRANSPORTS

    fun build(
        settings: AppSettings,
        nativeLibDir: String,
        torDir: File,
        dataDir: File,
        upstreamSocksPort: Int? = null,
        transports: Transports = Transports.android(nativeLibDir),
        dnsPort: Int? = null,
        socksPort: Int = SOCKS_PORT,
    ): String =
        buildString {
            val isolation = buildString {
                if (settings.torIsolateDestAddr) append(" IsolateDestAddr")
                if (settings.torIsolateDestPort) append(" IsolateDestPort")
            }
            appendLine("SocksPort 127.0.0.1:$socksPort$isolation")
            appendLine("DNSPort ${dnsPort?.let { "127.0.0.1:$it" } ?: "0"}")
            appendLine("RunAsDaemon 0")
            appendLine("ClientOnly 1")
            appendLine("Schedulers Vanilla")
            appendLine("DataDirectory ${dataDir.absolutePath}")
            appendLine("GeoIPFile ${File(torDir, "geoip").absolutePath}")
            appendLine("GeoIPv6File ${File(torDir, "geoip6").absolutePath}")
            appendLine("Log notice stdout")
            appendLine("DormantCanceledByStartup 1")
            appendLine("AutomapHostsOnResolve 1")
            appendLine("CircuitsAvailableTimeout 86400")

            if (upstreamSocksPort != null && upstreamSocksPort > 0 && supportsUpstreamProxy(settings)) {
                appendLine("Socks5Proxy 127.0.0.1:$upstreamSocksPort")
            }

            appendLine("VirtualAddrNetwork ${settings.torVirtualAddrNetwork}")
            appendLine("HardwareAccel ${settings.torHardwareAccel.b}")
            appendLine("AvoidDiskWrites ${settings.torAvoidDiskWrites.b}")
            appendLine("ConnectionPadding ${settings.torConnectionPadding.b}")
            appendLine("ReducedConnectionPadding ${settings.torReducedConnectionPadding.b}")
            if (settings.torFascistFirewall) appendLine("FascistFirewall 1")
            appendLine("NewCircuitPeriod ${settings.torNewCircuitPeriod}")
            appendLine("MaxCircuitDirtiness ${settings.torMaxCircuitDirtiness}")
            appendLine("DormantClientTimeout ${settings.torDormantClientTimeout.coerceAtLeast(10)} minutes")
            appendLine("EnforceDistinctSubnets ${settings.torEnforceDistinctSubnets.b}")
            if (settings.torTrackHostExits) appendLine("TrackHostExits .")
            appendLine("ClientUseIPv4 ${settings.torClientUseIPv4.b}")
            appendLine("ClientUseIPv6 ${settings.torClientUseIPv6.b}")

            node("EntryNodes", settings.torEntryNodes)
            node("ExitNodes", settings.torExitNodes)
            node("ExcludeNodes", settings.torExcludeNodes)
            node("ExcludeExitNodes", settings.torExcludeExitNodes)
            appendLine("StrictNodes ${if (settings.torStrictNodes) 1 else 0}")

            appendBridges(settings, transports, torDir)
        }

    private val Boolean.b: Int get() = if (this) 1 else 0

    private fun StringBuilder.node(directive: String, codes: String) {
        val v = codes.trim()
        if (v.isNotEmpty()) appendLine("$directive $v")
    }

    private fun StringBuilder.appendBridges(settings: AppSettings, transports: Transports, torDir: File) {
        if (settings.torBridgesMode == "none") {
            appendLine("UseBridges 0")
            return
        }
        appendLine("UseBridges 1")

        transports.obfs?.let { appendLine("ClientTransportPlugin obfs4,obfs3,scramblesuit,meek_lite,webtunnel exec $it") }
        transports.snowflake?.let { appendLine("ClientTransportPlugin snowflake exec $it") }
        transports.conjure?.let { appendLine("ClientTransportPlugin conjure exec $it") }
        transports.dnstt?.let { appendLine("ClientTransportPlugin dnstt exec $it") }

        val transport = settings.torBridgeTransport
        val lines = if (settings.torBridgesMode == "own") {
            settings.torOwnBridges.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        } else if (transport == "snowflake") {
            SnowflakeConfigurator.bridgeLines(settings.torSnowflakeRendezvous, settings.torSnowflakeStun)
        } else {
            val defaults = defaultBridges(torDir, transport)
            val enabled = settings.torEnabledBridges.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val chosen = if (enabled.isEmpty()) defaults.take(8) else defaults.filter { it in enabled }
            chosen.ifEmpty { defaults.take(8) }
        }
        lines.forEach { appendLine("Bridge $it") }
    }

    private fun defaultBridges(torDir: File, transport: String): List<String> {
        val all = runCatching { File(torDir, "bridges_default.lst").readLines() }.getOrDefault(emptyList())
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        return if (transport == "vanilla") {
            all.filter { line ->
                val first = line.substringBefore(' ')
                first.contains(':') && !line.substringBefore(' ').any { it.isLetter() }
            }
        } else {
            all.filter { it.startsWith("$transport ") }
        }
    }
}
