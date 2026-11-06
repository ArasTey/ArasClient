package dev.cluvex.zedsecure.data.settings

import android.content.Context
import android.content.SharedPreferences
import dev.cluvex.zedsecure.domain.model.AppLanguage
import dev.cluvex.zedsecure.domain.ai.AiSettings
import dev.cluvex.zedsecure.domain.model.AppSettings
import dev.cluvex.zedsecure.domain.model.DomainStrategy
import dev.cluvex.zedsecure.domain.model.FragmentPackets
import dev.cluvex.zedsecure.domain.model.GeoFilesSource
import dev.cluvex.zedsecure.domain.model.HevLogLevel
import dev.cluvex.zedsecure.domain.model.LogLevel
import dev.cluvex.zedsecure.domain.model.OutboundDomainResolve
import dev.cluvex.zedsecure.domain.model.RoutingMode
import dev.cluvex.zedsecure.domain.model.RulesetItem
import dev.cluvex.zedsecure.domain.model.RunMode
import dev.cluvex.zedsecure.domain.model.ThemeMode
import dev.cluvex.zedsecure.domain.model.VpnBypassLan
import dev.cluvex.zedsecure.domain.model.VpnInterfaceAddress
import dev.cluvex.zedsecure.domain.model.XudpQuic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun read(): AppSettings {
        val d = AppSettings(useZepTun = true, directDns = "localhost")
        if (!prefs.getBoolean(KEY_ROUTING_DEFAULTS_MIGRATED, false)) {
            val edit = prefs.edit()
            if (prefs.getString("direct_dns", null) == LEGACY_DIRECT_DNS) edit.putString("direct_dns", d.directDns)
            if (prefs.getString("domain_strategy", null) == DomainStrategy.IpIfNonMatch.name) {
                edit.putString("domain_strategy", d.domainStrategy.name)
            }
            if (prefs.getString("tor_rendezvous", null) == LEGACY_RENDEZVOUS) edit.putString("tor_rendezvous", d.torSnowflakeRendezvous)
            edit.putBoolean(KEY_ROUTING_DEFAULTS_MIGRATED, true).apply()
        }
        return AppSettings(

            themeMode = prefs.enum("theme_mode", d.themeMode),
            dynamicColor = prefs.getBoolean("dynamic_color", d.dynamicColor),
            accentColor = prefs.getInt("accent_color", d.accentColor),
            colorStyle = prefs.enum("color_style", d.colorStyle),
            colorContrast = prefs.enum("color_contrast", d.colorContrast),
            appDesign = prefs.enum("app_design", d.appDesign),
            amoledBlack = prefs.getBoolean("amoled_black", d.amoledBlack),
            language = prefs.enum("language", d.language),

            customDownloadColor = prefs.argb("custom_color_download"),
            customUploadColor = prefs.argb("custom_color_upload"),
            customDownloadTileColor = prefs.argb("custom_color_download_tile"),
            customUploadTileColor = prefs.argb("custom_color_upload_tile"),
            customConnectColor = prefs.argb("custom_color_connect"),
            customConnectedColor = prefs.argb("custom_color_connected"),
            customTapHintColor = prefs.argb("custom_color_tap_hint"),
            customServerCardColor = prefs.argb("custom_color_server_card"),
            customServerActiveColor = prefs.argb("custom_color_server_active"),
            customConfigCardColor = prefs.argb("custom_color_config_card"),
            customConfigCardTextColor = prefs.argb("custom_color_config_card_text"),
            connectButtonStyle = prefs.enum("connect_button_style", d.connectButtonStyle),
            navBarStyle = prefs.enum("nav_bar_style", d.navBarStyle),
            themeProfileId = prefs.getString("theme_profile_id", null),
            cardCornerStyle = prefs.enum("card_corner_style", d.cardCornerStyle),
            listDensity = prefs.enum("list_density", d.listDensity),
            uiFontScale = prefs.enum("ui_font_scale", d.uiFontScale),
            showServerPing = prefs.getBoolean("show_server_ping", d.showServerPing),
            showServerUsage = prefs.getBoolean("show_server_usage", d.showServerUsage),
            monospaceAddress = prefs.getBoolean("monospace_address", d.monospaceAddress),
            speedInNotification = prefs.getBoolean("speed_notif", d.speedInNotification),
            liveNotification = prefs.getBoolean("live_notif", d.liveNotification),
            notifChip = prefs.enum("notif_chip", d.notifChip),
            onboardingVersion = prefs.getInt("onboarding_version", d.onboardingVersion),
            screenTransition = prefs.enum("screen_transition", d.screenTransition),
            confirmRemove = prefs.getBoolean("confirm_remove", d.confirmRemove),
            homeAfterSelect = prefs.getBoolean("home_after_select", d.homeAfterSelect),
            showTrafficTiles = prefs.getBoolean("traffic_tiles", d.showTrafficTiles),
            trafficTilesAboveHero = prefs.getBoolean("traffic_tiles_top", d.trafficTilesAboveHero),
            trafficTileSize = prefs.enum("traffic_tile_size", d.trafficTileSize),
            trafficCardStyle = prefs.enum("traffic_card_style", d.trafficCardStyle),
            doubleColumnDisplay = prefs.getBoolean("double_column", d.doubleColumnDisplay),
            groupAllDisplay = prefs.getBoolean("group_all", d.groupAllDisplay),
            reduceMotion = prefs.getBoolean("reduce_motion", d.reduceMotion),
            speedFabAtEnd = prefs.getBoolean("speed_fab_at_end", d.speedFabAtEnd),
            speedFabY = prefs.getFloat("speed_fab_y", d.speedFabY),
            verifiedOriginCode = prefs.str("verified_origin_code", d.verifiedOriginCode),
            verifiedOriginLabel = prefs.str("verified_origin_label", d.verifiedOriginLabel),

            runMode = prefs.enum("run_mode", d.runMode),

            enableIpv6 = prefs.getBoolean("ipv6", d.enableIpv6),
            preferIpv6 = prefs.getBoolean("prefer_ipv6", d.preferIpv6),
            localDnsEnabled = prefs.getBoolean("local_dns", d.localDnsEnabled),
            fakeDnsEnabled = prefs.getBoolean("fake_dns", d.fakeDnsEnabled),
            vpnDns = prefs.str("vpn_dns", d.vpnDns),
            appendHttpProxy = prefs.getBoolean("append_http_proxy", d.appendHttpProxy),
            vpnBypassLan = prefs.enum("vpn_bypass_lan", d.vpnBypassLan),
            vpnInterfaceAddress = prefs.enum("vpn_iface_addr", d.vpnInterfaceAddress),
            vpnMtu = prefs.getInt("vpn_mtu", d.vpnMtu),
            useZepTun = prefs.getBoolean("use_zeptun", d.useZepTun),
            hevTunLogLevel = prefs.enum("hev_log_level", d.hevTunLogLevel),
            hevTunRwTimeout = prefs.str("hev_rw_timeout", d.hevTunRwTimeout),

            sniffingEnabled = prefs.getBoolean("sniffing", d.sniffingEnabled),
            routeOnly = prefs.getBoolean("route_only", d.routeOnly),
            enableLocalProxy = prefs.getBoolean("enable_local_proxy", d.enableLocalProxy),
            proxySharing = prefs.getBoolean("proxy_sharing", d.proxySharing),
            dynamicSocksPort = prefs.getBoolean("dynamic_socks_port", d.dynamicSocksPort),
            socksPort = prefs.getInt("socks_port", d.socksPort),
            localProxyPort = prefs.getInt("local_proxy_port", d.localProxyPort),
            socksUsername = prefs.str("socks_username", d.socksUsername),
            socksPassword = prefs.str("socks_password", d.socksPassword),
            lanShareAuth = prefs.getBoolean("lan_share_auth", d.lanShareAuth),
            socksEnableUdp = prefs.getBoolean("socks_udp", d.socksEnableUdp),
            remoteDns = prefs.str("remote_dns", d.remoteDns),
            directDns = prefs.str("direct_dns", d.directDns),
            dnsHosts = prefs.str("dns_hosts", d.dnsHosts),
            logLevel = prefs.enum("log_level", d.logLevel),
            outboundDomainResolve = prefs.enum("outbound_resolve", d.outboundDomainResolve),

            domainStrategy = prefs.enum("domain_strategy", d.domainStrategy),
            routingMode = prefs.enum("routing_mode", d.routingMode),
            blockAds = prefs.getBoolean("block_ads", d.blockAds),
            customProxyRules = prefs.str("custom_proxy_rules", d.customProxyRules),
            customDirectRules = prefs.str("custom_direct_rules", d.customDirectRules),
            customBlockRules = prefs.str("custom_block_rules", d.customBlockRules),
            rulesets = prefs.rulesets("rulesets", d.rulesets),
            routingMigrated = prefs.getBoolean("routing_migrated", d.routingMigrated),
            routingSeeded = prefs.getBoolean("routing_seeded", d.routingSeeded),

            perAppProxyEnabled = prefs.getBoolean("per_app", d.perAppProxyEnabled),
            perAppBypassMode = prefs.getBoolean("per_app_bypass", d.perAppBypassMode),
            perAppPackages = prefs.getStringSet("per_app_set", null) ?: d.perAppPackages,

            singBoxMuxEnabled = prefs.getBoolean("sb_mux", d.singBoxMuxEnabled),
            singBoxMuxProtocol = prefs.enum("sb_mux_protocol", d.singBoxMuxProtocol),
            singBoxMuxMaxConnections = prefs.getInt("sb_mux_connections", d.singBoxMuxMaxConnections),
            singBoxMuxPadding = prefs.getBoolean("sb_mux_padding", d.singBoxMuxPadding),
            singBoxBrutalEnabled = prefs.getBoolean("sb_brutal", d.singBoxBrutalEnabled),
            singBoxBrutalUpMbps = prefs.getInt("sb_brutal_up", d.singBoxBrutalUpMbps),
            singBoxBrutalDownMbps = prefs.getInt("sb_brutal_down", d.singBoxBrutalDownMbps),
            singBoxTlsFragment = prefs.getBoolean("sb_tls_fragment", d.singBoxTlsFragment),
            singBoxTlsRecordFragment = prefs.getBoolean("sb_record_fragment", d.singBoxTlsRecordFragment),
            singBoxUtlsFingerprint = prefs.getString("sb_utls", d.singBoxUtlsFingerprint) ?: d.singBoxUtlsFingerprint,
            singBoxUdpOverTcp = prefs.getBoolean("sb_uot", d.singBoxUdpOverTcp),
            singBoxTunStack = prefs.enum("sb_stack", d.singBoxTunStack),
            singBoxStrictRoute = prefs.getBoolean("sb_strict_route", d.singBoxStrictRoute),
            singBoxEndpointIndependentNat = prefs.getBoolean("sb_eim_nat", d.singBoxEndpointIndependentNat),
            singBoxStoreCache = prefs.getBoolean("sb_cache", d.singBoxStoreCache),
            singBoxNtpEnabled = prefs.getBoolean("sb_ntp", d.singBoxNtpEnabled),
            singBoxNtpServer = prefs.getString("sb_ntp_server", d.singBoxNtpServer) ?: d.singBoxNtpServer,
            muxEnabled = prefs.getBoolean("mux", d.muxEnabled),
            muxConcurrency = prefs.getInt("mux_concurrency", d.muxConcurrency),
            muxXudpConcurrency = prefs.getInt("mux_xudp_concurrency", d.muxXudpConcurrency),
            muxXudpQuic = prefs.enum("mux_xudp_quic", d.muxXudpQuic),

            fragmentEnabled = prefs.getBoolean("fragment", d.fragmentEnabled),
            fragmentPackets = prefs.enum("fragment_packets", d.fragmentPackets),
            fragmentLength = prefs.str("fragment_length", d.fragmentLength),
            fragmentInterval = prefs.str("fragment_interval", d.fragmentInterval),
            fragmentMaxSplit = prefs.getInt("fragment_maxsplit", d.fragmentMaxSplit),

            autoSelectCheckSeconds = prefs.getInt("auto_check_seconds", d.autoSelectCheckSeconds),
            autoSelectSweepMinutes = prefs.getInt("auto_sweep_minutes", d.autoSelectSweepMinutes),
            autoSelectSwitchMargin = prefs.getInt("auto_switch_margin", d.autoSelectSwitchMargin),
            autoSelectRetry = prefs.getBoolean("auto_retry", d.autoSelectRetry),

            autoConnectOnBoot = prefs.getBoolean("auto_connect_boot", d.autoConnectOnBoot),
            delayTestUrl = prefs.str("delay_url", d.delayTestUrl),
            realPingConcurrency = prefs.getInt("real_ping_concurrency", d.realPingConcurrency),
            ipApiUrl = prefs.str("ip_api", d.ipApiUrl),
            useRealDelayForTests = prefs.getBoolean("real_delay", d.useRealDelayForTests),

            lastUpdateCheckMs = prefs.getLong("last_update_check", d.lastUpdateCheckMs),
            dismissedUpdateVersion = prefs.str("dismissed_update_version", d.dismissedUpdateVersion),
            dismissedUpdateAtMs = prefs.getLong("dismissed_update_at", d.dismissedUpdateAtMs),
            successfulConnections = prefs.getInt("successful_connections", d.successfulConnections),
            ratePromptLastShownMs = prefs.getLong("rate_prompt_last", d.ratePromptLastShownMs),
            rateNeverAsk = prefs.getBoolean("rate_never_ask", d.rateNeverAsk),

            geoFilesSource = prefs.enum("geo_source", d.geoFilesSource),

            autoUpdateSubscriptions = prefs.getBoolean("auto_sub", d.autoUpdateSubscriptions),
            subscriptionUpdateIntervalHours = prefs.getInt("sub_interval", d.subscriptionUpdateIntervalHours),
            autoTestAfterUpdate = prefs.getBoolean("auto_test_after_update", d.autoTestAfterUpdate),
            autoRemoveInvalidAfterTest = prefs.getBoolean("auto_remove_invalid", d.autoRemoveInvalidAfterTest),
            autoSortAfterTest = prefs.getBoolean("auto_sort_after_test", d.autoSortAfterTest),

            dnsGlobalResolverEnabled = prefs.getBoolean("dns_override", d.dnsGlobalResolverEnabled),
            dnsGlobalResolvers = prefs.str("dns_override_list", d.dnsGlobalResolvers),
            dnsPoolEnabled = prefs.getBoolean("dns_pool", d.dnsPoolEnabled),
            dnsPoolText = prefs.str("dns_pool_text", d.dnsPoolText),
            dnsPoolFullVerification = prefs.getBoolean("dns_pool_verify", d.dnsPoolFullVerification),
            remoteDnsMode = prefs.str("remote_dns_mode", d.remoteDnsMode),
            remoteDnsPrimary = prefs.str("remote_dns_primary", d.remoteDnsPrimary),
            remoteDnsFallback = prefs.str("remote_dns_fallback", d.remoteDnsFallback),
            dnsWorkerMode = prefs.str("dns_worker_mode", d.dnsWorkerMode),
            preventDnsFallback = prefs.getBoolean("prevent_dns_fallback", d.preventDnsFallback),

            torBridgesMode = prefs.str("tor_bridges_mode", d.torBridgesMode),
            torBridgeTransport = prefs.str("tor_bridge_transport", d.torBridgeTransport),
            torOwnBridges = prefs.str("tor_own_bridges", d.torOwnBridges),
            torEntryNodes = prefs.str("tor_entry", d.torEntryNodes),
            torExitNodes = prefs.str("tor_exit", d.torExitNodes),
            torExcludeNodes = prefs.str("tor_exclude", d.torExcludeNodes),
            torExcludeExitNodes = prefs.str("tor_exclude_exit", d.torExcludeExitNodes),
            torStrictNodes = prefs.getBoolean("tor_strict", d.torStrictNodes),
            torVirtualAddrNetwork = prefs.str("tor_vaddr", d.torVirtualAddrNetwork),
            torHardwareAccel = prefs.getBoolean("tor_hwaccel", d.torHardwareAccel),
            torAvoidDiskWrites = prefs.getBoolean("tor_avoiddisk", d.torAvoidDiskWrites),
            torConnectionPadding = prefs.getBoolean("tor_padding", d.torConnectionPadding),
            torReducedConnectionPadding = prefs.getBoolean("tor_reduced_padding", d.torReducedConnectionPadding),
            torFascistFirewall = prefs.getBoolean("tor_fascist", d.torFascistFirewall),
            torNewCircuitPeriod = prefs.getInt("tor_new_circuit", d.torNewCircuitPeriod),
            torMaxCircuitDirtiness = prefs.getInt("tor_max_dirty", d.torMaxCircuitDirtiness),
            torDormantClientTimeout = prefs.getInt("tor_dormant", d.torDormantClientTimeout),
            torEnforceDistinctSubnets = prefs.getBoolean("tor_distinct", d.torEnforceDistinctSubnets),
            torTrackHostExits = prefs.getBoolean("tor_track_host", d.torTrackHostExits),
            torClientUseIPv4 = prefs.getBoolean("tor_ipv4", d.torClientUseIPv4),
            torClientUseIPv6 = prefs.getBoolean("tor_ipv6", d.torClientUseIPv6),
            torIsolateUid = prefs.getBoolean("tor_iso_uid", d.torIsolateUid),
            torIsolateDestAddr = prefs.getBoolean("tor_iso_addr", d.torIsolateDestAddr),
            torIsolateDestPort = prefs.getBoolean("tor_iso_port", d.torIsolateDestPort),
            torSnowflakeRendezvous = prefs.str("tor_rendezvous", d.torSnowflakeRendezvous),
            torSnowflakeStun = prefs.str("tor_stun", d.torSnowflakeStun),
            torEnabledBridges = prefs.str("tor_enabled_bridges", d.torEnabledBridges),
            torFakeSniEnabled = prefs.getBoolean("tor_fakesni", d.torFakeSniEnabled),
            torFakeSniHosts = prefs.str("tor_fakesni_hosts", d.torFakeSniHosts),

            sshCipher = prefs.str("ssh_cipher", d.sshCipher),
            sshCompression = prefs.getBoolean("ssh_compression", d.sshCompression),
            sshMaxChannels = prefs.getInt("ssh_max_channels", d.sshMaxChannels),

            killSwitch = prefs.getBoolean("kill_switch", d.killSwitch),

            ai = prefs.getString("ai_settings", null)
                ?.let { runCatching { aiJson.decodeFromString(AiSettings.serializer(), it) }.getOrNull() }
                ?: d.ai,
        )
    }

    private val aiJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        persist(next)
    }

    private fun persist(s: AppSettings) {
        prefs.edit().apply {
            putString("theme_mode", s.themeMode.name)
            putBoolean("dynamic_color", s.dynamicColor)
            putInt("accent_color", s.accentColor)
            putString("color_style", s.colorStyle.name)
            putString("color_contrast", s.colorContrast.name)
            putString("app_design", s.appDesign.name)
            putBoolean("amoled_black", s.amoledBlack)
            putString("language", s.language.name)
            putArgb("custom_color_download", s.customDownloadColor)
            putArgb("custom_color_upload", s.customUploadColor)
            putArgb("custom_color_download_tile", s.customDownloadTileColor)
            putArgb("custom_color_upload_tile", s.customUploadTileColor)
            putArgb("custom_color_connect", s.customConnectColor)
            putArgb("custom_color_connected", s.customConnectedColor)
            putArgb("custom_color_tap_hint", s.customTapHintColor)
            putArgb("custom_color_server_card", s.customServerCardColor)
            putArgb("custom_color_server_active", s.customServerActiveColor)
            putArgb("custom_color_config_card", s.customConfigCardColor)
            putArgb("custom_color_config_card_text", s.customConfigCardTextColor)
            putString("connect_button_style", s.connectButtonStyle.name)
            putString("nav_bar_style", s.navBarStyle.name)
            putString("theme_profile_id", s.themeProfileId)
            putString("card_corner_style", s.cardCornerStyle.name)
            putString("list_density", s.listDensity.name)
            putString("ui_font_scale", s.uiFontScale.name)
            putBoolean("show_server_ping", s.showServerPing)
            putBoolean("show_server_usage", s.showServerUsage)
            putBoolean("monospace_address", s.monospaceAddress)
            putBoolean("speed_notif", s.speedInNotification)
            putBoolean("live_notif", s.liveNotification)
            putString("notif_chip", s.notifChip.name)
            putInt("onboarding_version", s.onboardingVersion)
            putString("screen_transition", s.screenTransition.name)
            putBoolean("confirm_remove", s.confirmRemove)
            putBoolean("home_after_select", s.homeAfterSelect)
            putBoolean("traffic_tiles", s.showTrafficTiles)
            putBoolean("traffic_tiles_top", s.trafficTilesAboveHero)
            putString("traffic_tile_size", s.trafficTileSize.name)
            putString("traffic_card_style", s.trafficCardStyle.name)
            putBoolean("double_column", s.doubleColumnDisplay)
            putBoolean("group_all", s.groupAllDisplay)
            putBoolean("reduce_motion", s.reduceMotion)
            putBoolean("speed_fab_at_end", s.speedFabAtEnd)
            putFloat("speed_fab_y", s.speedFabY)
            putString("verified_origin_code", s.verifiedOriginCode)
            putString("verified_origin_label", s.verifiedOriginLabel)

            remove("real_origin_code")
            remove("real_origin_label")

            putString("run_mode", s.runMode.name)

            putBoolean("ipv6", s.enableIpv6)
            putBoolean("prefer_ipv6", s.preferIpv6)
            putBoolean("local_dns", s.localDnsEnabled)
            putBoolean("fake_dns", s.fakeDnsEnabled)
            putString("vpn_dns", s.vpnDns)
            putBoolean("append_http_proxy", s.appendHttpProxy)
            putString("vpn_bypass_lan", s.vpnBypassLan.name)
            putString("vpn_iface_addr", s.vpnInterfaceAddress.name)
            putInt("vpn_mtu", s.vpnMtu)
            putBoolean("use_zeptun", s.useZepTun)
            putString("hev_log_level", s.hevTunLogLevel.name)
            putString("hev_rw_timeout", s.hevTunRwTimeout)

            putBoolean("sniffing", s.sniffingEnabled)
            putBoolean("route_only", s.routeOnly)
            putBoolean("enable_local_proxy", s.enableLocalProxy)
            putBoolean("proxy_sharing", s.proxySharing)
            putBoolean("dynamic_socks_port", s.dynamicSocksPort)
            putInt("socks_port", s.socksPort)
            putInt("local_proxy_port", s.localProxyPort)
            putString("socks_username", s.socksUsername)
            putString("socks_password", s.socksPassword)
            putBoolean("lan_share_auth", s.lanShareAuth)
            putBoolean("socks_udp", s.socksEnableUdp)
            putString("remote_dns", s.remoteDns)
            putString("direct_dns", s.directDns)
            putString("dns_hosts", s.dnsHosts)
            putString("log_level", s.logLevel.name)
            putString("outbound_resolve", s.outboundDomainResolve.name)

            putString("domain_strategy", s.domainStrategy.name)
            putString("routing_mode", s.routingMode.name)
            putBoolean("block_ads", s.blockAds)
            putString("custom_proxy_rules", s.customProxyRules)
            putString("custom_direct_rules", s.customDirectRules)
            putString("custom_block_rules", s.customBlockRules)
            putString("rulesets", json.encodeToString(RULESETS, s.rulesets))
            putBoolean("routing_migrated", s.routingMigrated)
            putBoolean("routing_seeded", s.routingSeeded)

            putBoolean("per_app", s.perAppProxyEnabled)
            putBoolean("per_app_bypass", s.perAppBypassMode)
            putStringSet("per_app_set", s.perAppPackages)

            putBoolean("sb_mux", s.singBoxMuxEnabled)
            putString("sb_mux_protocol", s.singBoxMuxProtocol.name)
            putInt("sb_mux_connections", s.singBoxMuxMaxConnections)
            putBoolean("sb_mux_padding", s.singBoxMuxPadding)
            putBoolean("sb_brutal", s.singBoxBrutalEnabled)
            putInt("sb_brutal_up", s.singBoxBrutalUpMbps)
            putInt("sb_brutal_down", s.singBoxBrutalDownMbps)
            putBoolean("sb_tls_fragment", s.singBoxTlsFragment)
            putBoolean("sb_record_fragment", s.singBoxTlsRecordFragment)
            putString("sb_utls", s.singBoxUtlsFingerprint)
            putBoolean("sb_uot", s.singBoxUdpOverTcp)
            putString("sb_stack", s.singBoxTunStack.name)
            putBoolean("sb_strict_route", s.singBoxStrictRoute)
            putBoolean("sb_eim_nat", s.singBoxEndpointIndependentNat)
            putBoolean("sb_cache", s.singBoxStoreCache)
            putBoolean("sb_ntp", s.singBoxNtpEnabled)
            putString("sb_ntp_server", s.singBoxNtpServer)
            putBoolean("mux", s.muxEnabled)
            putInt("mux_concurrency", s.muxConcurrency)
            putInt("mux_xudp_concurrency", s.muxXudpConcurrency)
            putString("mux_xudp_quic", s.muxXudpQuic.name)

            putBoolean("fragment", s.fragmentEnabled)
            putString("fragment_packets", s.fragmentPackets.name)
            putString("fragment_length", s.fragmentLength)
            putString("fragment_interval", s.fragmentInterval)
            putInt("fragment_maxsplit", s.fragmentMaxSplit)

            putInt("auto_check_seconds", s.autoSelectCheckSeconds)
            putInt("auto_sweep_minutes", s.autoSelectSweepMinutes)
            putInt("auto_switch_margin", s.autoSelectSwitchMargin)
            putBoolean("auto_retry", s.autoSelectRetry)

            putBoolean("auto_connect_boot", s.autoConnectOnBoot)
            putString("delay_url", s.delayTestUrl)
            putInt("real_ping_concurrency", s.realPingConcurrency)
            putString("ip_api", s.ipApiUrl)
            putBoolean("real_delay", s.useRealDelayForTests)

            putLong("last_update_check", s.lastUpdateCheckMs)
            putString("dismissed_update_version", s.dismissedUpdateVersion)
            putLong("dismissed_update_at", s.dismissedUpdateAtMs)
            putInt("successful_connections", s.successfulConnections)
            putLong("rate_prompt_last", s.ratePromptLastShownMs)
            putBoolean("rate_never_ask", s.rateNeverAsk)

            putString("geo_source", s.geoFilesSource.name)

            putBoolean("auto_sub", s.autoUpdateSubscriptions)
            putInt("sub_interval", s.subscriptionUpdateIntervalHours)
            putBoolean("auto_test_after_update", s.autoTestAfterUpdate)
            putBoolean("auto_remove_invalid", s.autoRemoveInvalidAfterTest)
            putBoolean("auto_sort_after_test", s.autoSortAfterTest)

            putBoolean("dns_override", s.dnsGlobalResolverEnabled)
            putString("dns_override_list", s.dnsGlobalResolvers)
            putBoolean("dns_pool", s.dnsPoolEnabled)
            putString("dns_pool_text", s.dnsPoolText)
            putBoolean("dns_pool_verify", s.dnsPoolFullVerification)
            putString("remote_dns_mode", s.remoteDnsMode)
            putString("remote_dns_primary", s.remoteDnsPrimary)
            putString("remote_dns_fallback", s.remoteDnsFallback)
            putString("dns_worker_mode", s.dnsWorkerMode)
            putBoolean("prevent_dns_fallback", s.preventDnsFallback)

            putString("tor_bridges_mode", s.torBridgesMode)
            putString("tor_bridge_transport", s.torBridgeTransport)
            putString("tor_own_bridges", s.torOwnBridges)
            putString("tor_entry", s.torEntryNodes)
            putString("tor_exit", s.torExitNodes)
            putString("tor_exclude", s.torExcludeNodes)
            putString("tor_exclude_exit", s.torExcludeExitNodes)
            putBoolean("tor_strict", s.torStrictNodes)
            putString("tor_vaddr", s.torVirtualAddrNetwork)
            putBoolean("tor_hwaccel", s.torHardwareAccel)
            putBoolean("tor_avoiddisk", s.torAvoidDiskWrites)
            putBoolean("tor_padding", s.torConnectionPadding)
            putBoolean("tor_reduced_padding", s.torReducedConnectionPadding)
            putBoolean("tor_fascist", s.torFascistFirewall)
            putInt("tor_new_circuit", s.torNewCircuitPeriod)
            putInt("tor_max_dirty", s.torMaxCircuitDirtiness)
            putInt("tor_dormant", s.torDormantClientTimeout)
            putBoolean("tor_distinct", s.torEnforceDistinctSubnets)
            putBoolean("tor_track_host", s.torTrackHostExits)
            putBoolean("tor_ipv4", s.torClientUseIPv4)
            putBoolean("tor_ipv6", s.torClientUseIPv6)
            putBoolean("tor_iso_uid", s.torIsolateUid)
            putBoolean("tor_iso_addr", s.torIsolateDestAddr)
            putBoolean("tor_iso_port", s.torIsolateDestPort)
            putString("tor_rendezvous", s.torSnowflakeRendezvous)
            putString("tor_stun", s.torSnowflakeStun)
            putString("tor_enabled_bridges", s.torEnabledBridges)
            putBoolean("tor_fakesni", s.torFakeSniEnabled)
            putString("tor_fakesni_hosts", s.torFakeSniHosts)

            putString("ssh_cipher", s.sshCipher)
            putBoolean("ssh_compression", s.sshCompression)
            putInt("ssh_max_channels", s.sshMaxChannels)

            putBoolean("kill_switch", s.killSwitch)
            putString("ai_settings", aiJson.encodeToString(AiSettings.serializer(), s.ai))
        }.apply()
    }

    private fun SharedPreferences.str(key: String, fallback: String): String =
        getString(key, null) ?: fallback

    private fun SharedPreferences.rulesets(key: String, fallback: List<RulesetItem>): List<RulesetItem> {
        val raw = getString(key, null) ?: return fallback
        return runCatching { json.decodeFromString(RULESETS, raw) }.getOrDefault(fallback)
    }

    private fun SharedPreferences.argb(key: String): Long? =
        getLong(key, -1L).takeIf { it >= 0L }

    private fun SharedPreferences.Editor.putArgb(key: String, value: Long?) {
        putLong(key, value ?: -1L)
    }

    private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, fallback: T): T {
        val name = getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
    }

    companion object {
        private const val PREFS_NAME = "zed_settings"
        private const val KEY_ROUTING_DEFAULTS_MIGRATED = "routing_defaults_migrated"
        private const val LEGACY_RENDEZVOUS = "amp"
        private const val LEGACY_DIRECT_DNS = "223.5.5.5"

        private val json = Json { ignoreUnknownKeys = true }
        private val RULESETS = ListSerializer(RulesetItem.serializer())

        fun readLanguageTag(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs.getString("language", null) ?: return null
            return runCatching { AppLanguage.valueOf(name).tag }.getOrNull()
        }
    }
}
