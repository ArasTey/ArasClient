package dev.cluvex.zedsecure.core

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import dev.cluvex.zedsecure.core.AppLog as Log
import androidx.core.app.NotificationCompat
import dev.cluvex.zedsecure.domain.model.AppSettings
import dev.cluvex.zedsecure.platform.AppInfo
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import libbox.BridgeOptions
import libbox.BridgeSession
import libbox.CommandClient
import libbox.CommandClientHandler
import libbox.CommandClientOptions
import libbox.CommandServer
import libbox.CommandServerHandler
import libbox.ConnectionEvents
import libbox.ConnectionOwner
import libbox.InterfaceUpdateListener
import libbox.Libbox
import libbox.LocalDNSTransport
import libbox.LogIterator
import libbox.NeighborUpdateListener
import libbox.NetworkInterfaceIterator
import libbox.Notification
import libbox.OutboundGroupItemIterator
import libbox.OutboundGroupIterator
import libbox.OverrideOptions
import libbox.PlatformInterface
import libbox.PlatformUser
import libbox.RoutePrefixIterator
import libbox.SetupOptions
import libbox.ShellSession
import libbox.StatusMessage
import libbox.StringIterator
import libbox.SystemProxyStatus
import libbox.TunOptions
import libbox.WIFIState

class SingBoxEngine(
    private val service: VpnService,
    private val session: String,
    private val settings: AppSettings,
    private val onLog: (String) -> Unit,
    private val onStopped: (String?) -> Unit,
) {
    private val active = AtomicBoolean(false)

    @Volatile private var server: CommandServer? = null
    @Volatile private var client: CommandClient? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile var uplinkTotal: Long = 0; private set
    @Volatile var downlinkTotal: Long = 0; private set

    val isRunning: Boolean get() = active.get()

    fun start(configJson: String) {
        setUp(service)
        val commandServer = CommandServer(serverHandler, platform)
        server = commandServer
        try {
            commandServer.start()
            commandServer.startOrReloadService(configJson, OverrideOptions())
        } catch (e: Exception) {
            stop()
            throw e
        }
        active.set(true)
        connectClient()
    }

    private fun connectClient() {
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandLog)
            statusInterval = 1_000_000_000L
        }
        val commandClient = CommandClient(clientHandler, options)
        client = commandClient

        Thread({
            runCatching { commandClient.connect() }
                .onFailure { Log.w(TAG, "command client", it) }
        }, "sing-box-client").start()
    }

    fun stop() {
        val wasActive = active.getAndSet(false)
        runCatching { client?.disconnect() }
        client = null
        server?.let { s ->
            runCatching { s.closeService() }
            runCatching { s.close() }
        }
        server = null
        stopInterfaceMonitor()
        runCatching { tun?.close() }
        tun = null
        if (wasActive) Log.i(TAG, "stopped")
    }

    fun resetNetwork() {
        runCatching { server?.resetNetwork() }
    }

    private val platform = object : PlatformInterface {
        override fun localDNSTransport(): LocalDNSTransport? = null

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun autoDetectInterfaceControl(fd: Int) {
            if (!service.protect(fd)) throw IOException("protect($fd) failed")
        }

        override fun openTun(options: TunOptions): Int = openTunInterface(options)

        override fun useProcFS(): Boolean = false

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int,
        ): ConnectionOwner {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw UnsupportedOperationException("finding a connection's app needs Android 10")
            }
            val cm = service.getSystemService(ConnectivityManager::class.java)
                ?: throw IOException("no connectivity service")
            val uid = cm.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort),
            )
            if (uid == Process.INVALID_UID) throw IOException("connection owner not found")
            val packages = service.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            return ConnectionOwner().apply {
                userId = uid
                userName = packages.firstOrNull().orEmpty()
                setAndroidPackageNames(strings(packages))
            }
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) =
            startInterfaceMonitor(listener)

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = stopInterfaceMonitor()

        override fun getInterfaces(): NetworkInterfaceIterator = networkInterfaces()

        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false

        override fun readWIFIState(): WIFIState? = null

        override fun clearDNSCache() = Unit

        override fun sendNotification(notification: Notification) = postNotification(notification)

        override fun cancelNotification(identifier: String, typeID: Int) {
            service.getSystemService(NotificationManager::class.java)?.cancel(identifier, typeID)
        }

        override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
        override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
        override fun registerMyInterface(name: String) = Unit

        override fun usePlatformShell(): Boolean = false
        override fun checkPlatformShell() = throw UnsupportedOperationException("no shell on this platform")
        override fun openShellSession(
            user: PlatformUser?,
            command: String?,
            environ: StringIterator?,
            term: String?,
            rows: Int,
            cols: Int,
        ): ShellSession = throw UnsupportedOperationException("no shell on this platform")

        override fun lookupUser(username: String?): PlatformUser = throw UnsupportedOperationException("no users")
        override fun lookupSFTPServer(): String = throw UnsupportedOperationException("no SFTP server")
        override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException("no SSH host key")

        override fun tailscaleHostname(): String = Build.MODEL.orEmpty().ifBlank { "android" }

        override fun usePlatformBridge(): Boolean = false
        override fun createBridge(options: BridgeOptions?): BridgeSession =
            throw UnsupportedOperationException("bridges need root")
    }

    private fun openTunInterface(options: TunOptions): Int {
        if (VpnService.prepare(service) != null) throw IOException("the VPN permission was revoked")
        val builder = service.Builder()
            .setSession(session.ifBlank { "ZedSecure" })
            .setMtu(options.mtu)

        options.inet4Address.forEach { builder.addAddress(it.address(), it.prefix()) }
        val hasIpv6 = options.inet6Address.hasAny()
        options.inet6Address.forEach { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            runCatching { options.dnsServerAddress.forEach { builder.addDnsServer(it) } }

            var routed4 = false
            options.inet4RouteRange.forEach { builder.addRoute(it.address(), it.prefix()); routed4 = true }
            if (!routed4) builder.addRoute("0.0.0.0", 0)
            if (hasIpv6) {
                var routed6 = false
                options.inet6RouteRange.forEach { builder.addRoute(it.address(), it.prefix()); routed6 = true }
                if (!routed6) builder.addRoute("::", 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                options.inet4RouteExcludeAddress.forEach { excludeRoute(builder, it.address(), it.prefix()) }
                options.inet6RouteExcludeAddress.forEach { excludeRoute(builder, it.address(), it.prefix()) }
            }
        } else {
            options.inet4RouteAddress.forEach { builder.addRoute(it.address(), it.prefix()) }
            options.inet6RouteAddress.forEach { builder.addRoute(it.address(), it.prefix()) }
        }

        applyAppLists(builder, options.includePackage.toList(), options.excludePackage.toList())

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bypass = options.httpProxyBypassDomain.toList()
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(options.httpProxyServer, options.httpProxyServerPort, bypass))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val pfd = builder.establish() ?: throw IOException("the VPN interface could not be established")
        tun?.close()
        tun = pfd
        return pfd.fd
    }

    private fun excludeRoute(builder: VpnService.Builder, address: String, prefix: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(address), prefix))
        }
    }

    private fun applyAppLists(builder: VpnService.Builder, include: List<String>, exclude: List<String>) {
        val self = service.packageName
        val userInclude = settings.perAppProxyEnabled && !settings.perAppBypassMode
        val userExclude = settings.perAppProxyEnabled && settings.perAppBypassMode
        val allowed = (include + if (userInclude) settings.perAppPackages else emptySet())
            .distinct()
            .filter { it != self }
        if (allowed.isNotEmpty()) {
            allowed.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
            return
        }
        val disallowed = (exclude + (if (userExclude) settings.perAppPackages else emptySet()) + self).distinct()
        disallowed.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
    }

    private fun startInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = service.getSystemService(ConnectivityManager::class.java) ?: return
        stopInterfaceMonitor()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report(cm, network, listener)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                report(cm, network, listener)

            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) =
                report(cm, network, listener)

            override fun onLost(network: Network) {
                listener.updateDefaultInterface("", -1, false, false)
            }
        }

        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
        cm.activeNetwork?.let { report(cm, it, listener) }
    }

    private fun report(cm: ConnectivityManager, network: Network, listener: InterfaceUpdateListener) {
        val lp = cm.getLinkProperties(network) ?: return
        val name = lp.interfaceName ?: return
        val index = runCatching { java.net.NetworkInterface.getByName(name)?.index }.getOrNull() ?: return
        val caps = cm.getNetworkCapabilities(network)
        val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        listener.updateDefaultInterface(name, index, expensive, false)
    }

    private fun stopInterfaceMonitor() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { service.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    @Suppress("DEPRECATION")
    private fun networkInterfaces(): NetworkInterfaceIterator {
        val cm = service.getSystemService(ConnectivityManager::class.java)
        val result = mutableListOf<libbox.NetworkInterface>()
        cm?.allNetworks?.forEach { network ->
            val lp = cm.getLinkProperties(network) ?: return@forEach
            val caps = cm.getNetworkCapabilities(network) ?: return@forEach
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@forEach
            val name = lp.interfaceName ?: return@forEach
            val ni = runCatching { java.net.NetworkInterface.getByName(name) }.getOrNull() ?: return@forEach
            result += libbox.NetworkInterface().apply {
                index = ni.index
                mtu = lp.mtu.takeIf { it > 0 } ?: runCatching { ni.mtu }.getOrDefault(1500)
                this.name = name
                addresses = strings(lp.linkAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" })
                flags = linkFlags(ni)
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                dnsServer = strings(lp.dnsServers.mapNotNull { it.hostAddress })
                gateway = strings(lp.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress })
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        val iterator = result.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): libbox.NetworkInterface = iterator.next()
        }
    }

    private fun linkFlags(ni: java.net.NetworkInterface): Int = runCatching {
        var flags = 0
        if (ni.isUp) flags = flags or IFF_UP or IFF_RUNNING
        if (ni.isLoopback) flags = flags or IFF_LOOPBACK
        if (ni.isPointToPoint) flags = flags or IFF_POINTOPOINT
        if (ni.supportsMulticast()) flags = flags or IFF_MULTICAST
        if (ni.interfaceAddresses.any { it.broadcast != null }) flags = flags or IFF_BROADCAST
        flags
    }.getOrDefault(0)

    private fun postNotification(notification: Notification) {
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        val built = NotificationCompat.Builder(service, VpnNotifications.CHANNEL_ID)
            .setSmallIcon(dev.cluvex.zedsecure.R.drawable.ic_tile_zed)
            .setContentTitle(notification.title)
            .setContentText(notification.body.ifBlank { notification.subtitle })
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setAutoCancel(true)
            .build()
        manager.notify(notification.identifier, notification.typeID, built)
    }

    private val serverHandler = object : CommandServerHandler {
        override fun serviceStop() {
            if (active.get()) onStopped(null)
        }

        override fun serviceReload() = Unit
        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
        override fun setSystemProxyEnabled(enabled: Boolean) = Unit
        override fun triggerNativeCrash() = Unit
        override fun writeDebugMessage(message: String) { Log.d(TAG, message) }
        override fun connectSSHAgent(): Int = throw UnsupportedOperationException("no SSH agent")
    }

    private val clientHandler = object : CommandClientHandler {
        override fun connected() = Unit
        override fun disconnected(message: String?) {
            if (!message.isNullOrBlank()) Log.i(TAG, "command client: $message")
        }

        override fun clearLogs() = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
        override fun updateClashMode(newMode: String?) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
        override fun writeGroups(groups: OutboundGroupIterator?) = Unit
        override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) = Unit

        override fun writeLogs(messageList: LogIterator?) {
            val logs = messageList ?: return
            while (logs.hasNext()) {
                val entry = logs.next() ?: continue
                onLog("${levelChar(entry.level)}/sing-box: ${entry.message}")
            }
        }

        override fun writeStatus(message: StatusMessage?) {
            val status = message ?: return
            uplinkTotal = status.uplinkTotal
            downlinkTotal = status.downlinkTotal
        }
    }

    companion object {
        private const val TAG = "SingBoxEngine"

        private const val IFF_UP = 0x1
        private const val IFF_BROADCAST = 0x2
        private const val IFF_LOOPBACK = 0x8
        private const val IFF_POINTOPOINT = 0x10
        private const val IFF_RUNNING = 0x40
        private const val IFF_MULTICAST = 0x1000

        private val setUpDone = AtomicBoolean(false)

        private fun setUp(context: Context) {
            if (!setUpDone.compareAndSet(false, true)) return
            val base = File(context.filesDir, "sing-box").apply { mkdirs() }
            Libbox.setup(SetupOptions().apply {
                basePath = base.absolutePath
                workingPath = File(base, "work").apply { mkdirs() }.absolutePath
                tempPath = File(context.cacheDir, "sing-box").apply { mkdirs() }.absolutePath

                fixAndroidStack = true
                logMaxLines = 3000L
                crashReportSource = "zedsecure"
                appVersion = AppInfo.versionName
            })
        }

        private fun levelChar(level: Int): String = when (level) {
            0, 1, 2 -> "E"
            3 -> "W"
            4 -> "I"
            else -> "D"
        }

        private fun strings(values: List<String>): StringIterator {
            val iterator = values.iterator()
            return object : StringIterator {
                override fun hasNext(): Boolean = iterator.hasNext()
                override fun len(): Int = values.size
                override fun next(): String = iterator.next()
            }
        }

        private fun StringIterator?.toList(): List<String> {
            val it = this ?: return emptyList()
            val out = mutableListOf<String>()
            while (it.hasNext()) it.next()?.let { v -> if (v.isNotBlank()) out += v }
            return out
        }

        private inline fun StringIterator?.forEach(action: (String) -> Unit) = toList().forEach(action)

        private inline fun RoutePrefixIterator?.forEach(action: (libbox.RoutePrefix) -> Unit) {
            val it = this ?: return
            while (it.hasNext()) it.next()?.let(action)
        }

        private fun RoutePrefixIterator?.hasAny(): Boolean = this?.hasNext() == true
    }
}
