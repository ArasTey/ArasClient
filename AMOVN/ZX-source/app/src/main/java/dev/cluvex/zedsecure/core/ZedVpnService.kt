package dev.cluvex.zedsecure.core

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.cluvex.zedsecure.core.AppLog as Log
import dev.amirzr.flutter_v2ray_client.v2ray.core.HevTunCore
import dev.cluvex.zedsecure.R
import dev.cluvex.zedsecure.data.settings.SettingsRepository
import dev.cluvex.zedsecure.domain.config.LocalPorts
import dev.cluvex.zedsecure.domain.config.RoutingMigration
import dev.cluvex.zedsecure.domain.model.AppSettings
import dev.cluvex.zedsecure.domain.model.RulesetItem
import dev.cluvex.zedsecure.domain.model.VpnBypassLan
import dev.cluvex.zedsecure.domain.model.VpnInterfaceAddress
import dev.cluvex.zedsecure.domain.config.XrayJsonBuilder
import dev.cluvex.zedsecure.domain.config.effectiveLocalProxyPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class ZedVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tun: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private var remark: String = ""
    private val notifications by lazy { VpnNotifications(this) }

    private var stage: VpnNotifications.Stage = VpnNotifications.Stage.Preparing

    private var connectWhen: Long = 0L

    private var showSpeed: Boolean = true
    private var livePromotion: Boolean = true
    private var notifChip: dev.cluvex.zedsecure.domain.model.NotifChip =
        dev.cluvex.zedsecure.domain.model.NotifChip.Speed
    private var psiphon: PsiphonController? = null
    private var dnsTunnel: DnsTunnelController? = null
    private var masterDns: MasterDnsController? = null
    private var tor: dev.cluvex.zedsecure.core.tor.TorController? = null
    private var ssh: SshController? = null
    private var sniSpoof: SniSpoofController? = null

    @Volatile
    private var sniSpoofEdge: String? = null
    private var openConnect: OpenConnectController? = null
    private var singBox: SingBoxEngine? = null

    private var singBoxLastUp = 0L
    private var singBoxLastDown = 0L
    private var kind: String = VpnManager.KIND_XRAY

    private var outerPsiphon: PsiphonController? = null
    private var outerTor: dev.cluvex.zedsecure.core.tor.TorController? = null
    private var outerSsh: SshController? = null
    private var outerDnsTunnel: DnsTunnelController? = null
    private var crossOuterKind: String? = null
    private var crossInnerKind: String? = null

    @Volatile private var userStop = false
    private var killSwitchEnabled = false

    private var killSwitchActive = false

    @Volatile private var reconnecting = false
    private var geoReloadJob: Job? = null
    private var holdWatch: android.net.ConnectivityManager.NetworkCallback? = null
    private val holdNetworks = java.util.Collections.synchronizedSet(HashSet<android.net.Network>())
    private var retryJob: Job? = null
    private var attemptJob: Job? = null
    @Volatile private var attemptStartedAt = 0L
    private var retryDelayMs = RETRY_FIRST_MS

    private var connected = false

    private var proxyOnly = false

    private var autoSelectSession = false
    private var networkWatch: android.net.ConnectivityManager.NetworkCallback? = null
    private var networkTypeWatch: android.net.ConnectivityManager.NetworkCallback? = null
    private var networkTypeJob: Job? = null
    @Volatile private var builtNetworkType: String? = null
    @Volatile private var swappingCore = false

    private val startLock = kotlinx.coroutines.sync.Mutex()

    private var hevIpv4: String = VpnInterfaceAddress.Option2.ipv4Client
    private var hevIpv6: String? = null
    private var hevLogLevel: String = HevTunCore.DEFAULT_LOG_LEVEL
    private var hevRwTimeout: String = HevTunCore.DEFAULT_RW_TIMEOUT

    private var useZepTun: Boolean = true

    private var localProxyPort: Int = LocalPorts.XRAY_SOCKS

    override fun attachBaseContext(base: Context) {
        val tag = SettingsRepository.readLanguageTag(base)
        super.attachBaseContext(dev.cluvex.zedsecure.core.platform.LocaleManager.wrap(base, tag))
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()

        XrayController.warmUp(this)
        XrayController.onCoreShutdown = { onTunnelFailed(null) }
        FrontCore.onLost = { onTunnelFailed(null) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra(VpnManager.EXTRA_COMMAND)

        if (command != VpnManager.CMD_STOP) {
            val wentForeground = runCatching {
                startInForeground(
                    if (connected) connectedNotification(0, 0, 0, 0) else connectingNotification(),
                )
            }.isSuccess
            if (!wentForeground) {
                Log.w(TAG, "could not enter the foreground; standing down")
                VpnManager.onError(getString(R.string.engine_missing_config))
                stopSelf()
                return START_NOT_STICKY
            }
        }
        when {
            command == VpnManager.CMD_START -> {
                val config = intent.getStringExtra(VpnManager.EXTRA_CONFIG)
                    ?: intent.getStringExtra(VpnManager.EXTRA_CONFIG_PATH)?.let { path ->

                        runCatching { java.io.File(path).let { f -> f.readText().also { f.delete() } } }
                            .getOrNull()
                    }
                remark = intent.getStringExtra(VpnManager.EXTRA_REMARK).orEmpty()
                val socksPort = intent.getIntExtra(VpnManager.EXTRA_SOCKS_PORT, LocalPorts.XRAY_SOCKS)
                kind = intent.getStringExtra(VpnManager.EXTRA_KIND) ?: VpnManager.KIND_XRAY
                proxyOnly = intent.getBooleanExtra(VpnManager.EXTRA_PROXY_ONLY, false)
                if (config.isNullOrBlank()) {
                    VpnManager.onError(getString(R.string.engine_missing_config))
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (Ikev2Controller.isActive) Ikev2Controller.stop(this)

                resetSessionState()
                stage = VpnNotifications.Stage.Preparing
                startInForeground(connectingNotification())
                scope.launch {
                    startLock.withLock {
                        if (tun != null) stopEngines()
                        startTunnel(config, socksPort, kind)
                    }
                }
            }
            command == VpnManager.CMD_STOP -> { userStop = true; stopEverything() }

            command == VpnManager.CMD_START_ACTIVE -> startActiveProfile(allowProxyOnly = true)

            intent?.action == VpnService.SERVICE_INTERFACE -> startActiveProfile(allowProxyOnly = false)

            intent == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn ->
                startActiveProfile(allowProxyOnly = false)
            else -> stopEverything()
        }
        return START_STICKY
    }

    private fun startActiveProfile(allowProxyOnly: Boolean) {
        val state = VpnManager.status.value.state
        if (state.isActive || state.isTransitioning) {
            startInForeground(if (connected) connectedNotification(0, 0, 0, 0) else connectingNotification())
            return
        }
        if (Ikev2Controller.isActive) Ikev2Controller.stop(this)
        resetSessionState()
        val container = (application as dev.cluvex.zedsecure.ZedSecureApp).container
        remark = container.configRepository.activeProfile()?.name.orEmpty()
        kind = VpnManager.KIND_XRAY
        proxyOnly = false
        stage = VpnNotifications.Stage.Preparing

        startInForeground(connectingNotification())
        scope.launch {
            when (val plan = StartPlanner(this@ZedVpnService).planActive(allowProxyOnly)) {
                is StartPlanner.Plan.Start -> {
                    VpnManager.onStarting(plan.remark)
                    remark = plan.remark
                    kind = plan.kind
                    proxyOnly = plan.proxyOnly
                    startTunnel(plan.configJson, plan.socksPort, plan.kind)
                }
                is StartPlanner.Plan.Ikev2 -> {
                    VpnManager.onError(getString(R.string.engine_ikev2_needs_app))
                    stopEverything()
                }
                is StartPlanner.Plan.Failure -> {
                    VpnManager.onError(plan.message)
                    stopEverything()
                }
            }
        }
    }

    private fun startTunnel(configJson: String, socksPort: Int, kind: String) {
        val settings = SettingsRepository(this).settings.value
        killSwitchEnabled = settings.killSwitch
        userStop = false
        killSwitchActive = false
        connected = false
        showSpeed = settings.speedInNotification
        livePromotion = settings.liveNotification
        notifChip = settings.notifChip
        val iface = settings.vpnInterfaceAddress
        hevIpv4 = iface.ipv4Client
        hevIpv6 = if (settings.enableIpv6) iface.ipv6Client else null
        useZepTun = settings.useZepTun && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        localProxyPort = settings.effectiveLocalProxyPort
        hevLogLevel = settings.hevTunLogLevel.value
        hevRwTimeout = settings.hevTunRwTimeout

        if (proxyOnly) {
            if (kind == VpnManager.KIND_XRAY) { startProxyOnly(configJson, socksPort); return }
            proxyOnly = false
        }

        if (kind == VpnManager.KIND_OPENCONNECT) { startOpenConnect(configJson, settings); return }

        if (kind == VpnManager.KIND_SINGBOX) { startSingBox(configJson, settings); return }

        val dnsInChain = kind == VpnManager.KIND_DNS_TUNNEL || kind == VpnManager.KIND_MASTERDNS ||
            (kind == VpnManager.KIND_CROSS_CHAIN && crossChainHasDnsTunnel(configJson))
        val tunMtu = if (dnsInChain) 1280
        else settings.vpnMtu.coerceIn(1000, 9000)

        val bypassLan = shouldBypassLan(settings, configJson, kind)

        val spoofBypassIp =
            if (kind == VpnManager.KIND_SNISPOOF) sniSpoofEdgeIp(configJson) else null

        sniSpoofEdge = spoofBypassIp
        val descriptor = try {
            Builder().apply {
                setSession(remark.ifBlank { "ZedSecure" })
                setMtu(tunMtu)

                addAddress(iface.ipv4Router, 30)
                if (settings.enableIpv6) addAddress(iface.ipv6Router, 126)
                addLanAwareRoutes(bypassLan, settings.enableIpv6, excludeIp = spoofBypassIp)
                applyAppRules(settings)

                if (kind == VpnManager.KIND_XRAY) {
                    val vpnDns = if (settings.localDnsEnabled) emptyList()
                    else vpnDnsServers(settings.vpnDns, settings.enableIpv6)
                    if (vpnDns.isNotEmpty()) vpnDns.forEach { runCatching { addDnsServer(it) } }
                    else addDnsServersFrom(configJson)
                } else {
                    val primary = if (settings.remoteDnsMode == "custom") {
                        settings.remoteDnsPrimary.ifBlank { "8.8.8.8" }
                    } else "8.8.8.8"
                    val fallback = if (settings.remoteDnsMode == "custom") {
                        settings.remoteDnsFallback.ifBlank { "1.1.1.1" }
                    } else "1.1.1.1"
                    runCatching { addDnsServer(primary) }
                    runCatching { addDnsServer(fallback) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false)
            }.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (descriptor == null) {
            VpnManager.onError(getString(R.string.engine_no_interface))
            stopEverything()
            return
        }
        val held = tun
        tun = descriptor
        if (held != null && held !== descriptor) runCatching { held.close() }
        advance(VpnNotifications.Stage.Interface)

        VpnManager.setActiveKind(kind)

        when (kind) {
            VpnManager.KIND_PSIPHON -> startPsiphon(configJson, descriptor, tunMtu)
            VpnManager.KIND_DNS_TUNNEL -> startDnsTunnel(configJson, descriptor, tunMtu, settings)
            VpnManager.KIND_MASTERDNS -> startMasterDns(configJson, descriptor, tunMtu, settings)
            VpnManager.KIND_TOR -> startTor(settings, descriptor, tunMtu)
            VpnManager.KIND_SSH -> startSsh(configJson, descriptor, tunMtu, settings)
            VpnManager.KIND_SNISPOOF -> startSniSpoof(configJson, socksPort, descriptor, tunMtu)
            VpnManager.KIND_CROSS_CHAIN -> startCrossChain(configJson, descriptor, tunMtu, settings)
            else -> startXray(configJson, socksPort, descriptor, tunMtu)
        }
    }

    private fun startSniSpoof(configJson: String, socksPort: Int, descriptor: ParcelFileDescriptor, tunMtu: Int) {
        scope.launch {
            try {
                val wrapper = JSONObject(configJson)
                val xray = wrapper.getJSONObject("xray")
                val sni = wrapper.getJSONObject("sni")
                val fakeSni = sni.optString("fakeSni")
                val cleanIp = sni.optString("cleanIp")

                if (!SniSpoofController.rootAvailable()) {
                    VpnManager.onError(getString(R.string.snispoof_needs_root))
                    stopEverything(); return@launch
                }

                val orig = readProxyOutbound(xray)
                if (orig == null) {
                    VpnManager.onError(getString(R.string.engine_snispoof_no_outbound))
                    stopEverything(); return@launch
                }
                val (realHost, realPort) = orig

                val connectIp = sniSpoofEdge ?: cleanIp.ifBlank {
                    runCatching { java.net.InetAddress.getByName(realHost).hostAddress }.getOrNull() ?: realHost
                }
                val listenPort = LocalPortPicker.pick(SNISPOOF_PORT)
                val controller = SniSpoofController(this@ZedVpnService, "$connectIp:$realPort", fakeSni, listenPort)
                sniSpoof = controller
                if (!controller.start()) {
                    VpnManager.onError(getString(R.string.snispoof_needs_root))
                    stopEverything(); return@launch
                }
                writeProxyOutbound(xray, "127.0.0.1", listenPort)

                val port = freePairFor(socksPort)
                if (!XrayController.start(XrayJsonBuilder.withLocalPorts(xray.toString(), LocalPorts.XRAY_SOCKS, port))) {
                    VpnManager.onError(coreStartError())
                    stopEverything(); return@launch
                }

                if (!bridge(descriptor, port, tunMtu, pipeline = true)) return@launch

                if (!TunnelReadiness.verify(port, timeoutMs = 7_000, attempts = 4)) {
                    val reason = if ((sniSpoof?.ackTimeouts ?: 0) > 0) {
                        R.string.snispoof_no_ack
                    } else {
                        R.string.snispoof_no_traffic
                    }
                    VpnManager.onError(getString(reason))
                    stopEverything(); return@launch
                }
                VpnManager.activeSocksPort = port
                finishConnected()
            } catch (e: Exception) {
                Log.e(TAG, "sni-spoof start failed", e)
                VpnManager.onError(engineFailed(R.string.engine_failed_snispoof, e.message))
                stopEverything()
            }
        }
    }

    private fun readProxyOutbound(xray: JSONObject): Pair<String, Int>? {
        val ob = proxyOutbound(xray) ?: return null
        val settings = ob.optJSONObject("settings") ?: return null
        val node = settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
        val host = node.optString("address").ifBlank { return null }
        val port = node.optInt("port", 0).takeIf { it > 0 } ?: return null
        return host to port
    }

    private fun writeProxyOutbound(xray: JSONObject, host: String, port: Int) {
        val ob = proxyOutbound(xray) ?: return
        val settings = ob.optJSONObject("settings") ?: return
        val node = settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0) ?: return
        node.put("address", host); node.put("port", port)
    }

    private fun proxyOutbound(xray: JSONObject): JSONObject? {
        val arr = xray.optJSONArray("outbounds") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("tag") == "proxy") return o
        }
        return arr.optJSONObject(0)
    }

    private fun startSsh(
        configJson: String,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
        appSettings: AppSettings,
    ) {
        val sshProfile = try {
            kotlinx.serialization.json.Json.decodeFromString(
                dev.cluvex.zedsecure.domain.config.SshProfile.serializer(), configJson,
            )
        } catch (e: Exception) {
            Log.e(TAG, "bad SSH config", e)
            VpnManager.onError(getString(R.string.engine_invalid_config_ssh))
            stopEverything(); return
        }
        scope.launch {
            val controller = SshController(
                profile = sshProfile,
                cipher = appSettings.sshCipher,
                compression = appSettings.sshCompression,
                listenPort = LocalPorts.SSH,
                maxListenPort = LocalPorts.SSH_MAX,
            )
            ssh = controller
            val port = controller.start()
            if (port < 0) {
                VpnManager.onError(engineFailed(R.string.engine_failed_ssh, controller.lastError))
                stopEverything(); return@launch
            }
            bridgeEngine(descriptor, port, tunMtu)
        }
    }

    private fun startTor(settings: AppSettings, descriptor: ParcelFileDescriptor, tunMtu: Int) {
        val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
        val torPort = LocalPortPicker.pick(LocalPorts.TOR_SOCKS)
        val controller = dev.cluvex.zedsecure.core.tor.TorController(
            context = this,
            settings = settings,
            onBootstrapped = {
                if (bridged.compareAndSet(false, true)) bridgeEngine(descriptor, torPort, tunMtu, onion = true)
            },
            onProgress = { pct -> LogBus.append("I/Tor bootstrap $pct%") },
            onStopped = { reason ->
                onTunnelFailed(reason)
            },
            socksPort = torPort,
        )
        tor = controller
        if (!controller.start()) stopEverything() else armTorBootstrapDeadline(bridged)
    }

    private fun startDnsTunnel(
        configJson: String,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
        appSettings: AppSettings,
    ) {
        val dnsProfile = try {
            kotlinx.serialization.json.Json.decodeFromString(
                dev.cluvex.zedsecure.domain.config.DnsTunnelProfile.serializer(), configJson,
            )
        } catch (e: Exception) {
            Log.e(TAG, "bad DNS-tunnel config", e)
            VpnManager.onError(getString(R.string.engine_invalid_config_dnstunnel))
            stopEverything(); return
        }
        val pool = if (appSettings.dnsPoolEnabled) {
            appSettings.dnsPoolText.split('\n', ',').map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else emptyList()
        val controller = DnsTunnelController(
            dnsProfile,
            listenPort = LocalPorts.DNS_TUNNEL,
            maxListenPort = LocalPorts.DNS_TUNNEL_MAX,
            pool = pool,
            poolFullVerification = appSettings.dnsPoolFullVerification,
        )
        dnsTunnel = controller
        val port = controller.start()
        if (port < 0) {
            VpnManager.onError(engineFailed(R.string.engine_failed_dnstunnel, controller.lastError))
            stopEverything(); return
        }

        val upstreamPort = if (dnsProfile.sshEnabled && dnsProfile.sshHost.isNotBlank()) {
            val sshProfile = dev.cluvex.zedsecure.domain.config.SshProfile(
                host = dnsProfile.sshHost,
                port = dnsProfile.sshPort,
                username = dnsProfile.sshUsername,
                authType = dnsProfile.sshAuthType,
                password = dnsProfile.sshPassword,
                privateKey = dnsProfile.sshPrivateKey,
                keyPassphrase = dnsProfile.sshKeyPassphrase,
            )
            val sshController = SshController(
                profile = sshProfile,
                cipher = appSettings.sshCipher,
                compression = appSettings.sshCompression,
                listenPort = LocalPorts.SSH_OVER_DNS,
                maxListenPort = LocalPorts.SSH_OVER_DNS_MAX,
                proxySocksHost = "127.0.0.1",
                proxySocksPort = port,
                proxySocksUser = dnsProfile.socksUser,
                proxySocksPass = dnsProfile.socksPass,
            )
            ssh = sshController
            val sshPort = sshController.start()
            if (sshPort < 0) {
                VpnManager.onError(engineFailed(R.string.engine_failed_ssh_over_dns, sshController.lastError))
                stopEverything(); return
            }
            Log.i(TAG, "DNS+SSH: SSH SOCKS on $sshPort (via DNS tunnel $port)")
            sshPort
        } else {
            port
        }

        bridgeEngine(descriptor, upstreamPort, tunMtu, unreachable = R.string.dns_tunnel_unreachable)
    }

    private fun startMasterDns(
        configJson: String,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
        appSettings: AppSettings,
    ) {
        val profile = try {
            kotlinx.serialization.json.Json.decodeFromString(
                dev.cluvex.zedsecure.domain.config.MasterDnsProfile.serializer(), configJson,
            )
        } catch (e: Exception) {
            Log.e(TAG, "bad MasterDNS config", e)
            VpnManager.onError(getString(R.string.engine_invalid_config_masterdns))
            stopEverything(); return
        }

        val effective = if (appSettings.dnsGlobalResolverEnabled && appSettings.dnsGlobalResolvers.isNotBlank()) {
            profile.copy(resolvers = appSettings.dnsGlobalResolvers)
        } else profile
        val pool = if (appSettings.dnsPoolEnabled) {
            appSettings.dnsPoolText.split('\n', ',').map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else emptyList()
        val controller = MasterDnsController(
            effective, filesDir,
            listenPort = LocalPorts.MASTER_DNS,
            maxListenPort = LocalPorts.MASTER_DNS_MAX,
            pool = pool,
            poolFullVerification = appSettings.dnsPoolFullVerification,
        )
        masterDns = controller
        val port = controller.start()
        if (port < 0) {
            if (controller.stopped) return
            VpnManager.onError(masterDnsError(controller))
            stopEverything(); return
        }

        if (!controller.awaitReady(MASTERDNS_IDLE_TIMEOUT_MS, MASTERDNS_MAX_WAIT_MS)) {
            if (controller.stopped) return
            VpnManager.onError(masterDnsError(controller))
            stopEverything(); return
        }
        bridgeEngine(descriptor, port, tunMtu)
    }

    private fun masterDnsError(c: MasterDnsController): String = when (val f = c.failure) {
        is MasterDnsController.Failure.NoUsableResolver ->
            getString(R.string.masterdns_no_resolver, f.tried)
        is MasterDnsController.Failure.SessionRefused ->
            getString(R.string.masterdns_session_refused, f.usable, f.reason.ifBlank { "—" })
        is MasterDnsController.Failure.Stalled ->
            getString(R.string.masterdns_stalled, f.checked, f.total, f.usable)
        is MasterDnsController.Failure.Other ->
            getString(R.string.masterdns_failed_reason, f.reason.take(200))
        null -> getString(R.string.masterdns_failed)
    }

    private fun startOpenConnect(configJson: String, appSettings: AppSettings) {
        VpnManager.setActiveKind(kind)
        val profile = try {
            kotlinx.serialization.json.Json.decodeFromString(
                dev.cluvex.zedsecure.domain.config.OpenConnectProfile.serializer(), configJson,
            )
        } catch (e: Exception) {
            Log.e(TAG, "bad OpenConnect config", e)
            VpnManager.onError(getString(R.string.engine_invalid_config_openconnect))
            stopEverything(); return
        }
        val controller = OpenConnectController(
            service = this,
            profile = profile,
            filesDir = filesDir,
            onLog = { level, msg -> Log.println(if (level <= 0) Log.ERROR else Log.INFO, TAG, msg) },

            configureBuilder = { b -> b.applyAppRules(appSettings, selfInTunnel = true) },

            fallbackDns = appSettings.vpnDns.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
            onUp = { finishConnected() },
            onDown = { reason -> onTunnelFailed(reason) },
        )
        openConnect = controller
        if (!controller.start()) stopEverything()
    }

    private fun startSingBox(configJson: String, appSettings: AppSettings) {
        VpnManager.setActiveKind(kind)
        advance(VpnNotifications.Stage.Engine)
        val engine = SingBoxEngine(
            service = this,
            session = remark,
            settings = appSettings,
            onLog = { LogBus.append(it) },
            onStopped = { reason -> onTunnelFailed(reason) },
        )
        singBox = engine
        singBoxLastUp = 0L
        singBoxLastDown = 0L
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                engine.start(configJson)
            } catch (e: Exception) {
                Log.e(TAG, "sing-box failed to start", e)
                VpnManager.onError(engineFailed(R.string.engine_singbox_failed, e.message))
                stopEverything()
                return@launch
            }
            VpnManager.activeSocksPort = runCatching {
                dev.cluvex.zedsecure.domain.config.SingBoxConfigs.prepareForDevice(configJson).socksPort
            }.getOrNull()
            advance(VpnNotifications.Stage.Tunnel)
            finishConnected()
        }
    }

    private fun engineFailed(res: Int, reason: String?): String =
        reason?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { getString(R.string.engine_failed_with_reason, getString(res), it.take(240)) }
            ?: getString(res)

    private fun coreStartError(error: String? = XrayController.lastError): String {
        val raw = error ?: return getString(R.string.core_start_failed)

        Regex("failed to (?:check|load) code ([^ ]+) from ([^ :]+)").find(raw)?.let { m ->
            return getString(R.string.core_geo_code_missing, m.groupValues[1], m.groupValues[2])
        }

        Regex("failed to open (geoip[^ :]*\\.dat|geosite[^ :]*\\.dat)").find(raw)?.let { m ->
            return getString(R.string.core_geo_file_missing, m.groupValues[1])
        }
        return getString(R.string.core_start_failed_reason, raw.take(240))
    }

    private fun startProxyOnly(configJson: String, socksPort: Int) {
        killSwitchEnabled = false
        VpnManager.setActiveKind(kind)
        advance(VpnNotifications.Stage.Engine)
        if (!XrayController.start(XrayJsonBuilder.withLocalPorts(configJson, LocalPorts.XRAY_SOCKS, socksPort))) {
            VpnManager.onError(coreStartError())
            stopEverything()
            return
        }
        onXrayStarted()
        VpnManager.activeSocksPort = socksPort
        finishConnected()
    }

    private fun vpnDnsServers(value: String, ipv6: Boolean): List<String> =
        value.split(',', ' ', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && XrayJsonBuilder.isIpLiteral(it) }
            .filter { ipv6 || !it.contains(':') }
            .distinct()

    private fun startXray(configJson: String, preferredPort: Int, descriptor: ParcelFileDescriptor, tunMtu: Int) {
        val socksPort = freePairFor(preferredPort)
        if (!XrayController.start(XrayJsonBuilder.withLocalPorts(configJson, LocalPorts.XRAY_SOCKS, socksPort))) {
            VpnManager.onError(coreStartError())
            stopEverything()
            return
        }
        onXrayStarted()

        if (!bridge(descriptor, socksPort, tunMtu, pipeline = true)) return
        VpnManager.activeSocksPort = socksPort
        finishConnected()
    }

    private fun freePairFor(preferred: Int): Int =
        if (LocalPortPicker.isFree(preferred) && LocalPortPicker.isFree(preferred + 1)) preferred
        else LocalPortPicker.pickPair(preferred + 2).also {
            LogBus.append("W/Engine port $preferred is taken, maybe by ZedSecure in another profile; using $it")
        }

    private fun onXrayStarted() {
        autoSelectSession = AutoSelect.activate()
        if (!autoSelectSession) return
        LogBus.append("I/Auto-select: started over ${AutoSelect.session.value?.memberProfiles?.size ?: 0} servers")
        watchNetworkChanges()
    }

    private fun pollAutoSelect() {
        val events = AutoSelect.poll()
        val session = AutoSelect.session.value ?: return
        val container = (application as dev.cluvex.zedsecure.ZedSecureApp).container
        val selectedId = session.selectedProfileId ?: return
        container.configRepository.rememberAutoSelectPick(session.profileId, selectedId)
        val name = container.configRepository.profile(selectedId)?.name ?: return
        val label = getString(R.string.auto_notif_remark, name)
        if (label != remark) {
            remark = label
            VpnManager.onConnected(remark)
        }
        events.forEach { e ->
            val from = session.memberProfiles[e.from]?.let { container.configRepository.profile(it)?.name } ?: e.from
            val to = session.memberProfiles[e.to]?.let { container.configRepository.profile(it)?.name } ?: e.to
            LogBus.append("I/Auto-select: ${e.reason} $from -> $to")
        }
    }

    private fun watchNetworkChanges() {
        if (networkWatch != null) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            private var current: android.net.Network? = null
            private var pending: Job? = null

            override fun onAvailable(network: android.net.Network) {
                val previous = current
                current = network

                if (previous == null || previous == network) return
                pending?.cancel()

                pending = scope.launch {
                    delay(NETWORK_SETTLE_MS)
                    LogBus.append("I/Auto-select: network changed, re-measuring every server")
                    AutoSelect.networkChanged()
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkWatch = callback }
            .onFailure { Log.w(TAG, "network watch unavailable", it) }
    }

    private fun stopAutoSelectSession() {
        networkWatch?.let { cb ->
            runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkWatch = null
        if (autoSelectSession) AutoSelect.end()
        autoSelectSession = false
    }

    private fun startPsiphon(configJson: String, descriptor: ParcelFileDescriptor, tunMtu: Int) {
        val socksPort = java.util.concurrent.atomic.AtomicInteger(0)
        val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
        val controller = PsiphonController(
            service = this,
            configJson = configJson,
            onSocksPort = { port -> socksPort.set(port) },
            onEstablished = {
                if (bridged.compareAndSet(false, true)) bridgeEngine(descriptor, socksPort.get(), tunMtu)
            },
            onStopped = { reason ->
                onTunnelFailed(reason)
            },
        )
        psiphon = controller
        if (!controller.start()) stopEverything()
    }

    private fun bridgeEngine(
        descriptor: ParcelFileDescriptor,
        enginePort: Int,
        tunMtu: Int,
        onion: Boolean = false,
        unreachable: Int? = null,
    ) {
        if (enginePort <= 0) {
            VpnManager.onError(getString(R.string.crosschain_inner_failed))
            stopEverything(); return
        }
        val front = runCatching { StartPlanner(this).engineFront(enginePort, onion) }
            .onFailure { Log.e(TAG, "the routing front could not be built", it) }
            .getOrNull()
        val port = freePairFor(localProxyPort)
        if (front == null || !FrontCore.start(this, XrayJsonBuilder.withLocalPorts(front, LocalPorts.XRAY_SOCKS, port))) {
            VpnManager.onError(coreStartError(FrontCore.lastError))
            stopEverything(); return
        }
        if (!bridge(descriptor, port, tunMtu, pipeline = true)) return
        if (unreachable != null && !TunnelReadiness.verify(port)) {
            VpnManager.onError(getString(unreachable))
            stopEverything(); return
        }
        VpnManager.activeSocksPort = port
        finishConnected()
    }

    private fun bridge(
        descriptor: ParcelFileDescriptor,
        socksPort: Int,
        tunMtu: Int,
        pipeline: Boolean = false,
    ): Boolean {
        advance(VpnNotifications.Stage.Engine)

        val startHev = {
            HevTunCore.start(
                context = this,
                tun = descriptor,
                socksPort = socksPort,
                mtu = tunMtu,
                ipv4 = hevIpv4,
                ipv6 = hevIpv6,
                preferIpv6 = hevIpv6 != null,
                pipeline = pipeline,
                logLevel = hevLogLevel,
                rwTimeout = hevRwTimeout,
            )
        }
        var onZepTun = false
        val ok = if (useZepTun) {
            onZepTun = ZepTunCore.start(
                service = this,
                tun = descriptor,
                socksPort = socksPort,
                mtu = tunMtu,
                ipv4 = hevIpv4,
                ipv6 = hevIpv6,
                pipeline = pipeline,
                logLevel = hevLogLevel,
                rwTimeout = hevRwTimeout,
            )
            if (onZepTun) {
                LogBus.append("I/tun zeptun ${ZepTunCore.version} carrying the tunnel")
                true
            } else {
                Log.w(TAG, "zeptun did not start; falling back to hev")
                LogBus.append("W/tun zeptun did not start, falling back to hev")
                startHev()
            }
        } else {
            startHev()
        }

        if (ok && onZepTun) {
            scope.launch {
                kotlinx.coroutines.delay(ZEPTUN_PROOF_MS)
                if (!ZepTunCore.carryingTraffic()) {
                    LogBus.append("W/tun zeptun carried nothing (${ZepTunCore.stats()}), switching to hev")
                    Log.w(TAG, "zeptun carried nothing (${ZepTunCore.stats()}); switching to hev")
                    ZepTunCore.stop()
                    if (!startHev()) {
                        VpnManager.onError(getString(R.string.engine_failed_tunnel))
                        stopEverything()
                    }
                }
            }
        }

        if (!ok) {
            VpnManager.onError(getString(R.string.engine_failed_tunnel))
            stopEverything()
        } else {
            advance(VpnNotifications.Stage.Tunnel)
        }
        return ok
    }

    private fun advance(next: VpnNotifications.Stage) {
        if (connected) return
        stage = next
        runCatching { notify(connectingNotification()) }
        ZedWidgetProvider.refresh(this, force = true)
    }

    private fun connectingNotification(): android.app.Notification =
        notifications.build(
            server = remark,
            phase = VpnNotifications.Phase.Connecting(stage),
            protocolLabel = protocolLabel(),
            livePromotion = livePromotion,
        )

    private fun protocolLabel(): String = when (kind) {
        VpnManager.KIND_PSIPHON -> "Psiphon"
        VpnManager.KIND_DNS_TUNNEL -> "DNS"
        VpnManager.KIND_MASTERDNS -> "MasterDNS"
        VpnManager.KIND_TOR -> "Tor"
        VpnManager.KIND_SSH -> "SSH"
        VpnManager.KIND_OPENCONNECT -> "OpenConnect"
        VpnManager.KIND_SINGBOX -> "sing-box"
        VpnManager.KIND_IKEV2 -> "IKEv2"
        VpnManager.KIND_SNISPOOF -> "Xray · SNI"

        VpnManager.KIND_CROSS_CHAIN ->
            "${engineLabel(crossInnerKind)} → ${engineLabel(crossOuterKind)}"
        else -> if (proxyOnly) "Xray · Proxy" else "Xray"
    }

    private fun engineLabel(k: String?): String = when (k) {
        VpnManager.KIND_PSIPHON -> "Psiphon"
        VpnManager.KIND_DNS_TUNNEL -> "DNS"
        VpnManager.KIND_MASTERDNS -> "MasterDNS"
        VpnManager.KIND_TOR -> "Tor"
        VpnManager.KIND_SSH -> "SSH"
        else -> "Xray"
    }

    private fun finishConnected() {
        connected = true
        if (reconnecting) LogBus.append("I/Kill switch: reconnected; traffic flows through the VPN again")
        reconnecting = false
        stopHoldWatch()
        retryDelayMs = RETRY_FIRST_MS
        geoReloadJob?.cancel()
        geoReloadJob = scope.launch {
            dev.cluvex.zedsecure.data.assets.GeoAutoFetch.ready.collect { reloadForGeoFiles() }
        }
        stage = VpnNotifications.Stage.Connected
        connectWhen = System.currentTimeMillis()
        VpnManager.onConnected(remark)
        notify(connectedNotification(0, 0, 0, 0))
        ZedWidgetProvider.refresh(this, force = true)
        startStatsLoop()
        watchNetworkType()
    }

    private fun watchNetworkType() {
        if (networkTypeWatch != null || kind != VpnManager.KIND_XRAY || autoSelectSession) return
        val settings = SettingsRepository(this).settings.value
        val dependent = dev.cluvex.zedsecure.domain.config.RoutingMigration.effectiveRulesets(settings)
            .any { it.enabled && it.networkType.isNotEmpty() }
        if (!dependent) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        builtNetworkType = NetworkKind.current(this)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                val type = NetworkKind.of(caps) ?: return
                if (type == builtNetworkType) return
                networkTypeJob?.cancel()
                networkTypeJob = scope.launch {
                    delay(NETWORK_SETTLE_MS)
                    rebuildForNetworkType(type)
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkTypeWatch = callback }
            .onFailure { Log.w(TAG, "network type watch unavailable", it) }
    }

    private fun stopNetworkTypeWatch() {
        networkTypeJob?.cancel()
        networkTypeJob = null
        networkTypeWatch?.let { cb ->
            runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkTypeWatch = null
    }

    private fun rebuildForNetworkType(type: String) {
        if (!connected || kind != VpnManager.KIND_XRAY || autoSelectSession) return
        val plan = StartPlanner(this).planActive(allowProxyOnly = proxyOnly)
        val sameSession = plan is StartPlanner.Plan.Start && plan.kind == VpnManager.KIND_XRAY &&
            plan.proxyOnly == proxyOnly && plan.remark == remark &&
            plan.socksPort == (VpnManager.activeSocksPort ?: plan.socksPort)
        if (!sameSession) {
            builtNetworkType = type
            LogBus.append("W/Routing: the network is now $type; reconnect to use the rules for it")
            return
        }
        swappingCore = true
        val started = try {
            (plan as StartPlanner.Plan.Start).let { XrayController.start(XrayJsonBuilder.withLocalPorts(it.configJson, LocalPorts.XRAY_SOCKS, it.socksPort)) }
        } finally {
            swappingCore = false
        }
        if (started) {
            builtNetworkType = type
            LogBus.append("I/Routing: the network is now $type; the rules for it are in use")
        } else {
            VpnManager.onError(coreStartError())
            stopEverything()
        }
    }

    private fun reloadForGeoFiles() {
        if (!connected || kind != VpnManager.KIND_XRAY) return
        if (autoSelectSession) {
            LogBus.append("I/Routing: geo files arrived; reconnect to use the rules that need them")
            return
        }
        val plan = StartPlanner(this).planActive(allowProxyOnly = proxyOnly)
        val sameSession = plan is StartPlanner.Plan.Start && plan.kind == VpnManager.KIND_XRAY &&
            plan.proxyOnly == proxyOnly && plan.remark == remark &&
            plan.socksPort == (VpnManager.activeSocksPort ?: plan.socksPort)
        if (!sameSession) {
            LogBus.append("I/Routing: geo files arrived; reconnect to use the rules that need them")
            return
        }
        swappingCore = true
        val started = try {
            (plan as StartPlanner.Plan.Start).let { XrayController.start(XrayJsonBuilder.withLocalPorts(it.configJson, LocalPorts.XRAY_SOCKS, it.socksPort)) }
        } finally {
            swappingCore = false
        }
        if (started) {
            LogBus.append("I/Routing: geo files loaded; every routing rule is in use now")
        } else {
            VpnManager.onError(coreStartError())
            stopEverything()
        }
    }

    private fun connectedNotification(
        downBps: Long,
        upBps: Long,
        totalDown: Long,
        totalUp: Long,
    ): android.app.Notification = notifications.build(
        server = remark,
        phase = VpnNotifications.Phase.Connected,
        protocolLabel = protocolLabel(),
        downBps = downBps,
        upBps = upBps,
        totalDown = totalDown,
        totalUp = totalUp,
        connectedAt = connectWhen,
        showSpeed = showSpeed,
        livePromotion = livePromotion,
        chip = notifChip,
    )

    private var lastUidRx = -1L
    private var lastUidTx = -1L

    private fun uidTrafficDelta(): Pair<Long, Long> {
        val uid = android.os.Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        val unsupported = android.net.TrafficStats.UNSUPPORTED.toLong()
        if (rx == unsupported || tx == unsupported) return 0L to 0L
        val delta = if (lastUidRx < 0) {
            0L to 0L
        } else {
            (rx - lastUidRx).coerceAtLeast(0) to (tx - lastUidTx).coerceAtLeast(0)
        }
        lastUidRx = rx
        lastUidTx = tx
        return delta
    }

    private val powerManager by lazy { getSystemService(POWER_SERVICE) as android.os.PowerManager }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var ticks = 0
            var elapsed: Int
            var totalDown = 0L
            var totalUp = 0L

            var waitMs = dev.cluvex.zedsecure.domain.power.PowerPolicy.cadence(screenOn = true).tickMs
            var lastPollSec = 0
            var lastNotifySec = 0
            while (isActive) {
                val waited = waitMs
                delay(waited)
                val running = when (kind) {
                    VpnManager.KIND_PSIPHON -> psiphon?.isRunning == true
                    VpnManager.KIND_DNS_TUNNEL -> dnsTunnel?.isRunning == true
                    VpnManager.KIND_MASTERDNS -> masterDns?.isRunning == true
                    VpnManager.KIND_TOR -> tor?.isRunning == true
                    VpnManager.KIND_SSH -> ssh?.isRunning == true
                    VpnManager.KIND_OPENCONNECT -> openConnect?.isRunning == true
                    VpnManager.KIND_SINGBOX -> singBox?.isRunning == true

                    VpnManager.KIND_CROSS_CHAIN ->
                        engineAlive(crossInnerKind, outer = false) && engineAlive(crossOuterKind, outer = true)
                    else -> swappingCore || XrayController.isRunning
                }
                if (!running) { onTunnelFailed(null); break }
                elapsed = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                ticks++
                val awake = runCatching { powerManager.isInteractive }.getOrDefault(true)
                val cadence = dev.cluvex.zedsecure.domain.power.PowerPolicy.cadence(screenOn = awake)
                waitMs = cadence.tickMs

                val pollEvery = cadence.autoPollSeconds
                if (autoSelectSession && elapsed - lastPollSec >= pollEvery) {
                    lastPollSec = elapsed
                    pollAutoSelect()
                }

                val (down, up) = if (FrontCore.isRunning) FrontCore.readTrafficDelta() else when (kind) {
                    VpnManager.KIND_XRAY, VpnManager.KIND_SNISPOOF -> XrayController.readTrafficDelta()

                    VpnManager.KIND_PSIPHON -> uidTrafficDelta()
                    VpnManager.KIND_OPENCONNECT -> { openConnect?.pokeStats(); openConnect?.trafficDelta() ?: (0L to 0L) }

                    VpnManager.KIND_SINGBOX -> singBox?.let { engine ->
                        val down = (engine.downlinkTotal - singBoxLastDown).coerceAtLeast(0)
                        val up = (engine.uplinkTotal - singBoxLastUp).coerceAtLeast(0)
                        singBoxLastDown = engine.downlinkTotal
                        singBoxLastUp = engine.uplinkTotal
                        down to up
                    } ?: (0L to 0L)

                    else -> XrayController.readTrafficDelta()
                }
                totalDown += down
                totalUp += up

                val seconds = (waited / 1000L).coerceAtLeast(1L)
                VpnManager.onMetrics(elapsed, down / seconds, up / seconds, totalDown, totalUp)

                val repaint = elapsed - lastNotifySec >= cadence.notifySeconds
                if (repaint) {
                    lastNotifySec = elapsed
                    if (showSpeed) notify(connectedNotification(down / seconds, up / seconds, totalDown, totalUp))
                    ZedWidgetProvider.refresh(this@ZedVpnService)
                }
            }
        }
    }

    private fun notify(notification: android.app.Notification) {
        getSystemService(NotificationManager::class.java)?.notify(VpnNotifications.NOTIFICATION_ID, notification)
    }

    private fun VpnService.Builder.applyAppRules(settings: AppSettings, selfInTunnel: Boolean = false) {
        val self = packageName
        if (!settings.perAppProxyEnabled || settings.perAppPackages.isEmpty()) {
            if (!selfInTunnel) runCatching { addDisallowedApplication(self) }
            return
        }
        if (settings.perAppBypassMode) {
            (settings.perAppPackages + self).forEach { pkg ->
                runCatching { addDisallowedApplication(pkg) }
            }
        } else {
            settings.perAppPackages.forEach { pkg ->
                if (pkg == self && !selfInTunnel) return@forEach
                runCatching { addAllowedApplication(pkg) }
            }
            if (selfInTunnel && self !in settings.perAppPackages) {
                runCatching { addAllowedApplication(self) }
            }
        }
    }

    private fun sniSpoofEdgeIp(configJson: String): String? = sniSpoofEdgeIpOrNull(configJson).also {
        if (it == null) Log.w(TAG, "SNI-spoof: could not resolve the edge IP; it will NOT be excluded")
        else Log.i(TAG, "SNI-spoof edge resolved to $it")
    }

    private fun sniSpoofEdgeIpOrNull(configJson: String): String? = runCatching {
        val wrapper = JSONObject(configJson)
        val sni = wrapper.optJSONObject("sni") ?: return null
        val clean = sni.optString("cleanIp").orEmpty()
        if (clean.isNotBlank()) return clean
        val xray = wrapper.optJSONObject("xray") ?: return null
        val host = readProxyOutbound(xray)?.first ?: return null
        java.net.InetAddress.getByName(host).hostAddress
    }.getOrNull()

    private fun VpnService.Builder.addLanAwareRoutes(
        bypassLan: Boolean,
        ipv6: Boolean,
        excludeIp: String? = null,
    ) {
        if (excludeIp != null && Build.VERSION.SDK_INT >= 33) {
            val excluded = runCatching {
                excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(excludeIp), 32))
                true
            }.getOrDefault(false)
            if (excluded) {
                Log.i(TAG, "excludeRoute: SNI-spoof edge $excludeIp kept off the tunnel")
                if (bypassLan) {
                    LAN_BYPASS_ROUTES.forEach { cidr ->
                        val slash = cidr.indexOf('/')
                        runCatching { addRoute(cidr.substring(0, slash), cidr.substring(slash + 1).toInt()) }
                    }
                } else {
                    addRoute("0.0.0.0", 0)
                }
                if (ipv6) runCatching { addRoute("::", 0) }
                return
            }
        }

        if (!bypassLan && excludeIp != null) {
            val split = RouteSplit.defaultExcluding(excludeIp)
            if (split.isNotEmpty()) {
                Log.i(TAG, "routing around SNI-spoof edge $excludeIp (${split.size} routes)")
                split.forEach { (addr, prefix) -> runCatching { addRoute(addr, prefix) } }
                if (ipv6) runCatching { addRoute("::", 0) }
                return
            }
        }
        if (bypassLan) {
            LAN_BYPASS_ROUTES.forEach { cidr ->
                val slash = cidr.indexOf('/')
                runCatching { addRoute(cidr.substring(0, slash), cidr.substring(slash + 1).toInt()) }
            }
        } else {
            addRoute("0.0.0.0", 0)
        }
        if (!ipv6) return
        if (bypassLan) {
            addRoute("2000::", 3)
            addRoute("fc00::", 18)
        } else {
            addRoute("::", 0)
        }
    }

    private fun shouldBypassLan(settings: AppSettings, configJson: String, kind: String): Boolean =
        when (settings.vpnBypassLan) {
            VpnBypassLan.Bypass -> true
            VpnBypassLan.NotBypass -> false
            VpnBypassLan.FollowConfig ->
                if (kind == VpnManager.KIND_XRAY || kind == VpnManager.KIND_SNISPOOF) {
                    configRoutesPrivateDirect(configJson)
                } else {
                    RoutingMigration.effectiveRulesets(settings).any { rule ->
                        rule.enabled && rule.outboundTag == RulesetItem.OUTBOUND_DIRECT &&
                            (rule.domain.any(::isPrivateMatcher) || rule.ip.any(::isPrivateMatcher))
                    }
                }
        }

    private fun configRoutesPrivateDirect(configJson: String): Boolean = runCatching {
        val root = JSONObject(configJson)

        val xray = root.optJSONObject("xray") ?: root
        val rules = xray.optJSONObject("routing")?.optJSONArray("rules") ?: return false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("outboundTag") != "direct") continue
            for (key in arrayOf("domain", "ip")) {
                val arr = rule.optJSONArray(key) ?: continue
                for (j in 0 until arr.length()) {
                    if (isPrivateMatcher(arr.optString(j))) return true
                }
            }
        }
        false
    }.getOrDefault(false)

    private fun isPrivateMatcher(entry: String): Boolean =
        entry == "geoip:private" || entry == "geosite:private" ||
            entry == "192.168.0.0/16" || entry == "10.0.0.0/8" || entry == "172.16.0.0/12"

    private fun VpnService.Builder.addDnsServersFrom(configJson: String) {
        var added = false
        runCatching {
            val dns = JSONObject(configJson).optJSONObject("dns") ?: return@runCatching
            val servers = dns.optJSONArray("servers") ?: return@runCatching
            for (i in 0 until servers.length()) {
                val entry = servers.opt(i)
                val addr = when (entry) {
                    is String -> entry
                    is JSONObject -> entry.optString("address").ifBlank { null }
                    else -> null
                }
                if (addr != null && !addr.startsWith("http")) {
                    runCatching { addDnsServer(addr); added = true }
                }
            }
        }
        if (!added) {
            runCatching { addDnsServer("1.1.1.1") }
            runCatching { addDnsServer("8.8.8.8") }
        }
    }

    private fun stopEngines() {
        geoReloadJob?.cancel()
        geoReloadJob = null
        statsJob?.cancel()
        stopNetworkTypeWatch()
        stopAutoSelectSession()
        psiphon?.stop(); psiphon = null
        dnsTunnel?.stop(); dnsTunnel = null
        masterDns?.stop(); masterDns = null
        tor?.stop(); tor = null
        ssh?.stop(); ssh = null
        sniSpoof?.stop(); sniSpoof = null
        openConnect?.stop(); openConnect = null
        singBox?.stop(); singBox = null
        outerPsiphon?.stop(); outerPsiphon = null
        outerTor?.stop(); outerTor = null
        outerSsh?.stop(); outerSsh = null
        outerDnsTunnel?.stop(); outerDnsTunnel = null
        ZepTunCore.stop()
        HevTunCore.stop()
        FrontCore.stop()
        XrayController.stop()
    }

    private fun resetSessionState() {
        statsJob?.cancel()
        statsJob = null
        connected = false
        connectWhen = 0L
        crossInnerKind = null

        lastUidRx = -1L
        lastUidTx = -1L
    }

    private val tearingDown = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun stopEverything() {
        if (reconnecting && !userStop && killSwitchEnabled && ensureKillSwitchTun()) {
            holdTraffic(null)
            return
        }
        stopHoldWatch()
        reconnecting = false
        val tunToClose = tun
        tun = null
        connected = false
        VpnManager.onDisconnected(keepError = !userStop)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        ZedWidgetProvider.refresh(this, force = true)

        if (Looper.myLooper() != Looper.getMainLooper()) {
            stopEngines()
            runCatching { tunToClose?.close() }
            stopSelf()
            return
        }
        if (!tearingDown.compareAndSet(false, true)) return

        Thread({
            runCatching { stopEngines() }
            runCatching { tunToClose?.close() }
            tearingDown.set(false)
            stopSelf()
        }, "zed-teardown").apply { isDaemon = false }.start()
    }

    private fun armTorBootstrapDeadline(bridged: java.util.concurrent.atomic.AtomicBoolean) {
        scope.launch {
            kotlinx.coroutines.delay(TOR_BOOTSTRAP_TIMEOUT_MS)
            if (!bridged.get() && VpnManager.status.value.state.isTransitioning) {
                LogBus.append("E/Tor bootstrap timed out after ${TOR_BOOTSTRAP_TIMEOUT_MS / 1000}s")
                onTunnelFailed(getString(R.string.tor_bootstrap_timeout))
            }
        }
    }

    private fun ensureKillSwitchTun(): Boolean {
        if (tun != null) return true
        return runCatching {
            val b = Builder()
                .setSession(getString(R.string.kill_switch))
                .setMtu(1400)
                .addAddress("10.111.222.1", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)

            runCatching { b.addDisallowedApplication(packageName) }
            tun = b.establish()
            tun != null
        }.getOrElse {
            Log.w(TAG, "kill-switch tun failed", it)
            false
        }
    }

    private fun onTunnelFailed(reason: String?) {
        if (userStop || killSwitchActive || swappingCore) return
        if (killSwitchEnabled && (connected || reconnecting) && ensureKillSwitchTun()) {
            holdTraffic(reason)
        } else {
            if (reason != null) VpnManager.onError(reason)
            stopEverything()
        }
    }

    private fun holdTraffic(reason: String?) {
        killSwitchActive = true
        reconnecting = false
        connected = false
        stopEngines()
        ensureKillSwitchTun()
        VpnManager.onError(reason ?: getString(R.string.kill_switch))
        runCatching {
            notify(
                notifications.build(
                    server = remark,
                    phase = VpnNotifications.Phase.Failed(
                        reason = getString(R.string.notif_kill_switch_body),
                    ),
                    protocolLabel = protocolLabel(),
                ),
            )
        }
        ZedWidgetProvider.refresh(this, force = true)
        watchForNetwork()
        scheduleRetry(retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    private fun watchForNetwork() {
        if (holdWatch != null) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val fresh = holdNetworks.add(network)
                if (fresh) networkCameBack()
            }

            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) && holdNetworks.add(network)) {
                    networkCameBack()
                }
            }

            override fun onLost(network: android.net.Network) {
                holdNetworks.remove(network)
            }
        }
        holdNetworks.clear()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { holdWatch = callback }
            .onFailure { Log.w(TAG, "kill-switch network watch unavailable", it) }
    }

    private fun networkCameBack() {
        if (userStop) return
        if (reconnecting) {
            val running = android.os.SystemClock.elapsedRealtime() - attemptStartedAt
            if (running < STALE_ATTEMPT_MS) return
            LogBus.append("I/Kill switch: the network changed during a reconnect; starting it again")
            retryDelayMs = RETRY_FIRST_MS
            onTunnelFailed(null)
            return
        }
        if (!killSwitchActive) return
        retryDelayMs = RETRY_FIRST_MS
        scheduleRetry(NETWORK_SETTLE_MS)
    }

    private fun stopHoldWatch() {
        retryJob?.cancel()
        retryJob = null
        attemptJob?.cancel()
        attemptJob = null
        holdWatch?.let { cb ->
            runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        holdWatch = null
        holdNetworks.clear()
    }

    private fun scheduleRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            retryFromHold()
        }
    }

    private fun retryFromHold() {
        if (userStop || !killSwitchActive || reconnecting) return
        if (holdWatch != null && holdNetworks.isEmpty()) {
            LogBus.append("I/Kill switch: no network yet; reconnecting once one is back")
            return
        }
        LogBus.append("I/Kill switch: reconnecting")
        reconnecting = true
        attemptStartedAt = android.os.SystemClock.elapsedRealtime()
        attemptJob?.cancel()
        attemptJob = scope.launch {
            delay(RECONNECT_ATTEMPT_MS)
            if (reconnecting && !connected && !userStop) {
                LogBus.append("W/Kill switch: the reconnect did not finish in ${RECONNECT_ATTEMPT_MS / 1000}s; trying again")
                onTunnelFailed(null)
            }
        }
        stage = VpnNotifications.Stage.Preparing
        runCatching { notify(connectingNotification()) }
        when (val plan = StartPlanner(this).planActive(allowProxyOnly = false)) {
            is StartPlanner.Plan.Start -> {
                VpnManager.onStarting(plan.remark)
                remark = plan.remark
                kind = plan.kind
                proxyOnly = plan.proxyOnly
                startTunnel(plan.configJson, plan.socksPort, plan.kind)
            }
            is StartPlanner.Plan.Ikev2 -> holdTraffic(getString(R.string.engine_ikev2_needs_app))
            is StartPlanner.Plan.Failure -> holdTraffic(plan.message)
        }
    }

    override fun onRevoke() {
        userStop = true
        stopEverything()
    }

    override fun onDestroy() {
        XrayController.onCoreShutdown = null
        scope.cancel()
        super.onDestroy()
    }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        writer.println("engine=$kind tun=${tun != null} core=${XrayController.isRunning}")
        writer.println("crypto=" + XrayController.cryptoAcceleration())
        writer.println(ZepTunCore.stats())
        writer.println()
        writer.println(XrayController.runtimeReport(if (args?.contains("all") == true) 40 else 12))
    }

    private fun startInForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(VpnNotifications.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(VpnNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun crossChainHasDnsTunnel(wrapper: String): Boolean = runCatching {
        val o = JSONObject(wrapper)
        val dns = setOf(VpnManager.KIND_DNS_TUNNEL, VpnManager.KIND_MASTERDNS)
        o.optString("innerKind") in dns || o.optString("outerKind") in dns
    }.getOrDefault(false)

    private fun engineAlive(k: String?, outer: Boolean): Boolean = when (k) {
        VpnManager.KIND_PSIPHON -> (if (outer) outerPsiphon else psiphon)?.isRunning == true
        VpnManager.KIND_DNS_TUNNEL -> (if (outer) outerDnsTunnel else dnsTunnel)?.isRunning == true
        VpnManager.KIND_MASTERDNS -> masterDns?.isRunning == true
        VpnManager.KIND_TOR -> (if (outer) outerTor else tor)?.isRunning == true
        VpnManager.KIND_SSH -> (if (outer) outerSsh else ssh)?.isRunning == true
        else -> XrayController.isRunning
    }

    private fun startCrossChain(
        wrapper: String,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
        settings: AppSettings,
    ) {
        val parsed = runCatching { JSONObject(wrapper) }.getOrNull()
        if (parsed == null) {
            VpnManager.onError(getString(R.string.config_invalid)); stopEverything(); return
        }
        val innerKind = parsed.optString("innerKind")
        val outerKind = parsed.optString("outerKind")
        val innerConfig = parsed.optString("inner")
        val outerConfig = parsed.optString("outer")
        crossInnerKind = innerKind
        crossOuterKind = outerKind
        Log.i(TAG, "cross chain: $innerKind through $outerKind")

        startCrossOuter(outerKind, outerConfig, settings) { carrierPort ->
            if (carrierPort <= 0) {
                VpnManager.onError(getString(R.string.crosschain_outer_failed))
                stopEverything()
            } else {
                Log.i(TAG, "cross chain: carrier up on $carrierPort")
                startCrossInner(innerKind, innerConfig, carrierPort, descriptor, tunMtu, settings)
            }
        }
    }

    private fun startCrossOuter(
        outerKind: String,
        config: String,
        settings: AppSettings,
        onReady: (Int) -> Unit,
    ) {
        when (outerKind) {
            VpnManager.KIND_PSIPHON -> {
                val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
                val socks = java.util.concurrent.atomic.AtomicInteger(LocalPorts.PSIPHON_SOCKS)
                val c = PsiphonController(
                    service = this,
                    configJson = config,
                    onSocksPort = { port -> socks.set(port) },

                    onEstablished = {
                        if (bridged.compareAndSet(false, true)) onReady(socks.get())
                    },
                    onStopped = { reason -> onTunnelFailed(reason) },
                )
                outerPsiphon = c
                if (!c.start()) { onReady(-1) }
            }
            VpnManager.KIND_TOR -> {
                val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
                val torPort = LocalPortPicker.pick(LocalPorts.TOR_SOCKS)
                val c = dev.cluvex.zedsecure.core.tor.TorController(
                    context = this,
                    settings = settings,
                    onBootstrapped = {
                        if (bridged.compareAndSet(false, true)) onReady(torPort)
                    },
                    onProgress = { pct -> LogBus.append("I/Tor bootstrap $pct%") },
                    onStopped = { reason -> onTunnelFailed(reason) },
                    socksPort = torPort,
                )
                outerTor = c
                if (!c.start()) onReady(-1) else armTorBootstrapDeadline(bridged)
            }
            VpnManager.KIND_SSH -> {
                val p = runCatching {
                    kotlinx.serialization.json.Json.decodeFromString(
                        dev.cluvex.zedsecure.domain.config.SshProfile.serializer(), config,
                    )
                }.getOrNull() ?: return onReady(-1)
                scope.launch {
                    val c = SshController(
                        profile = p,
                        cipher = settings.sshCipher,
                        compression = settings.sshCompression,
                        listenPort = LocalPorts.SSH,
                        maxListenPort = LocalPorts.SSH_MAX,
                    )
                    outerSsh = c
                    onReady(c.start())
                }
            }
            VpnManager.KIND_DNS_TUNNEL -> {
                val p = runCatching {
                    kotlinx.serialization.json.Json.decodeFromString(
                        dev.cluvex.zedsecure.domain.config.DnsTunnelProfile.serializer(), config,
                    )
                }.getOrNull() ?: return onReady(-1)
                scope.launch {
                    val c = DnsTunnelController(
                        p,
                        listenPort = LocalPorts.DNS_TUNNEL,
                        maxListenPort = LocalPorts.DNS_TUNNEL_MAX,
                        pool = dnsPoolOf(settings),
                        poolFullVerification = settings.dnsPoolFullVerification,
                    )
                    outerDnsTunnel = c
                    onReady(c.start())
                }
            }

            else -> {
                val carrierPort = LocalPortPicker.pick(LocalPorts.CHAIN_CARRIER)
                val carrier = XrayJsonBuilder.withSocksPort(config, LocalPorts.XRAY_SOCKS, carrierPort)
                if (!XrayController.start(carrier)) {
                    VpnManager.onError(coreStartError())
                    onReady(-1)
                } else {
                    onReady(carrierPort)
                }
            }
        }
    }

    private fun startCrossInner(
        innerKind: String,
        config: String,
        carrierPort: Int,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
        settings: AppSettings,
    ) {
        when (innerKind) {
            VpnManager.KIND_DNS_TUNNEL -> {
                val p = runCatching {
                    kotlinx.serialization.json.Json.decodeFromString(
                        dev.cluvex.zedsecure.domain.config.DnsTunnelProfile.serializer(), config,
                    )
                }.getOrNull()
                if (p == null) { VpnManager.onError(getString(R.string.config_invalid)); stopEverything(); return }
                scope.launch {
                    val c = DnsTunnelController(
                        p,
                        listenPort = LocalPorts.DNS_TUNNEL,
                        maxListenPort = LocalPorts.DNS_TUNNEL_MAX,
                        pool = dnsPoolOf(settings),
                        poolFullVerification = settings.dnsPoolFullVerification,
                        upstreamSocks = "127.0.0.1:$carrierPort",
                        upstreamSocksUser = "",
                        upstreamSocksPass = "",
                    )
                    dnsTunnel = c
                    bridgeEngine(descriptor, c.start(), tunMtu)
                }
            }
            VpnManager.KIND_SSH -> {
                val p = runCatching {
                    kotlinx.serialization.json.Json.decodeFromString(
                        dev.cluvex.zedsecure.domain.config.SshProfile.serializer(), config,
                    )
                }.getOrNull()
                if (p == null) { VpnManager.onError(getString(R.string.config_invalid)); stopEverything(); return }
                scope.launch {
                    val c = SshController(
                        profile = p,
                        cipher = settings.sshCipher,
                        compression = settings.sshCompression,
                        listenPort = LocalPorts.SSH_OVER_DNS,
                        maxListenPort = LocalPorts.SSH_OVER_DNS_MAX,
                        proxySocksHost = "127.0.0.1",
                        proxySocksPort = carrierPort,
                    )
                    ssh = c
                    bridgeEngine(descriptor, c.start(), tunMtu)
                }
            }
            VpnManager.KIND_TOR -> {
                val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
                val torPort = LocalPortPicker.pick(LocalPorts.TOR_SOCKS, setOf(carrierPort))
                val c = dev.cluvex.zedsecure.core.tor.TorController(
                    context = this,
                    settings = settings,
                    onBootstrapped = {
                        if (bridged.compareAndSet(false, true)) bridgeEngine(descriptor, torPort, tunMtu, onion = true)
                    },
                    onProgress = { pct -> LogBus.append("I/Tor bootstrap $pct%") },
                    onStopped = { reason -> onTunnelFailed(reason) },
                    upstreamSocksPort = carrierPort,
                    socksPort = torPort,
                )
                tor = c
                if (!c.start()) stopEverything() else armTorBootstrapDeadline(bridged)
            }
            VpnManager.KIND_PSIPHON -> {
                val patched = runCatching {
                    dev.cluvex.zedsecure.domain.config.PsiphonConfigBuilder
                        .withUpstreamProxy(config, carrierPort)
                }.getOrNull()
                if (patched == null) { VpnManager.onError(getString(R.string.config_invalid)); stopEverything(); return }
                val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
                val socks = java.util.concurrent.atomic.AtomicInteger(LocalPorts.PSIPHON_SOCKS)
                val c = PsiphonController(
                    service = this,
                    configJson = patched,
                    onSocksPort = { port -> socks.set(port) },
                    onEstablished = {
                        if (bridged.compareAndSet(false, true)) bridgeEngine(descriptor, socks.get(), tunMtu)
                    },
                    onStopped = { reason -> onTunnelFailed(reason) },
                )
                psiphon = c
                if (!c.start()) stopEverything()
            }

            else -> {
                val patched = runCatching {
                    dev.cluvex.zedsecure.domain.config.XrayJsonBuilder
                        .withCarrierProxy(config, carrierPort)
                }.getOrNull()
                if (patched == null) {
                    VpnManager.onError(getString(R.string.config_invalid)); stopEverything(); return
                }
                val port = freePairFor(localProxyPort)
                if (!XrayController.start(XrayJsonBuilder.withLocalPorts(patched, LocalPorts.XRAY_SOCKS, port))) {
                    VpnManager.onError(coreStartError()); stopEverything(); return
                }

                if (bridge(descriptor, port, tunMtu, pipeline = true)) {
                    VpnManager.activeSocksPort = port
                    finishConnected()
                } else {
                    stopEverything()
                }
            }
        }
    }

    private fun dnsPoolOf(settings: AppSettings): List<String> =
        if (settings.dnsPoolEnabled) {
            settings.dnsPoolText.split('\n', ',').map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else emptyList()

    companion object {
        private const val TAG = "ZedVpnService"

        private const val ZEPTUN_PROOF_MS = 6_000L

        private const val NETWORK_SETTLE_MS = 1_500L
        private const val RETRY_FIRST_MS = 3_000L
        private const val RECONNECT_ATTEMPT_MS = 150_000L
        private const val STALE_ATTEMPT_MS = 8_000L
        private const val RETRY_MAX_MS = 60_000L


        private const val MASTERDNS_IDLE_TIMEOUT_MS = 90_000L

        private const val MASTERDNS_MAX_WAIT_MS = 300_000L

        private const val TOR_BOOTSTRAP_TIMEOUT_MS = 120_000L

        private const val SNISPOOF_PORT = LocalPorts.SNI_SPOOF
        private const val MTU = 1500

        private val LAN_BYPASS_ROUTES = listOf(
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4",
            "32.0.0.0/3", "64.0.0.0/2", "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6",
            "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9", "173.0.0.0/8",
            "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11", "192.160.0.0/13",
            "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12",
            "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5",
            "208.0.0.0/4", "240.0.0.0/4",
        )
    }
}
