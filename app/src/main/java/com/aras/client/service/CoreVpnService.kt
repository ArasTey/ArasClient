package com.aras.client.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import com.aras.client.AppConfig
import com.aras.client.util.ConnectionStatsManager
import com.aras.client.AppConfig.LOOPBACK
import com.aras.client.BuildConfig
import com.aras.client.contracts.ServiceControl
import com.aras.client.TProxyService
import com.aras.client.contracts.Tun2SocksControl
import com.aras.client.core.CoreServiceManager
import com.aras.client.handler.AppLocaleManager
import com.aras.client.handler.MmkvManager
import com.aras.client.handler.NotificationManager
import com.aras.client.handler.SettingsManager
import com.aras.client.root.RootLanSharing
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)

    /** Set when a stop arrives mid-start; the setup thread finishes the teardown. */
    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopXray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // Ensure VPN interface is properly closed when the service is destroyed without
        // going through stopAllService() (e.g. when killed unexpectedly). isRunning is
        // set to false at the start of stopAllService(), so this guard prevents a double-close.
        if (isRunning) {
            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed in onDestroy")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface in onDestroy", e)
            }
            // Destroyed without stopAllService(): the core and its aether/Tor helpers must
            // end here too, or they keep their listeners and the next connect finds the
            // daemon "running" with nothing able to stop it.
            isRunning = false
            CoreServiceManager.stopCoreLoop()
        }

        unlockStart()
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        // Always-on VPN restarts from OS deliver intent.action == SERVICE_INTERFACE or null intent.
        // Reset any stuck start lock left by a killed process to allow setupVpnService() to run.
        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
        // A restart intent from the OS (or a duplicate start command) must not
        // release the lock while a start is already running — doing so let a
        // second start race the first and tear it down mid-setup.
        if (isSystemVpnStart && !isStartingLock.get()) {
            unlockStart()
        }
        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
            return START_NOT_STICKY
        }
        stopRequested = false
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")
        // Setup resolves server DNS and initializes the native core, which can
        // block for seconds on unreachable hosts; keep it off the main thread
        // so the system does not ANR the service while it starts.
        Thread {
            if (!setupVpnService()) {
                unlockStart()
                // Stop service if setup fails to avoid infinite restart loops (START_STICKY)
                stopSelf()
                return@Thread
            }
            if (stopRequested) {
                // The stop arrived while the interface was being set up. Close what the
                // setup just created instead of starting a core for a cancelled session,
                // or the interface would outlive the stop.
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: Stop requested during setup")
                isRunning = false
                try {
                    if (::mInterface.isInitialized) mInterface.close()
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: Failed to close interface after stop", e)
                }
                unlockStart()
                stopSelf()
                return@Thread
            }
            startService()
        }.start()
        return START_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }

        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        runTun2socks()
        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        // The builder's set-up from the settings sits in the try too, so that a setting the
        // builder refuses, or one that cannot be read, ends the start like a failed establish
        // rather than escaping onStartCommand and ending the process.
        try {
            val ipv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED, AppConfig.DEFAULT_IPV6_ENABLED)
            val builder = Builder()

            // Configure network settings (addresses, routing and DNS)
            configureNetworkSettings(builder, ipv6)

            // Configure app-specific settings (session name and per-app proxy)
            configurePerAppProxy(builder)

            // Close the old interface since the parameters have been changed
            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: closing the old VPN interface failed", e)
            }

            // Configure platform-specific features
            configurePlatformFeatures(builder)

            // Create a new interface using the builder and save the parameters
            mInterface = establishFallingBackToIpv4(
                ipv6,
                { builder.establish()!! },
                ::establishWithoutIpv6,
            ) { e ->
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: establishing the VPN interface with IPv6 failed, trying without IPv6", e)
            }
            isRunning = true
            ConnectionStatsManager.onSessionStarted()
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: setting up the VPN interface failed", e)
            stopAllService()
        }
        return false
    }

    /**
     * The VPN interface once more, without IPv6, for a kernel that refuses it
     * ([establishFallingBackToIpv4]).
     */
    private fun establishWithoutIpv6(): ParcelFileDescriptor {
        val builder = Builder()
        configureNetworkSettings(builder, ipv6 = false)
        configurePerAppProxy(builder)
        configurePlatformFeatures(builder)
        return builder.establish()!!
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     * @param ipv6 Whether the interface takes the IPv6 address, routes and DNS servers ([addVpnDnsServers])
     */
    private fun configureNetworkSettings(builder: Builder, ipv6: Boolean) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (ipv6) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        addVpnDnsServers(SettingsManager.getVpnDnsServers(), ipv6, { builder.addDnsServer(it) }) { server, e ->
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: skipping the VPN DNS server $server, which Android refuses", e)
        }

        //builder.setSession(XrayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        // The TUN has exactly one reader. Everything the app serves — including
        // WireGuard and AmneziaWG — is dialled by the core, which listens on the
        // local SOCKS inbound that tun2socks forwards to. A second reader of the
        // same descriptor would drain the packets before the tunnel saw them,
        // and the connection would show as up while carrying nothing.
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
        stopRequested = true
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        unlockStart()
        if (isRunning) ConnectionStatsManager.onSessionStopped()
        isRunning = false

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        RootLanSharing.stopClientSharing(this)

        CoreServiceManager.stopCoreLoop()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise xray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created xray core report port in use
            //which means the first xray core somehow failed to stop and release the port.
            stopSelf()

            // Add a small delay to allow the async core stop operation to complete
            // before closing the VPN interface, preventing a race condition that can
            // leave the VPN icon in the status bar after stopping the service.
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
            }

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
    }

    fun tryLockStart(): Boolean {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: tryLockStart: ${isStartingLock.get()}")
        return isStartingLock.compareAndSet(false, true)
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}

/**
 * Establishes the VPN interface through [establish], and when that fails while the interface takes IPv6, once more
 * through [establishWithoutIpv6]: a kernel with IPv6 turned off refuses the IPv6 address, and the VPN then runs on
 * IPv4 alone, its DNS servers included ([addVpnDnsServers]), which blocks IPv6 for the apps it covers. A failure
 * without IPv6 stays a failure. Needed for as long as the interface takes IPv6 by default.
 */
internal fun <T> establishFallingBackToIpv4(
    ipv6: Boolean,
    establish: () -> T,
    establishWithoutIpv6: () -> T,
    onIpv6Refused: (Exception) -> Unit,
): T {
    if (!ipv6) {
        return establish()
    }
    return try {
        establish()
    } catch (e: Exception) {
        onIpv6Refused(e)
        establishWithoutIpv6()
    }
}

/**
 * Adds the VPN interface's DNS servers, the addresses among [servers], through [add], which is VpnService.Builder
 * .addDnsServer: it refuses a loopback or wildcard (0.0.0.0, ::) address, or one it cannot read, with an
 * IllegalArgumentException, and such a server is skipped ([onRefused]) rather than ending the start. On an interface
 * without [ipv6], whether the user turned it off or the kernel refused it, IPv6 ones are left out: a DNS server
 * allows its address family on the VPN (addDnsServer, allowFamily), so Android would leave the VPN's IPv6 unblocked,
 * and IPv6 would go around the VPN. When the servers given leave none, or none were given to an interface without
 * IPv6, [AppConfig.DNS_VPN], as the default network's servers would be used otherwise; none given with IPv6 stays
 * none. Returns the servers added.
 */
internal fun addVpnDnsServers(
    servers: List<String>,
    ipv6: Boolean,
    add: (String) -> Unit,
    onRefused: (String, IllegalArgumentException) -> Unit,
): List<String> {
    val added = servers.filter { Utils.isPureIpAddress(it) && (ipv6 || !it.contains(':')) }.filter {
        try {
            add(it)
            true
        } catch (e: IllegalArgumentException) {
            onRefused(it, e)
            false
        }
    }
    if (added.isNotEmpty() || (ipv6 && servers.isEmpty())) {
        return added
    }
    add(AppConfig.DNS_VPN)
    return listOf(AppConfig.DNS_VPN)
}
