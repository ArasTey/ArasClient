@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
)

package dev.cluvex.zedsecure.ui.settings

import dev.cluvex.zedsecure.ui.platform.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import dev.cluvex.zedsecure.ui.motion.transitionDecoration
import dev.cluvex.zedsecure.ui.onboarding.TourTargets
import dev.cluvex.zedsecure.ui.onboarding.tourTargetInPage
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.intl.Locale
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.platform.AppInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.cluvex.zedsecure.ui.platform.AutomaticRendering
import dev.cluvex.zedsecure.ui.platform.LocalPlatform
import dev.cluvex.zedsecure.ui.theme.applyThemeProfile
import dev.cluvex.zedsecure.domain.model.AppLanguage
import dev.cluvex.zedsecure.data.update.Distribution
import dev.cluvex.zedsecure.domain.model.AppSettings
import dev.cluvex.zedsecure.domain.model.RenderingMode
import dev.cluvex.zedsecure.domain.model.CardCornerStyle
import dev.cluvex.zedsecure.domain.model.ConnectButtonStyle
import dev.cluvex.zedsecure.domain.model.DomainStrategy
import dev.cluvex.zedsecure.domain.model.ListDensity
import dev.cluvex.zedsecure.domain.model.FragmentPackets
import dev.cluvex.zedsecure.domain.model.HevLogLevel
import dev.cluvex.zedsecure.domain.model.LogLevel
import dev.cluvex.zedsecure.domain.model.NavBarStyle
import dev.cluvex.zedsecure.domain.model.NotifChip
import dev.cluvex.zedsecure.domain.model.OutboundDomainResolve
import dev.cluvex.zedsecure.domain.model.RunMode
import dev.cluvex.zedsecure.domain.model.ThemeMode
import dev.cluvex.zedsecure.domain.model.ColorContrast
import dev.cluvex.zedsecure.domain.model.AppDesign
import dev.cluvex.zedsecure.domain.model.TrafficCardStyle
import dev.cluvex.zedsecure.domain.model.TrafficTileSize
import dev.cluvex.zedsecure.domain.model.UiFontScale
import dev.cluvex.zedsecure.domain.model.VpnBypassLan
import dev.cluvex.zedsecure.domain.model.VpnInterfaceAddress
import dev.cluvex.zedsecure.domain.model.SingBoxMuxProtocol
import dev.cluvex.zedsecure.domain.model.SingBoxStack
import dev.cluvex.zedsecure.domain.model.XudpQuic
import dev.cluvex.zedsecure.ui.theme.argbLongToColor
import dev.cluvex.zedsecure.ui.theme.toPersonalization
import dev.cluvex.zedsecure.ui.theme.hasPersonalization
import dev.cluvex.zedsecure.ui.theme.resetPersonalization
import dev.cluvex.zedsecure.ui.update.ManualUpdateCheckHost
import dev.cluvex.zedsecure.domain.config.LocalPorts
import dev.cluvex.zedsecure.domain.config.SingBoxTuning
import dev.cluvex.zedsecure.domain.config.effectiveLanSocksPort
import dev.cluvex.zedsecure.domain.config.effectiveLocalProxyPort
import dev.cluvex.zedsecure.platform.secureRandomToken

enum class SettingsPage {
    Root, Ui, Vpn, Core, Routing, PerApp, Mux, Fragment, Observatory, Advanced, Mode, Assets, SingBox,
    DnsProtocols, Tor, TorBridges, Ssh, SpeedTest, Map, Privacy, Monitor, Ai, AiChat,
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
    initialPage: SettingsPage = SettingsPage.Root,
    onInitialPageConsumed: () -> Unit = {},

    serverTargets: List<Pair<String, String>> = emptyList(),

    groupTargets: List<Pair<String, String>> = emptyList(),

    dnsTunnelActive: Boolean = false,

    onDeepLinkBack: (() -> Unit)? = null,

    onReplayTour: (() -> Unit)? = null,

    mtuHint: dev.cluvex.zedsecure.data.net.MtuServerHint? = null,

    ai: dev.cluvex.zedsecure.ui.settings.AiSection? = null,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    var deepLinked by rememberSaveable { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(initialPage) {
        if (initialPage != SettingsPage.Root) {
            page = initialPage
            deepLinked = true
            onInitialPageConsumed()
        }
    }

    val goUp: () -> Unit = {
        when {
            deepLinked && onDeepLinkBack != null -> {
                deepLinked = false

                page = SettingsPage.Root
                onDeepLinkBack()
            }
            page == SettingsPage.TorBridges -> page = SettingsPage.Tor
            else -> {
                deepLinked = false
                page = SettingsPage.Root
            }
        }
    }

    if (showAbout) AboutSheet(onDismiss = { showAbout = false })

    BackHandler(enabled = page != SettingsPage.Root) { goUp() }

    val stateHolder = rememberSaveableStateHolder()

    val goBack: (() -> Unit)? = if (page == SettingsPage.Root) null else goUp
    dev.cluvex.zedsecure.ui.components.ProvidePageBack(goBack) {
    val sign = dev.cluvex.zedsecure.ui.motion.layoutSign()
    val scrim = androidx.compose.material3.MaterialTheme.colorScheme.background
    val statusBar = contentPadding.calculateTopPadding()
    androidx.compose.animation.SharedTransitionLayout(Modifier.statusBarScrim(scrim, statusBar)) {
    val sharedScope = this
    androidx.compose.animation.AnimatedContent(
        targetState = page,
        transitionSpec = {
            dev.cluvex.zedsecure.ui.motion.screenTransition(
                style = settings.screenTransition,
                forward = pageDepth(targetState) >= pageDepth(initialState),
                direction = sign,
                reduceMotion = settings.reduceMotion,
            ).using(androidx.compose.animation.SizeTransform(clip = false))
        },
        label = "settings-page",
    ) { shown ->
    val visibilityScope = this
    val decoration = transitionDecoration(
        style = settings.screenTransition,
        forward = pageDepth(shown) >= pageDepth(page),
        direction = sign,
        reduceMotion = settings.reduceMotion,
    )
    androidx.compose.foundation.layout.Box(decoration) {
    val artTransition = remember(sharedScope, visibilityScope) { ArtTransition(sharedScope, visibilityScope) }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalArtTransition provides artTransition.takeUnless { settings.reduceMotion },
    ) {
    stateHolder.SaveableStateProvider(shown) {
        when (shown) {
            SettingsPage.Root -> RootPage(
                settings = settings,
                contentPadding = contentPadding,
                modifier = modifier,
                onOpen = { page = it },
                onAbout = { showAbout = true },
                onReplayTour = onReplayTour,
                hasAi = ai != null,
            )
            SettingsPage.Ui -> UiPage(settings, contentPadding, modifier, onUpdate, onLanguage)
            SettingsPage.Vpn -> VpnPage(settings, contentPadding, modifier, onUpdate, mtuHint)
            SettingsPage.Core -> CorePage(settings, contentPadding, modifier, onUpdate)
            SettingsPage.Mux -> MuxPage(settings, contentPadding, modifier, onUpdate)
            SettingsPage.SingBox -> SingBoxPage(settings, contentPadding, modifier, onUpdate)
            SettingsPage.Fragment -> FragmentPage(settings, contentPadding, modifier, onUpdate)
            SettingsPage.Observatory -> ObservatoryPage(settings, contentPadding, modifier, onUpdate)
            SettingsPage.Advanced -> AdvancedPage(settings, contentPadding, modifier, onUpdate)
            SettingsPage.Mode -> ModePage(settings, contentPadding, modifier, onUpdate)
            SettingsPage.Routing -> RoutingScreen(settings, contentPadding, onUpdate, modifier, serverTargets, groupTargets)
            SettingsPage.PerApp -> PerAppProxyScreen(settings, contentPadding, onUpdate, modifier)
            SettingsPage.Assets -> AssetsScreen(settings, contentPadding, modifier, onUpdate)
            SettingsPage.DnsProtocols -> DnsProtocolsPage(settings, contentPadding, modifier, onUpdate)
            SettingsPage.Tor -> TorPage(settings, contentPadding, modifier, onUpdate) { page = it }
            SettingsPage.TorBridges -> TorBridgesScreen(settings, contentPadding, modifier, onUpdate)
            SettingsPage.Ssh -> SshPage(settings, contentPadding, modifier, onUpdate)
            SettingsPage.Privacy -> PrivacyPage(contentPadding, modifier)
            SettingsPage.Monitor -> LiveMonitorPage(contentPadding, modifier)
            SettingsPage.Ai -> ai?.let {
                SettingsPageScaffold(
                    title = stringResource(Res.string.ai_title),
                    art = SettingsArts.Ai,
                    subtitle = stringResource(Res.string.ai_subtitle),
                    contentPadding = contentPadding,
                    modifier = modifier,
                ) {
                    AiAssistantPage(
                        settings = it.settings,
                        onUpdate = it.onUpdate,
                        onOpenChat = { page = SettingsPage.AiChat },
                        onOpenKeyPage = it.onOpenUrl,
                    )
                }
            }

            SettingsPage.AiChat -> ai?.let {
                AiChatScreen(
                    settings = it.settings,
                    bridge = it.bridge,
                    voiceController = it.voiceController,
                    languageName = it.languageName,
                    languageNative = it.languageNative,
                    platform = it.platform,
                    appVersion = it.appVersion,
                    contentPadding = contentPadding,
                    modifier = modifier,
                )
            }
            SettingsPage.SpeedTest ->
                dev.cluvex.zedsecure.ui.speedtest.SpeedTestScreen(contentPadding, modifier, dnsTunnelActive)
            SettingsPage.Map -> dev.cluvex.zedsecure.ui.map.MapScreen(
                contentPadding = contentPadding,
                modifier = modifier,
                reduceMotion = settings.reduceMotion,
                ipApiUrl = settings.ipApiUrl,
                personalization = settings.toPersonalization(),
                savedOriginCode = settings.verifiedOriginCode,
                savedOriginLabel = settings.verifiedOriginLabel,
                onRealLocation = { code, label ->
                    if (code != settings.verifiedOriginCode || label != settings.verifiedOriginLabel) {
                        onUpdate { it.copy(verifiedOriginCode = code, verifiedOriginLabel = label) }
                    }
                },
            )
        }
    }
    }
    }
    }
    }
    }
}

private val ScrimFade = 14.dp

private fun Modifier.statusBarScrim(color: androidx.compose.ui.graphics.Color, height: androidx.compose.ui.unit.Dp): Modifier =
    if (height <= 0.dp) {
        this
    } else {
        drawWithContent {
            drawContent()
            val band = height.toPx()
            val fade = ScrimFade.toPx()
            drawRect(color, size = androidx.compose.ui.geometry.Size(size.width, band))
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)), startY = band, endY = band + fade),
                topLeft = androidx.compose.ui.geometry.Offset(0f, band),
                size = androidx.compose.ui.geometry.Size(size.width, fade),
            )
        }
    }

private fun pageDepth(page: SettingsPage): Int = when (page) {
    SettingsPage.Root -> 0
    SettingsPage.TorBridges -> 2
    else -> 1
}

@Composable
private fun RootPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onOpen: (SettingsPage) -> Unit,
    onAbout: () -> Unit,
    onReplayTour: (() -> Unit)?,

    hasAi: Boolean,
) {
    val platform = LocalPlatform.current

    var checkTrigger by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var checking by androidx.compose.runtime.remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SettingsHero(
            title = stringResource(Res.string.settings_title),
            tagline = stringResource(Res.string.settings_hero_tagline),
            reduceMotion = settings.reduceMotion,
        )

        SettingsGroup(
            stringResource(Res.string.settings_group_personal),
            modifier = Modifier.tourTargetInPage(TourTargets.SETTINGS_LOOK),
        ) {
            SettingsMenuRow(
                stringResource(Res.string.title_ui_settings),
                stringResource(Res.string.summary_ui_settings),
                art = SettingsArts.Appearance,
            ) { onOpen(SettingsPage.Ui) }
            SettingsMenuRow(
                stringResource(Res.string.title_mode_settings),
                stringResource(Res.string.summary_mode_settings),
                art = SettingsArts.Mode,
            ) { onOpen(SettingsPage.Mode) }
            SettingsMenuRow(
                stringResource(Res.string.monitor_title),
                stringResource(Res.string.monitor_subtitle),
                art = SettingsArts.Monitor,
            ) { onOpen(SettingsPage.Monitor) }
            if (hasAi) {
                SettingsMenuRow(
                    stringResource(Res.string.ai_title),
                    stringResource(Res.string.ai_subtitle),
                    art = SettingsArts.Ai,
                ) { onOpen(SettingsPage.Ai) }
            }
            if (onReplayTour != null) {
                SettingsMenuRow(
                    stringResource(Res.string.tour_replay_title),
                    stringResource(Res.string.tour_replay_subtitle),
                    art = SettingsArts.Tour,
                ) { onReplayTour() }
            }
        }

        SettingsGroup(
            stringResource(Res.string.routing_title),
            modifier = Modifier.tourTargetInPage(TourTargets.SETTINGS_ROUTING),
        ) {
            SettingsMenuRow(
                stringResource(Res.string.routing_title),
                stringResource(Res.string.routing_subtitle),
                art = SettingsArts.Routing,
            ) { onOpen(SettingsPage.Routing) }
            SettingsMenuRow(
                stringResource(Res.string.per_app_title),
                if (settings.perAppProxyEnabled) {
                    stringResource(Res.string.per_app_selected, settings.perAppPackages.size)
                } else {
                    stringResource(Res.string.per_app_mode_off)
                },
                art = SettingsArts.PerApp,
            ) { onOpen(SettingsPage.PerApp) }
            SettingsMenuRow(
                stringResource(Res.string.assets_title),
                stringResource(Res.string.assets_subtitle),
                art = SettingsArts.Assets,
            ) { onOpen(SettingsPage.Assets) }
        }

        SettingsGroup(
            stringResource(Res.string.title_tunnels_settings),
            modifier = Modifier.tourTargetInPage(TourTargets.SETTINGS_TUNNELS),
        ) {
            SettingsMenuRow(
                stringResource(Res.string.title_dns_protocols),
                stringResource(Res.string.summary_dns_protocols),
                art = SettingsArts.Dns,
            ) { onOpen(SettingsPage.DnsProtocols) }
            SettingsMenuRow(
                stringResource(Res.string.title_tor_settings),
                stringResource(Res.string.summary_tor_settings),
                art = SettingsArts.Tor,
            ) { onOpen(SettingsPage.Tor) }
            SettingsMenuRow(
                stringResource(Res.string.title_ssh_settings),
                stringResource(Res.string.summary_ssh_settings),
                art = SettingsArts.Ssh,
            ) { onOpen(SettingsPage.Ssh) }
        }

        SettingsGroup(
            stringResource(Res.string.title_core_settings),
            modifier = Modifier.tourTargetInPage(TourTargets.SETTINGS_CORE),
        ) {
            SettingsMenuRow(
                stringResource(Res.string.title_vpn_settings),
                stringResource(Res.string.summary_vpn_settings),
                art = SettingsArts.Vpn,
            ) { onOpen(SettingsPage.Vpn) }
            SettingsMenuRow(
                stringResource(Res.string.title_core_settings),
                stringResource(Res.string.summary_core_settings),
                art = SettingsArts.Core,
            ) { onOpen(SettingsPage.Core) }
            SettingsMenuRow(
                stringResource(Res.string.title_mux_settings),
                stringResource(Res.string.summary_mux_settings),
                art = SettingsArts.Mux,
            ) { onOpen(SettingsPage.Mux) }
            SettingsMenuRow(
                stringResource(Res.string.title_fragment_settings),
                stringResource(Res.string.summary_fragment_settings),
                art = SettingsArts.Fragment,
            ) { onOpen(SettingsPage.Fragment) }
            SettingsMenuRow(
                stringResource(Res.string.title_singbox_settings),
                stringResource(Res.string.summary_singbox_settings),
                art = SettingsArts.SingBox,
            ) { onOpen(SettingsPage.SingBox) }
            SettingsMenuRow(
                stringResource(Res.string.title_observatory_settings),
                stringResource(Res.string.summary_observatory_settings),
                art = SettingsArts.Observatory,
            ) { onOpen(SettingsPage.Observatory) }
            SettingsMenuRow(
                stringResource(Res.string.title_advanced),
                stringResource(Res.string.summary_advanced),
                art = SettingsArts.Advanced,
            ) { onOpen(SettingsPage.Advanced) }
        }

        SettingsGroup(
            stringResource(Res.string.settings_group_tools),
            modifier = Modifier.tourTargetInPage(TourTargets.SETTINGS_TOOLS),
        ) {
            SettingsMenuRow(
                stringResource(Res.string.map_title),
                stringResource(Res.string.map_settings_sub),
                art = SettingsArts.Map,
            ) { onOpen(SettingsPage.Map) }
            SettingsMenuRow(
                stringResource(Res.string.title_speedtest),
                stringResource(Res.string.summary_speedtest),
                art = SettingsArts.SpeedTest,
            ) { onOpen(SettingsPage.SpeedTest) }
        }

        SettingsGroup(
            stringResource(Res.string.settings_about),
            modifier = Modifier.tourTargetInPage(TourTargets.SETTINGS_ABOUT),
        ) {
            val fromPlay = platform.distribution == Distribution.PlayStore
            SettingsMenuRow(
                stringResource(Res.string.update_check_title),
                when {
                    checking -> stringResource(Res.string.update_checking)
                    fromPlay -> stringResource(Res.string.update_check_summary)
                    else -> stringResource(Res.string.update_check_summary_github)
                },
                art = SettingsArts.Update,
                trailing = if (checking) {
                    { SettingsBusyIndicator() }
                } else {
                    null
                },
            ) { if (!checking) { checking = true; checkTrigger++ } }
            SettingsMenuRow(
                stringResource(if (fromPlay) Res.string.rate_title else Res.string.rate_title_github),
                stringResource(if (fromPlay) Res.string.rate_summary else Res.string.rate_summary_github),
                art = SettingsArts.Rate,
            ) { platform.openStorePage() }
            SettingsMenuRow(
                stringResource(Res.string.privacy_title),
                stringResource(Res.string.privacy_settings_sub),
                art = SettingsArts.Privacy,
            ) { onOpen(SettingsPage.Privacy) }
            SettingsMenuRow(
                stringResource(Res.string.settings_about),
                AppInfo.versionName,
                art = SettingsArts.About,
            ) { onAbout() }
        }

        ManualUpdateCheckHost(
            settings = settings,
            trigger = checkTrigger,
            onFinished = { checking = false },
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun DnsProtocolsPage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.title_dns_protocols),
        art = SettingsArts.Dns,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SettingsGroup {
            SettingsInfoRow(
                stringResource(Res.string.dns_protocols_about_title),
                stringResource(Res.string.dns_protocols_about_body),
            )
        }

        SettingsGroup(stringResource(Res.string.dns_global_resolver_group)) {
            SettingsSwitchRow(
                title = stringResource(Res.string.dns_override),
                summary = stringResource(Res.string.dns_override_sum),
                checked = s.dnsGlobalResolverEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(dnsGlobalResolverEnabled = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.dns_resolver_ips),
                value = s.dnsGlobalResolvers,
                enabled = s.dnsGlobalResolverEnabled,
                placeholder = "8.8.8.8, 1.1.1.1",
                onValueChanged = { v -> onUpdate { it.copy(dnsGlobalResolvers = v) } },
            )
        }

        SettingsGroup(stringResource(Res.string.dns_pool_group)) {
            SettingsSwitchRow(
                title = stringResource(Res.string.dns_pool_enable),
                summary = stringResource(Res.string.dns_pool_enable_sum),
                checked = s.dnsPoolEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(dnsPoolEnabled = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.dns_pool_list),
                value = s.dnsPoolText,
                enabled = s.dnsPoolEnabled,
                placeholder = "8.8.8.8\n1.1.1.1",
                onValueChanged = { v -> onUpdate { it.copy(dnsPoolText = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.dns_pool_verify),
                summary = stringResource(
                    if (s.dnsPoolFullVerification) Res.string.dns_pool_verify_on else Res.string.dns_pool_verify_off,
                ),
                enabled = s.dnsPoolEnabled,
                checked = s.dnsPoolFullVerification,
                onCheckedChange = { v -> onUpdate { it.copy(dnsPoolFullVerification = v) } },
            )
        }

        SettingsGroup(stringResource(Res.string.dns_remote_group)) {
            SettingsListRow(
                title = stringResource(Res.string.dns_remote_server),
                options = listOf(
                    "default" to stringResource(Res.string.dns_remote_default),
                    "custom" to stringResource(Res.string.dns_remote_custom),
                ),
                selected = s.remoteDnsMode,
                onSelected = { v -> onUpdate { it.copy(remoteDnsMode = v) } },
            )
            if (s.remoteDnsMode == "custom") {
                SettingsEditRow(
                    title = stringResource(Res.string.dns_remote_primary), value = s.remoteDnsPrimary, placeholder = "9.9.9.9",
                    onValueChanged = { v -> onUpdate { it.copy(remoteDnsPrimary = v) } },
                )
                SettingsEditRow(
                    title = stringResource(Res.string.dns_remote_fallback), value = s.remoteDnsFallback, placeholder = "8.8.8.8",
                    onValueChanged = { v -> onUpdate { it.copy(remoteDnsFallback = v) } },
                )
            }
        }

        SettingsGroup(stringResource(Res.string.dns_behaviour_group)) {
            SettingsListRow(
                title = stringResource(Res.string.dns_workers),
                options = listOf(
                    "per_query" to stringResource(Res.string.dns_workers_per_query),
                    "two" to stringResource(Res.string.dns_workers_2),
                    "three" to stringResource(Res.string.dns_workers_3),
                    "five" to stringResource(Res.string.dns_workers_5),
                ),
                selected = s.dnsWorkerMode,
                onSelected = { v -> onUpdate { it.copy(dnsWorkerMode = v) } },
            )

            SettingsInfoRow(
                title = stringResource(Res.string.dns_prevent_fallback),
                body = stringResource(Res.string.dns_prevent_fallback_always),
            )
        }
    }
}

@Composable
private fun TorPage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.title_tor_settings),
        art = SettingsArts.Tor,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SettingsGroup(stringResource(Res.string.tor_bridges_group)) {
            SettingsListRow(
                title = stringResource(Res.string.tor_bridges_mode),
                options = listOf(
                    "none" to stringResource(Res.string.tor_bridges_none),
                    "default" to stringResource(Res.string.tor_bridges_default),
                    "own" to stringResource(Res.string.tor_bridges_own),
                ),
                selected = s.torBridgesMode,
                onSelected = { v -> onUpdate { it.copy(torBridgesMode = v) } },
            )
            if (s.torBridgesMode != "none") {
                val transports = if (s.torBridgesMode == "own") {
                    listOf("obfs4", "obfs3", "scramblesuit", "meek_lite", "snowflake", "conjure", "webtunnel", "vanilla")
                } else {
                    listOf("obfs4", "obfs3", "meek_lite", "snowflake", "conjure", "webtunnel", "vanilla")
                }
                SettingsListRow(
                    title = stringResource(Res.string.tor_bridge_transport),
                    options = transports.map { it to it },
                    selected = s.torBridgeTransport,
                    onSelected = { v -> onUpdate { it.copy(torBridgeTransport = v) } },
                )
                if (s.torBridgeTransport == "snowflake") {
                    SettingsListRow(
                        title = stringResource(Res.string.tor_rendezvous),
                        options = listOf("amp" to "AMP", "cdn77" to "CDN77", "amazon" to "AMAZON"),
                        selected = s.torSnowflakeRendezvous,
                        onSelected = { v -> onUpdate { it.copy(torSnowflakeRendezvous = v) } },
                    )
                    SettingsEditRow(
                        title = stringResource(Res.string.tor_snowflake_stun_hint),
                        value = s.torSnowflakeStun,
                        onValueChanged = { v -> onUpdate { it.copy(torSnowflakeStun = v) } },
                    )
                }
                if (s.torBridgesMode == "own") {
                    SettingsEditRow(
                        title = stringResource(Res.string.tor_own_bridges),
                        value = s.torOwnBridges,
                        placeholder = "obfs4 1.2.3.4:443 CERT… iat-mode=0",
                        onValueChanged = { v -> onUpdate { it.copy(torOwnBridges = v) } },
                    )
                } else {
                    SettingsMenuRow(
                        stringResource(Res.string.tor_bridges_title),
                        stringResource(Res.string.tor_bridges_browse_sum),
                    ) { onOpen(SettingsPage.TorBridges) }
                }
            }
        }

        SettingsGroup(stringResource(Res.string.tor_nodes_group)) {
            SettingsEditRow(
                title = stringResource(Res.string.tor_entry_nodes_t),
                value = s.torEntryNodes, placeholder = "{de},{nl}",
                onValueChanged = { v -> onUpdate { it.copy(torEntryNodes = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.tor_exit_nodes_t),
                value = s.torExitNodes, placeholder = "{us},{gb}",
                onValueChanged = { v -> onUpdate { it.copy(torExitNodes = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.tor_exclude_nodes_t),
                value = s.torExcludeNodes, placeholder = "{ru},{cn}",
                onValueChanged = { v -> onUpdate { it.copy(torExcludeNodes = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.tor_exclude_exit_nodes_t),
                value = s.torExcludeExitNodes, placeholder = "{ru},{cn}",
                onValueChanged = { v -> onUpdate { it.copy(torExcludeExitNodes = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_strict_nodes_t),
                summary = stringResource(Res.string.tor_strict_nodes_t_sum),
                checked = s.torStrictNodes,
                onCheckedChange = { v -> onUpdate { it.copy(torStrictNodes = v) } },
            )
        }

        SettingsGroup(stringResource(Res.string.tor_common_group)) {
            SettingsEditRow(
                title = stringResource(Res.string.tor_virtual_addr), value = s.torVirtualAddrNetwork,
                onValueChanged = { v -> onUpdate { it.copy(torVirtualAddrNetwork = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_hardware_accel),
                summary = stringResource(Res.string.tor_hardware_accel_sum),
                checked = s.torHardwareAccel,
                onCheckedChange = { v -> onUpdate { it.copy(torHardwareAccel = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_avoid_disk),
                summary = stringResource(Res.string.tor_avoid_disk_sum),
                checked = s.torAvoidDiskWrites,
                onCheckedChange = { v -> onUpdate { it.copy(torAvoidDiskWrites = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_conn_padding),
                summary = stringResource(Res.string.tor_conn_padding_sum),
                checked = s.torConnectionPadding,
                onCheckedChange = { v -> onUpdate { it.copy(torConnectionPadding = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_reduced_padding),
                summary = stringResource(Res.string.tor_reduced_padding_sum),
                checked = s.torReducedConnectionPadding,
                onCheckedChange = { v -> onUpdate { it.copy(torReducedConnectionPadding = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_fascist),
                summary = stringResource(Res.string.tor_fascist_sum),
                checked = s.torFascistFirewall,
                onCheckedChange = { v -> onUpdate { it.copy(torFascistFirewall = v) } },
            )
            IntEditRow(stringResource(Res.string.tor_new_circuit), s.torNewCircuitPeriod) { v -> onUpdate { it.copy(torNewCircuitPeriod = v) } }
            IntEditRow(stringResource(Res.string.tor_max_dirtiness), s.torMaxCircuitDirtiness) { v -> onUpdate { it.copy(torMaxCircuitDirtiness = v) } }
            IntEditRow(stringResource(Res.string.tor_dormant_timeout), s.torDormantClientTimeout) { v -> onUpdate { it.copy(torDormantClientTimeout = v) } }
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_distinct_subnets),
                summary = stringResource(Res.string.tor_distinct_subnets_sum),
                checked = s.torEnforceDistinctSubnets,
                onCheckedChange = { v -> onUpdate { it.copy(torEnforceDistinctSubnets = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_track_host),
                summary = stringResource(Res.string.tor_track_host_sum),
                checked = s.torTrackHostExits,
                onCheckedChange = { v -> onUpdate { it.copy(torTrackHostExits = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_client_ipv4), checked = s.torClientUseIPv4,
                onCheckedChange = { v -> onUpdate { it.copy(torClientUseIPv4 = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_client_ipv6), checked = s.torClientUseIPv6,
                onCheckedChange = { v -> onUpdate { it.copy(torClientUseIPv6 = v) } },
            )
        }

        SettingsGroup(stringResource(Res.string.tor_isolation_group)) {
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_isolate_addr_t),
                summary = stringResource(Res.string.tor_isolate_addr_t_sum),
                checked = s.torIsolateDestAddr,
                onCheckedChange = { v -> onUpdate { it.copy(torIsolateDestAddr = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_isolate_port_t),
                summary = stringResource(Res.string.tor_isolate_port_t_sum),
                checked = s.torIsolateDestPort,
                onCheckedChange = { v -> onUpdate { it.copy(torIsolateDestPort = v) } },
            )
        }

        SettingsGroup(stringResource(Res.string.tor_advanced_group)) {
            SettingsSwitchRow(
                title = stringResource(Res.string.tor_fake_sni),
                summary = stringResource(Res.string.tor_fake_sni_sum),
                checked = s.torFakeSniEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(torFakeSniEnabled = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.tor_fake_sni_hosts),
                value = s.torFakeSniHosts,
                enabled = s.torFakeSniEnabled,
                placeholder = "play.googleapis.com, drive.google.com (blank = defaults)",
                onValueChanged = { v -> onUpdate { it.copy(torFakeSniHosts = v) } },
            )
        }
    }
}

@Composable
private fun IntEditRow(title: String, value: Int, onChange: (Int) -> Unit) {
    SettingsEditRow(
        title = title,
        value = value.toString(),
        numeric = true,
        onValueChanged = { it.toIntOrNull()?.let(onChange) },
    )
}

@Composable
private fun SshPage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.title_ssh_settings),
        art = SettingsArts.Ssh,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SettingsGroup {
            SettingsListRow(
                title = stringResource(Res.string.ssh_cipher),
                options = listOf(
                    "auto" to stringResource(Res.string.ssh_cipher_auto),
                    "aes128-gcm@openssh.com" to "AES-128-GCM",
                    "chacha20-poly1305@openssh.com" to "ChaCha20-Poly1305",
                    "aes256-gcm@openssh.com" to "AES-256-GCM",
                    "aes128-ctr" to stringResource(Res.string.ssh_cipher_legacy),
                ),
                selected = s.sshCipher,
                onSelected = { v -> onUpdate { it.copy(sshCipher = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.ssh_compression),
                summary = stringResource(Res.string.ssh_compression_sum),
                checked = s.sshCompression,
                onCheckedChange = { v -> onUpdate { it.copy(sshCompression = v) } },
            )
            IntEditRow(stringResource(Res.string.ssh_max_channels), s.sshMaxChannels) { v ->
                onUpdate { it.copy(sshMaxChannels = v.coerceIn(1, 64)) }
            }
        }
    }
}

@Composable
private fun UiPage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.title_ui_settings),
        art = SettingsArts.Appearance,
        subtitle = stringResource(Res.string.summary_ui_settings),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        val dynamicActive = s.dynamicColor && LocalPlatform.current.supportsDynamicColor
        val oldHome = s.appDesign == AppDesign.Old
        SettingsGroup {
            AppDesignPicker(
                selected = s.appDesign,
                onSelect = { v -> onUpdate { it.copy(appDesign = v) } },
            )
            if (!oldHome) {
                SettingsInfoRow(
                    title = stringResource(Res.string.home_design_title),
                    body = stringResource(Res.string.home_design_old_note),
                )
            }
        }

        SettingsGroup(title = stringResource(Res.string.settings_group_colors)) {
            SettingsChoiceRow(
                title = stringResource(Res.string.title_pref_ui_mode_night),
                summary = null,
                options = listOf(
                    ThemeMode.System to stringResource(Res.string.settings_theme_system),
                    ThemeMode.Light to stringResource(Res.string.settings_theme_light),
                    ThemeMode.Dark to stringResource(Res.string.settings_theme_dark),
                ),
                selected = s.themeMode,
                icons = listOf(Res.drawable.ic_fill_brightness_auto, Res.drawable.ic_fill_light_mode, Res.drawable.ic_fill_dark_mode),
                onSelected = { v -> onUpdate { it.copy(themeMode = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_dynamic_color),
                summary = stringResource(Res.string.summary_pref_dynamic_color),
                checked = s.dynamicColor,
                enabled = LocalPlatform.current.supportsDynamicColor,
                onCheckedChange = { v -> onUpdate { it.copy(dynamicColor = v) } },
            )
            if (!dynamicActive) {
                ColorStylePicker(
                    selected = s.colorStyle,
                    accent = s.accentColor,
                    contrast = s.colorContrast,
                    onSelect = { style -> onUpdate { it.copy(colorStyle = style) } },
                )
                AccentPicker(
                    selected = s.accentColor,
                    onSelect = { idx -> onUpdate { it.copy(accentColor = idx) } },
                )
                SettingsChoiceRow(
                    title = stringResource(Res.string.contrast_title),
                    summary = null,
                    options = listOf(
                        ColorContrast.Standard to stringResource(Res.string.contrast_standard),
                        ColorContrast.Medium to stringResource(Res.string.contrast_medium),
                        ColorContrast.High to stringResource(Res.string.contrast_high),
                    ),
                    selected = s.colorContrast,
                    onSelected = { v -> onUpdate { it.copy(colorContrast = v) } },
                )
            }
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_amoled),
                summary = stringResource(Res.string.summary_pref_amoled),
                checked = s.amoledBlack,
                onCheckedChange = { v -> onUpdate { it.copy(amoledBlack = v) } },
            )
        }

        SettingsGroup {
            SettingsSegment {
                ThemeProfileRow(
                    label = stringResource(Res.string.theme_profile_title),
                    subtitle = stringResource(Res.string.theme_profile_sub),
                    defaultLabel = stringResource(Res.string.theme_profile_none),
                    selectedId = s.themeProfileId,
                    onSelect = { profile ->
                        onUpdate {
                            if (profile == null) it.resetPersonalization()
                            else it.applyThemeProfile(profile)
                        }
                    },
                )
            }
        }

        SettingsGroup(title = stringResource(Res.string.settings_group_display)) {
            SettingsListRow(
                title = stringResource(Res.string.settings_language),
                options = listOf(
                    AppLanguage.System to stringResource(Res.string.settings_language_system),
                    AppLanguage.English to "English",
                    AppLanguage.Persian to "فارسی",
                    AppLanguage.Chinese to "中文",
                    AppLanguage.Russian to "Русский",
                ),
                selected = s.language,
                onSelected = onLanguage,
            )
            SettingsListRow(
                title = stringResource(Res.string.personalize_font_size),
                options = listOf(
                    UiFontScale.Smallest to stringResource(Res.string.personalize_font_smallest),
                    UiFontScale.Smaller to stringResource(Res.string.personalize_font_smaller),
                    UiFontScale.Normal to stringResource(Res.string.personalize_font_normal),
                    UiFontScale.Larger to stringResource(Res.string.personalize_font_larger),
                    UiFontScale.Largest to stringResource(Res.string.personalize_font_largest),
                ),
                selected = s.uiFontScale,
                onSelected = { v -> onUpdate { it.copy(uiFontScale = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_reduce_motion),
                summary = stringResource(Res.string.summary_pref_reduce_motion),
                checked = s.reduceMotion,
                onCheckedChange = { v -> onUpdate { it.copy(reduceMotion = v) } },
            )
            LocalPlatform.current.automaticRendering?.let { automatic ->
                SettingsListRow(
                    title = stringResource(Res.string.title_pref_rendering),
                    options = listOf(
                        RenderingMode.Auto to stringResource(Res.string.rendering_auto),
                        RenderingMode.Gpu to stringResource(Res.string.rendering_gpu),
                        RenderingMode.Software to stringResource(Res.string.rendering_software),
                    ),
                    selected = s.renderingMode,
                    onSelected = { v -> onUpdate { it.copy(renderingMode = v) } },
                )
                SettingsInfoRow(
                    stringResource(Res.string.title_pref_rendering),
                    stringResource(
                        when {
                            s.renderingMode != RenderingMode.Auto -> Res.string.rendering_sub_manual
                            automatic == AutomaticRendering.SoftwareWindows -> Res.string.rendering_sub_auto_windows
                            automatic == AutomaticRendering.SoftwareNvidiaWayland -> Res.string.rendering_sub_auto_nvidia
                            else -> Res.string.rendering_sub_auto_gpu
                        },
                    ),
                )
            }
        }

        SettingsGroup {
            SettingsSegment {
                TransitionPicker(
                    selected = s.screenTransition,
                    reduceMotion = s.reduceMotion,
                    onSelect = { style -> onUpdate { it.copy(screenTransition = style) } },
                )
            }
        }

        SettingsGroup {
            SettingsListRow(
                title = stringResource(Res.string.nav_style_title),
                options = listOf(
                    NavBarStyle.FloatingPill to stringResource(Res.string.nav_style_floating),
                    NavBarStyle.CompactDock to stringResource(Res.string.nav_style_compact),
                    NavBarStyle.ExpressivePill to stringResource(Res.string.nav_style_expressive),
                    NavBarStyle.FullBar to stringResource(Res.string.nav_style_full),
                    NavBarStyle.Underline to stringResource(Res.string.nav_style_underline),
                    NavBarStyle.Minimal to stringResource(Res.string.nav_style_minimal),
                ),
                selected = s.navBarStyle,
                onSelected = { v -> onUpdate { it.copy(navBarStyle = v) } },
            )
            if (oldHome) {
                SettingsSegment {
                    ConnectStyleRow(
                        label = stringResource(Res.string.connect_style_title),
                        subtitle = stringResource(Res.string.connect_style_sub),
                        selected = s.connectButtonStyle,
                        nameOf = { style -> stringResource(style.labelRes()) },
                        onSelect = { style -> onUpdate { it.copy(connectButtonStyle = style) } },
                    )
                }
                if (s.connectButtonStyle == ConnectButtonStyle.Hero) {
                    SettingsInfoRow(
                        title = stringResource(Res.string.connect_style_hero),
                        body = stringResource(Res.string.connect_style_hero_note),
                    )
                }
            }
        }

        if (oldHome) {
            SettingsGroup(title = stringResource(Res.string.personalize_tiles_title)) {
                val cs = androidx.compose.material3.MaterialTheme.colorScheme
                PersonalizeColorRow(
                    title = stringResource(Res.string.personalize_download),
                    subtitle = stringResource(Res.string.personalize_download_sub),
                    stored = s.customDownloadColor,
                    default = cs.primary,
                    preview = { c -> TrafficTilePreview(Res.drawable.ic_download, stringResource(Res.string.home_download), c) },
                    onChange = { v -> onUpdate { it.copy(customDownloadColor = v) } },
                )
                PersonalizeColorRow(
                    title = stringResource(Res.string.personalize_download_tile),
                    subtitle = stringResource(Res.string.personalize_download_tile_sub),
                    stored = s.customDownloadTileColor,
                    default = cs.surfaceContainerHigh,
                    preview = { c ->
                        TrafficTilePreview(
                            Res.drawable.ic_download,
                            stringResource(Res.string.home_download),
                            accent = s.customDownloadColor?.argbLongToColor() ?: cs.primary,
                            container = c,
                        )
                    },
                    onChange = { v -> onUpdate { it.copy(customDownloadTileColor = v) } },
                )
                PersonalizeColorRow(
                    title = stringResource(Res.string.personalize_upload),
                    subtitle = stringResource(Res.string.personalize_upload_sub),
                    stored = s.customUploadColor,
                    default = cs.tertiary,
                    preview = { c -> TrafficTilePreview(Res.drawable.ic_upload, stringResource(Res.string.home_upload), c) },
                    onChange = { v -> onUpdate { it.copy(customUploadColor = v) } },
                )
                PersonalizeColorRow(
                    title = stringResource(Res.string.personalize_upload_tile),
                    subtitle = stringResource(Res.string.personalize_upload_tile_sub),
                    stored = s.customUploadTileColor,
                    default = cs.surfaceContainerHigh,
                    preview = { c ->
                        TrafficTilePreview(
                            Res.drawable.ic_upload,
                            stringResource(Res.string.home_upload),
                            accent = s.customUploadColor?.argbLongToColor() ?: cs.tertiary,
                            container = c,
                        )
                    },
                    onChange = { v -> onUpdate { it.copy(customUploadTileColor = v) } },
                )
            }

            SettingsGroup(title = stringResource(Res.string.personalize_title)) {
                val cs = androidx.compose.material3.MaterialTheme.colorScheme
                PersonalizeColorRow(
                    title = stringResource(Res.string.personalize_connect),
                    subtitle = stringResource(Res.string.personalize_connect_sub),
                    stored = s.customConnectColor,
                    default = cs.primary,
                    preview = { c -> ConnectButtonPreview(c) },
                    onChange = { v -> onUpdate { it.copy(customConnectColor = v) } },
                )
                PersonalizeColorRow(
                    title = stringResource(Res.string.personalize_connected),
                    subtitle = stringResource(Res.string.personalize_connected_sub),
                    stored = s.customConnectedColor,
                    default = cs.secondaryContainer,
                    preview = { c -> ConnectButtonPreview(c) },
                    onChange = { v -> onUpdate { it.copy(customConnectedColor = v) } },
                )
                PersonalizeColorRow(
                    title = stringResource(Res.string.personalize_config_card),
                    subtitle = stringResource(Res.string.personalize_config_card_sub),
                    stored = s.customConfigCardColor,
                    default = cs.primaryContainer,
                    preview = { c -> ServerCardPreview(c, active = true) },
                    onChange = { v -> onUpdate { it.copy(customConfigCardColor = v) } },
                )
                PersonalizeColorRow(
                    title = stringResource(Res.string.personalize_config_card_text),
                    subtitle = stringResource(Res.string.personalize_config_card_text_sub),
                    stored = s.customConfigCardTextColor,
                    default = cs.onPrimaryContainer,
                    preview = { c -> TapHintPreview(c) },
                    onChange = { v -> onUpdate { it.copy(customConfigCardTextColor = v) } },
                )
                PersonalizeColorRow(
                    title = stringResource(Res.string.personalize_tap_hint),
                    subtitle = stringResource(Res.string.personalize_tap_hint_sub),
                    stored = s.customTapHintColor,
                    default = cs.onPrimaryContainer.copy(alpha = 0.75f),
                    preview = { c -> TapHintPreview(c) },
                    onChange = { v -> onUpdate { it.copy(customTapHintColor = v) } },
                )
            }
        }
        SettingsGroup(title = stringResource(Res.string.personalize_servers_title)) {
            val cs = androidx.compose.material3.MaterialTheme.colorScheme
            PersonalizeColorRow(
                title = stringResource(Res.string.personalize_server_card),
                subtitle = stringResource(Res.string.personalize_server_card_sub),
                stored = s.customServerCardColor,
                default = cs.surfaceContainerHigh,
                preview = { c -> ServerCardPreview(c, active = false) },
                onChange = { v -> onUpdate { it.copy(customServerCardColor = v) } },
            )
            PersonalizeColorRow(
                title = stringResource(Res.string.personalize_server_active),
                subtitle = stringResource(Res.string.personalize_server_active_sub),
                stored = s.customServerActiveColor,
                default = cs.primaryContainer,
                preview = { c -> ServerCardPreview(c, active = true) },
                onChange = { v -> onUpdate { it.copy(customServerActiveColor = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.personalize_show_ping),
                summary = stringResource(Res.string.personalize_show_ping_sub),
                checked = s.showServerPing,
                onCheckedChange = { v -> onUpdate { it.copy(showServerPing = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.personalize_show_usage),
                summary = stringResource(Res.string.personalize_show_usage_sub),
                checked = s.showServerUsage,
                onCheckedChange = { v -> onUpdate { it.copy(showServerUsage = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.personalize_monospace),
                summary = stringResource(Res.string.personalize_monospace_sub),
                checked = s.monospaceAddress,
                onCheckedChange = { v -> onUpdate { it.copy(monospaceAddress = v) } },
            )
        }
        SettingsGroup(title = stringResource(Res.string.personalize_shape_title)) {
            SettingsListRow(
                title = stringResource(Res.string.personalize_corner),
                options = listOf(
                    CardCornerStyle.Squared to stringResource(Res.string.personalize_corner_squared),
                    CardCornerStyle.Rounded to stringResource(Res.string.personalize_corner_rounded),
                    CardCornerStyle.Pill to stringResource(Res.string.personalize_corner_pill),
                ),
                selected = s.cardCornerStyle,
                onSelected = { v -> onUpdate { it.copy(cardCornerStyle = v) } },
            )
            SettingsListRow(
                title = stringResource(Res.string.personalize_density),
                options = listOf(
                    ListDensity.Comfortable to stringResource(Res.string.personalize_density_comfortable),
                    ListDensity.Compact to stringResource(Res.string.personalize_density_compact),
                ),
                selected = s.listDensity,
                onSelected = { v -> onUpdate { it.copy(listDensity = v) } },
            )
            SettingsActionRow(
                title = stringResource(Res.string.personalize_reset_all),
                summary = stringResource(Res.string.personalize_reset_all_sub),
                enabled = s.hasPersonalization,
                onClick = { onUpdate { it.resetPersonalization() } },
            )
        }
        SettingsGroup(title = stringResource(Res.string.settings_group_behaviour)) {
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_speed_enabled),
                summary = stringResource(Res.string.summary_pref_speed_enabled),
                checked = s.speedInNotification,
                onCheckedChange = { v -> onUpdate { it.copy(speedInNotification = v) } },
            )

            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_live_notif),
                summary = stringResource(Res.string.summary_pref_live_notif),
                checked = s.liveNotification,
                onCheckedChange = { v -> onUpdate { it.copy(liveNotification = v) } },
            )
            SettingsListRow(
                title = stringResource(Res.string.title_pref_notif_chip),
                options = listOf(
                    NotifChip.Speed to stringResource(Res.string.notif_chip_opt_speed),
                    NotifChip.ConfigName to stringResource(Res.string.notif_chip_opt_name),
                    NotifChip.Duration to stringResource(Res.string.notif_chip_opt_duration),
                    NotifChip.Total to stringResource(Res.string.notif_chip_opt_total),
                    NotifChip.None to stringResource(Res.string.notif_chip_opt_none),
                ),
                selected = s.notifChip,
                enabled = s.liveNotification,
                onSelected = { v -> onUpdate { it.copy(notifChip = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_confirm_remove),
                summary = stringResource(Res.string.summary_pref_confirm_remove),
                checked = s.confirmRemove,
                onCheckedChange = { v -> onUpdate { it.copy(confirmRemove = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_group_all_display),
                summary = stringResource(Res.string.summary_pref_group_all_display),
                checked = s.groupAllDisplay,
                onCheckedChange = { v -> onUpdate { it.copy(groupAllDisplay = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_double_column_display),
                summary = stringResource(Res.string.summary_pref_double_column_display),
                checked = s.doubleColumnDisplay,
                onCheckedChange = { v -> onUpdate { it.copy(doubleColumnDisplay = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_home_after_select),
                summary = stringResource(Res.string.summary_pref_home_after_select),
                checked = s.homeAfterSelect,
                onCheckedChange = { v -> onUpdate { it.copy(homeAfterSelect = v) } },
            )
            if (oldHome) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.title_pref_traffic_tiles),
                    summary = stringResource(Res.string.summary_pref_traffic_tiles),
                    checked = s.showTrafficTiles,
                    onCheckedChange = { v -> onUpdate { it.copy(showTrafficTiles = v) } },
                )
                SettingsSwitchRow(
                    title = stringResource(Res.string.title_pref_traffic_tiles_top),
                    summary = stringResource(Res.string.summary_pref_traffic_tiles_top),
                    checked = s.trafficTilesAboveHero,
                    enabled = s.showTrafficTiles,
                    onCheckedChange = { v -> onUpdate { it.copy(trafficTilesAboveHero = v) } },
                )
                SettingsListRow(
                    title = stringResource(Res.string.title_pref_traffic_card_style),
                    options = listOf(
                        TrafficCardStyle.Cards to stringResource(Res.string.card_style_cards),
                        TrafficCardStyle.Duo to stringResource(Res.string.card_style_duo),
                        TrafficCardStyle.Rings to stringResource(Res.string.card_style_rings),
                        TrafficCardStyle.Minimal to stringResource(Res.string.card_style_minimal),
                        TrafficCardStyle.Graph to stringResource(Res.string.card_style_graph),
                    ),
                    selected = s.trafficCardStyle,
                    enabled = s.showTrafficTiles,
                    onSelected = { v -> onUpdate { it.copy(trafficCardStyle = v) } },
                )
                SettingsListRow(
                    title = stringResource(Res.string.title_pref_traffic_tile_size),
                    options = listOf(
                        TrafficTileSize.Small to stringResource(Res.string.tile_size_small),
                        TrafficTileSize.Normal to stringResource(Res.string.tile_size_normal),
                        TrafficTileSize.Large to stringResource(Res.string.tile_size_large),
                    ),
                    selected = s.trafficTileSize,
                    enabled = s.showTrafficTiles,
                    onSelected = { v -> onUpdate { it.copy(trafficTileSize = v) } },
                )
            }
        }
    }
}

@Composable
private fun AdminAccessRow(access: dev.cluvex.zedsecure.ui.platform.AdminAccess) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var remembered by remember { mutableStateOf(access.remembered) }
    var busy by remember { mutableStateOf(false) }
    val failed = stringResource(Res.string.settings_admin_remember_failed)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_admin_remember_title),
        summary = stringResource(Res.string.settings_admin_remember_body),
        checked = remembered,
        enabled = !busy,
        onCheckedChange = { on ->
            busy = true
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { access.setRemembered(on) }
                remembered = access.remembered
                busy = false
                result.exceptionOrNull()?.let { platform.toast(failed + (it.message?.let { m -> ": $m" } ?: "")) }
            }
        },
    )
}

@Composable
private fun VpnPage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,

    mtuHint: dev.cluvex.zedsecure.data.net.MtuServerHint?,
) {
    val isVpn = s.isVpnMode
    val hevOn = isVpn
    SettingsPageScaffold(
        title = stringResource(Res.string.title_vpn_settings),
        art = SettingsArts.Vpn,
        subtitle = stringResource(Res.string.summary_vpn_settings),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SettingsGroup {
            val batteryPlatform = LocalPlatform.current
            batteryPlatform.adminAccess?.let { access -> AdminAccessRow(access) }
            if (batteryPlatform.supportsBatteryOptimization) {
                var exempt by remember { mutableStateOf(batteryPlatform.isIgnoringBatteryOptimizations) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    exempt = batteryPlatform.isIgnoringBatteryOptimizations
                }
                SettingsActionRow(
                    title = stringResource(Res.string.battery_opt_title),
                    summary = stringResource(
                        if (exempt) Res.string.battery_opt_off else Res.string.battery_opt_on,
                    ),
                    onClick = { batteryPlatform.openBatteryOptimizationSettings() },
                )
            }
            SettingsSwitchRow(
                title = stringResource(Res.string.kill_switch),
                summary = stringResource(Res.string.kill_switch_sum),
                checked = s.killSwitch,
                onCheckedChange = { v -> onUpdate { it.copy(killSwitch = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_ipv6_enabled),
                summary = stringResource(Res.string.summary_pref_ipv6_enabled),
                checked = s.enableIpv6,
                onCheckedChange = { v -> onUpdate { it.copy(enableIpv6 = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_prefer_ipv6),
                summary = stringResource(Res.string.summary_pref_prefer_ipv6),
                checked = s.preferIpv6,
                onCheckedChange = { v -> onUpdate { it.copy(preferIpv6 = v) } },
            )
            SettingsListRow(
                title = stringResource(Res.string.title_pref_vpn_interface_address),
                options = VpnInterfaceAddress.entries.map { it to dev.cluvex.zedsecure.ui.format.ltrIsolate(it.label) },
                selected = s.vpnInterfaceAddress,
                enabled = isVpn,
                onSelected = { v -> onUpdate { it.copy(vpnInterfaceAddress = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_vpn_mtu),
                value = s.vpnMtu.toString(),
                enabled = isVpn,
                numeric = true,
                onValueChanged = { v ->
                    v.toIntOrNull()?.coerceIn(1000, 9000)?.let { mtu ->
                        onUpdate { it.copy(vpnMtu = mtu) }
                    }
                },
            )

            SettingsSegment {
                MtuOptimizeRow(
                    enabled = isVpn,
                    serverHost = mtuHint?.host,
                    overhead = mtuHint?.overhead ?: dev.cluvex.zedsecure.data.net.MtuOverheads.worstCase(),
                ) { mtu -> onUpdate { it.copy(vpnMtu = mtu) } }
            }
            SettingsListRow(
                title = stringResource(Res.string.title_pref_vpn_bypass_lan),
                options = listOf(
                    VpnBypassLan.FollowConfig to stringResource(Res.string.routing_mode_global),
                    VpnBypassLan.Bypass to stringResource(Res.string.routing_mode_bypass_lan),
                    VpnBypassLan.NotBypass to stringResource(Res.string.routing_mode_bypass_mainland),
                ),
                selected = s.vpnBypassLan,
                enabled = isVpn,
                onSelected = { v -> onUpdate { it.copy(vpnBypassLan = v) } },
            )
        }

        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_local_dns_enabled),
                summary = stringResource(Res.string.summary_pref_local_dns_enabled),
                checked = s.localDnsEnabled,
                enabled = isVpn,
                onCheckedChange = { v -> onUpdate { it.copy(localDnsEnabled = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_fake_dns_enabled),
                summary = stringResource(Res.string.summary_pref_fake_dns_enabled),
                checked = s.fakeDnsEnabled,
                enabled = isVpn && s.localDnsEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(fakeDnsEnabled = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_vpn_dns),
                value = s.vpnDns,
                enabled = isVpn && !s.localDnsEnabled,
                onValueChanged = { v -> onUpdate { it.copy(vpnDns = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_append_http_proxy),
                summary = stringResource(Res.string.summary_pref_append_http_proxy),
                checked = s.appendHttpProxy,
                enabled = s.effectiveLocalProxy,
                onCheckedChange = { v -> onUpdate { it.copy(appendHttpProxy = v) } },
            )
        }

        if (LocalPlatform.current.choosesTunEngine && LocalPlatform.current.isComputer) {
            SettingsGroup {
                SettingsListRow(
                    title = stringResource(Res.string.tun_engine_title),
                    options = listOf(
                        false to stringResource(Res.string.tun_engine_hev_default),
                        true to "zeptun",
                    ),
                    selected = s.useZepTun,
                    enabled = hevOn,
                    onSelected = { v -> onUpdate { it.copy(useZepTun = v) } },
                )
                SettingsInfoRow(
                    stringResource(Res.string.tun_engine_title),
                    stringResource(Res.string.tun_engine_sub_desktop),
                )
            }
        } else if (LocalPlatform.current.choosesTunEngine) {
            SettingsGroup {
                SettingsInfoRow(
                    stringResource(Res.string.title_pref_use_hev_tunnel),
                    stringResource(Res.string.summary_pref_hev_tunnel_always_on),
                )
                SettingsListRow(
                    title = stringResource(Res.string.tun_engine_title),
                    options = listOf(
                        true to stringResource(Res.string.tun_engine_zeptun),
                        false to stringResource(Res.string.tun_engine_hev),
                    ),
                    selected = s.useZepTun,
                    enabled = hevOn,
                    onSelected = { v -> onUpdate { it.copy(useZepTun = v) } },
                )
                SettingsInfoRow(
                    stringResource(Res.string.tun_engine_title),
                    stringResource(Res.string.tun_engine_sub),
                )
                SettingsListRow(
                    title = stringResource(Res.string.title_pref_hev_tunnel_loglevel),
                    options = HevLogLevel.entries.map { it to it.value },
                    selected = s.hevTunLogLevel,
                    enabled = hevOn,
                    onSelected = { v -> onUpdate { it.copy(hevTunLogLevel = v) } },
                )
                SettingsEditRow(
                    title = stringResource(Res.string.title_pref_hev_tunnel_rw_timeout),
                    value = s.hevTunRwTimeout,
                    enabled = hevOn,
                    placeholder = stringResource(Res.string.summary_pref_hev_tunnel_rw_timeout),
                    onValueChanged = { v -> onUpdate { it.copy(hevTunRwTimeout = v) } },
                )
            }
        }
    }
}

@Composable
private fun CorePage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val localProxy = s.effectiveLocalProxy
    SettingsPageScaffold(
        title = stringResource(Res.string.title_core_settings),
        art = SettingsArts.Core,
        subtitle = stringResource(Res.string.summary_core_settings),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_sniffing_enabled),
                summary = stringResource(Res.string.summary_pref_sniffing_enabled),
                checked = s.sniffingEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(sniffingEnabled = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_route_only_enabled),
                summary = stringResource(Res.string.summary_pref_route_only_enabled),
                checked = s.routeOnly,
                onCheckedChange = { v -> onUpdate { it.copy(routeOnly = v) } },
            )
            SettingsListRow(
                title = stringResource(Res.string.title_core_loglevel),
                options = LogLevel.entries.map { it to it.value },
                selected = s.logLevel,
                onSelected = { v -> onUpdate { it.copy(logLevel = v) } },
            )
            SettingsListRow(
                title = stringResource(Res.string.title_outbound_domain_resolve_method),
                options = listOf(
                    OutboundDomainResolve.DoNotResolve to "Do not resolve",
                    OutboundDomainResolve.ResolveAndAddToHosts to "Resolve and add to DNS hosts",
                    OutboundDomainResolve.ResolveAndReplace to "Resolve and replace domain",
                ),
                selected = s.outboundDomainResolve,
                onSelected = { v -> onUpdate { it.copy(outboundDomainResolve = v) } },
            )
        }

        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_enable_local_proxy),
                summary = stringResource(Res.string.summary_pref_enable_local_proxy),
                checked = s.enableLocalProxy,

                enabled = !s.localProxyForced,
                onCheckedChange = { v ->
                    onUpdate {
                        it.copy(
                            enableLocalProxy = v,
                            appendHttpProxy = if (!v) false else it.appendHttpProxy,
                        )
                    }
                },
            )

            val portPlatform = LocalPlatform.current
            val badProxyPort = stringResource(Res.string.toast_local_proxy_port_invalid)
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_local_proxy_port),
                value = s.effectiveLocalProxyPort.toString(),
                enabled = localProxy,
                numeric = true,
                onValueChanged = { v ->
                    val port = v.trim().toIntOrNull()
                    val lan = s.effectiveLanSocksPort
                    if (port != null && LocalPorts.localProxyPort(port) == port && port != lan && port + 1 != lan && port != lan + 1) {
                        onUpdate { it.copy(localProxyPort = port) }
                    } else {
                        portPlatform.toast(badProxyPort)
                    }
                },
            )

            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_proxy_sharing_enabled),
                summary = stringResource(Res.string.summary_pref_proxy_sharing_enabled),
                checked = s.proxySharing,
                enabled = localProxy,
                onCheckedChange = { v ->
                    onUpdate {
                        if (v && (it.socksUsername.isBlank() || it.socksPassword.isBlank())) {
                            it.copy(
                                proxySharing = true,
                                socksUsername = it.socksUsername.ifBlank { LAN_DEFAULT_USER },
                                socksPassword = it.socksPassword.ifBlank { secureRandomToken(LAN_PASSWORD_LENGTH) },
                            )
                        } else {
                            it.copy(proxySharing = v)
                        }
                    }
                },
            )
            if (s.proxySharing) LanShareAddressRow(s)
            val platform = LocalPlatform.current
            val invalidPort = stringResource(Res.string.toast_lan_port_invalid)
            val credentialRequired = stringResource(Res.string.toast_lan_credential_required)
            val sharing = localProxy && s.proxySharing
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_lan_port),
                value = s.effectiveLanSocksPort.toString(),
                enabled = sharing,
                numeric = true,
                onValueChanged = { v ->
                    val port = v.trim().toIntOrNull()

                    if (port != null && LocalPorts.lanSocksPort(port) == port) {
                        onUpdate { it.copy(socksPort = port) }
                    } else {
                        platform.toast(invalidPort)
                    }
                },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_lan_auth),
                summary = stringResource(Res.string.summary_pref_lan_auth),
                checked = s.lanShareAuth,
                enabled = sharing,
                onCheckedChange = { v ->
                    onUpdate {
                        if (v && (it.socksUsername.isBlank() || it.socksPassword.isBlank())) {
                            it.copy(
                                lanShareAuth = true,
                                socksUsername = it.socksUsername.ifBlank { LAN_DEFAULT_USER },
                                socksPassword = it.socksPassword.ifBlank { secureRandomToken(LAN_PASSWORD_LENGTH) },
                            )
                        } else {
                            it.copy(lanShareAuth = v)
                        }
                    }
                },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_lan_username),
                value = s.socksUsername,
                enabled = sharing && s.lanShareAuth,
                onValueChanged = { v ->
                    if (v.isBlank()) platform.toast(credentialRequired)
                    else onUpdate { it.copy(socksUsername = v.trim()) }
                },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_lan_password),
                value = s.socksPassword,
                enabled = sharing && s.lanShareAuth,
                isPassword = true,
                onValueChanged = { v ->
                    if (v.isBlank()) platform.toast(credentialRequired)
                    else onUpdate { it.copy(socksPassword = v) }
                },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_socks_enable_udp),
                summary = stringResource(Res.string.summary_pref_socks_enable_udp),
                checked = s.socksEnableUdp,
                enabled = sharing,
                onCheckedChange = { v -> onUpdate { it.copy(socksEnableUdp = v) } },
            )
        }

        SettingsGroup {
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_remote_dns),
                value = s.remoteDns,
                placeholder = stringResource(Res.string.summary_pref_remote_dns),
                onValueChanged = { v -> onUpdate { it.copy(remoteDns = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_domestic_dns),
                value = s.directDns,
                onValueChanged = { v -> onUpdate { it.copy(directDns = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_dns_hosts),
                value = s.dnsHosts,
                placeholder = stringResource(Res.string.summary_pref_dns_hosts),
                onValueChanged = { v -> onUpdate { it.copy(dnsHosts = v) } },
            )
        }
    }
}

private const val LAN_DEFAULT_USER = "zed"

private const val LAN_PASSWORD_LENGTH = 16

@Composable
private fun LanShareAddressRow(s: AppSettings) {
    val platform = LocalPlatform.current
    var addresses by remember { mutableStateOf(platform.lanIpv4Addresses()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { addresses = platform.lanIpv4Addresses() }
    val port = s.effectiveLanSocksPort
    val userLine = if (s.lanShareAuth) stringResource(Res.string.lan_share_user, s.socksUsername)
    else stringResource(Res.string.lan_share_no_auth)
    val none = stringResource(Res.string.lan_share_no_address)
    SettingsInfoRow(
        title = stringResource(Res.string.lan_share_connect_title),
        body = if (addresses.isEmpty()) {
            none
        } else {
            buildString {
                addresses.forEach { ip ->
                    appendLine("SOCKS5  $ip:$port")
                    if (s.appendHttpProxy) appendLine("HTTP  $ip:${port + 1}")
                }
                append(userLine)
            }
        },
    )
}

@Composable
private fun SingBoxPage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.title_singbox_settings),
        art = SettingsArts.SingBox,
        subtitle = stringResource(Res.string.summary_singbox_settings),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SettingsGroup(title = stringResource(Res.string.singbox_section_servers)) {
            SettingsSwitchRow(
                title = stringResource(Res.string.title_singbox_mux),
                summary = stringResource(Res.string.summary_singbox_mux),
                checked = s.singBoxMuxEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(singBoxMuxEnabled = v) } },
            )
            SettingsListRow(
                title = stringResource(Res.string.title_singbox_mux_protocol),
                options = SingBoxMuxProtocol.entries.map { it to it.value },
                selected = s.singBoxMuxProtocol,
                enabled = s.singBoxMuxEnabled,
                onSelected = { v -> onUpdate { it.copy(singBoxMuxProtocol = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_singbox_mux_connections),
                value = s.singBoxMuxMaxConnections.toString(),
                enabled = s.singBoxMuxEnabled,
                numeric = true,
                onValueChanged = { v ->
                    v.toIntOrNull()?.coerceIn(0, 64)?.let { n -> onUpdate { it.copy(singBoxMuxMaxConnections = n) } }
                },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_singbox_mux_padding),
                summary = stringResource(Res.string.summary_singbox_mux_padding),
                checked = s.singBoxMuxPadding,
                enabled = s.singBoxMuxEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(singBoxMuxPadding = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_singbox_brutal),
                summary = stringResource(Res.string.summary_singbox_brutal),
                checked = s.singBoxBrutalEnabled,
                enabled = s.singBoxMuxEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(singBoxBrutalEnabled = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_singbox_brutal_up),
                value = s.singBoxBrutalUpMbps.toString(),
                enabled = s.singBoxMuxEnabled && s.singBoxBrutalEnabled,
                numeric = true,
                onValueChanged = { v ->
                    v.toIntOrNull()?.coerceIn(1, 10_000)?.let { n -> onUpdate { it.copy(singBoxBrutalUpMbps = n) } }
                },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_singbox_brutal_down),
                value = s.singBoxBrutalDownMbps.toString(),
                enabled = s.singBoxMuxEnabled && s.singBoxBrutalEnabled,
                numeric = true,
                onValueChanged = { v ->
                    v.toIntOrNull()?.coerceIn(1, 10_000)?.let { n -> onUpdate { it.copy(singBoxBrutalDownMbps = n) } }
                },
            )
        }

        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(Res.string.title_singbox_tls_fragment),
                summary = stringResource(Res.string.summary_singbox_tls_fragment),
                checked = s.singBoxTlsFragment,
                onCheckedChange = { v -> onUpdate { it.copy(singBoxTlsFragment = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_singbox_record_fragment),
                summary = stringResource(Res.string.summary_singbox_record_fragment),
                checked = s.singBoxTlsRecordFragment,
                onCheckedChange = { v -> onUpdate { it.copy(singBoxTlsRecordFragment = v) } },
            )
            SettingsListRow(
                title = stringResource(Res.string.title_singbox_utls),
                options = listOf("" to stringResource(Res.string.singbox_utls_off)) +
                    SingBoxTuning.FINGERPRINTS.map { it to it },
                selected = s.singBoxUtlsFingerprint,
                onSelected = { v -> onUpdate { it.copy(singBoxUtlsFingerprint = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_singbox_uot),
                summary = stringResource(Res.string.summary_singbox_uot),
                checked = s.singBoxUdpOverTcp,
                onCheckedChange = { v -> onUpdate { it.copy(singBoxUdpOverTcp = v) } },
            )
        }

        SettingsGroup(title = stringResource(Res.string.singbox_section_configs)) {
            SettingsListRow(
                title = stringResource(Res.string.title_singbox_stack),
                options = SingBoxStack.entries.map {
                    it to if (it == SingBoxStack.Auto) stringResource(Res.string.singbox_stack_auto) else it.value
                },
                selected = s.singBoxTunStack,
                onSelected = { v -> onUpdate { it.copy(singBoxTunStack = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_singbox_strict_route),
                summary = stringResource(Res.string.summary_singbox_strict_route),
                checked = s.singBoxStrictRoute,
                onCheckedChange = { v -> onUpdate { it.copy(singBoxStrictRoute = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_singbox_nat),
                summary = stringResource(Res.string.summary_singbox_nat),
                checked = s.singBoxEndpointIndependentNat,
                onCheckedChange = { v -> onUpdate { it.copy(singBoxEndpointIndependentNat = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_singbox_cache),
                summary = stringResource(Res.string.summary_singbox_cache),
                checked = s.singBoxStoreCache,
                onCheckedChange = { v -> onUpdate { it.copy(singBoxStoreCache = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_singbox_ntp),
                summary = stringResource(Res.string.summary_singbox_ntp),
                checked = s.singBoxNtpEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(singBoxNtpEnabled = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_singbox_ntp_server),
                value = s.singBoxNtpServer,
                enabled = s.singBoxNtpEnabled,
                onValueChanged = { v -> onUpdate { it.copy(singBoxNtpServer = v.trim()) } },
            )
        }
    }
}

@Composable
private fun MuxPage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.title_mux_settings),
        art = SettingsArts.Mux,
        subtitle = stringResource(Res.string.summary_mux_settings),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_mux_enabled),
                summary = stringResource(Res.string.summary_pref_mux_enabled),
                checked = s.muxEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(muxEnabled = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_mux_concurrency),
                value = s.muxConcurrency.toString(),
                enabled = s.muxEnabled,
                numeric = true,
                placeholder = stringResource(Res.string.summary_pref_mux_concurrency),
                onValueChanged = { v ->
                    v.toIntOrNull()?.coerceIn(-1, 1024)?.let { n ->
                        onUpdate { it.copy(muxConcurrency = n) }
                    }
                },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_mux_xudp_concurrency),
                value = s.muxXudpConcurrency.toString(),
                enabled = s.muxEnabled,
                numeric = true,
                onValueChanged = { v ->
                    v.toIntOrNull()?.coerceIn(-1, 1024)?.let { n ->
                        onUpdate { it.copy(muxXudpConcurrency = n) }
                    }
                },
            )
            SettingsListRow(
                title = stringResource(Res.string.title_pref_mux_xudp_quic),
                options = XudpQuic.entries.map { it to it.value },
                selected = s.muxXudpQuic,
                enabled = s.muxEnabled && s.muxXudpConcurrency >= 0,
                onSelected = { v -> onUpdate { it.copy(muxXudpQuic = v) } },
            )
        }
    }
}

@Composable
private fun FragmentPage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.title_fragment_settings),
        art = SettingsArts.Fragment,
        subtitle = stringResource(Res.string.summary_fragment_settings),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_fragment_enabled),
                checked = s.fragmentEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(fragmentEnabled = v) } },
            )

            SettingsListRow(
                title = stringResource(Res.string.title_pref_fragment_packets),
                options = FragmentPackets.entries.map { it to it.value },
                selected = s.fragmentPackets,
                enabled = s.fragmentEnabled,
                onSelected = { v -> onUpdate { it.copy(fragmentPackets = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_fragment_length),
                value = s.fragmentLength,
                enabled = s.fragmentEnabled,
                placeholder = stringResource(Res.string.summary_pref_fragment_range),
                onValueChanged = { v -> onUpdate { it.copy(fragmentLength = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_fragment_interval),
                value = s.fragmentInterval,
                enabled = s.fragmentEnabled,
                placeholder = stringResource(Res.string.summary_pref_fragment_range),
                onValueChanged = { v -> onUpdate { it.copy(fragmentInterval = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_fragment_maxsplit),
                value = s.fragmentMaxSplit.toString(),
                enabled = s.fragmentEnabled,
                numeric = true,
                onValueChanged = { v ->
                    v.toIntOrNull()?.takeIf { it > 0 }?.let { n ->
                        onUpdate { it.copy(fragmentMaxSplit = n) }
                    }
                },
            )
        }
    }
}

@Composable
private fun ObservatoryPage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val seconds = stringResource(Res.string.auto_seconds, "%s")
    val minutes = stringResource(Res.string.auto_minutes, "%s")
    SettingsPageScaffold(
        title = stringResource(Res.string.title_observatory_settings),
        art = SettingsArts.Observatory,
        subtitle = stringResource(Res.string.summary_observatory_settings),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SettingsGroup {
            SettingsInfoRow(
                stringResource(Res.string.auto_how_title),
                stringResource(Res.string.auto_how_body),
            )
        }
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(Res.string.auto_pref_retry),
                summary = stringResource(Res.string.auto_pref_retry_sum),
                checked = s.autoSelectRetry,
                onCheckedChange = { v -> onUpdate { it.copy(autoSelectRetry = v) } },
            )
            SettingsListRow(
                title = stringResource(Res.string.auto_pref_margin),
                options = listOf(15, 30, 50).map { it to "$it%" },
                selected = s.autoSelectSwitchMargin,
                onSelected = { v -> onUpdate { it.copy(autoSelectSwitchMargin = v) } },
            )
            SettingsListRow(
                title = stringResource(Res.string.auto_pref_check),
                options = listOf(15, 30, 60, 120).map { it to seconds.replace("%s", it.toString()) },
                selected = s.autoSelectCheckSeconds,
                onSelected = { v -> onUpdate { it.copy(autoSelectCheckSeconds = v) } },
            )
            SettingsListRow(
                title = stringResource(Res.string.auto_pref_sweep),
                options = listOf(5, 10, 20, 30).map { it to minutes.replace("%s", it.toString()) },
                selected = s.autoSelectSweepMinutes,
                onSelected = { v -> onUpdate { it.copy(autoSelectSweepMinutes = v) } },
            )
        }
    }
}

@Composable
private fun AdvancedPage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.title_advanced),
        art = SettingsArts.Advanced,
        subtitle = stringResource(Res.string.summary_advanced),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_is_booted),
                summary = stringResource(Res.string.summary_pref_is_booted),
                checked = s.autoConnectOnBoot,
                onCheckedChange = { v -> onUpdate { it.copy(autoConnectOnBoot = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_delay_test_url),
                value = s.delayTestUrl,
                onValueChanged = { v -> onUpdate { it.copy(delayTestUrl = v) } },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_real_ping_concurrency),
                value = s.realPingConcurrency.toString(),
                numeric = true,
                onValueChanged = { v ->
                    v.toIntOrNull()?.coerceIn(1, 128)?.let { n ->
                        onUpdate { it.copy(realPingConcurrency = n) }
                    }
                },
            )
            SettingsEditRow(
                title = stringResource(Res.string.title_pref_ip_api_url),
                value = s.ipApiUrl,
                onValueChanged = { v -> onUpdate { it.copy(ipApiUrl = v) } },
            )
        }
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_auto_update_subs),
                summary = stringResource(Res.string.settings_auto_update_subs_sub),
                checked = s.autoUpdateSubscriptions,
                onCheckedChange = { v -> onUpdate { it.copy(autoUpdateSubscriptions = v) } },
            )
            val hourOptions = (SUB_INTERVAL_PRESETS + s.subscriptionUpdateIntervalHours.coerceIn(1, 168))
                .distinct().sorted()
            SettingsListRow(
                title = stringResource(Res.string.settings_sub_interval),
                options = hourOptions.map { h -> h to subIntervalLabel(h) },
                selected = s.subscriptionUpdateIntervalHours.coerceIn(1, 168),
                enabled = s.autoUpdateSubscriptions,
                onSelected = { h -> onUpdate { it.copy(subscriptionUpdateIntervalHours = h) } },
            )
            if (s.autoUpdateSubscriptions) {
                SettingsInfoRow(
                    title = stringResource(Res.string.settings_sub_interval_provider_title),
                    body = stringResource(Res.string.settings_sub_interval_provider_body),
                )
            }
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_auto_test_after_update),
                checked = s.autoTestAfterUpdate,
                onCheckedChange = { v -> onUpdate { it.copy(autoTestAfterUpdate = v) } },
            )

            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_auto_remove_invalid),
                checked = s.autoRemoveInvalidAfterTest,
                onCheckedChange = { v -> onUpdate { it.copy(autoRemoveInvalidAfterTest = v) } },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.title_pref_auto_sort_after_test),
                checked = s.autoSortAfterTest,
                onCheckedChange = { v -> onUpdate { it.copy(autoSortAfterTest = v) } },
            )
        }
        SettingsBackupGroup(s, onUpdate)
    }
}

@Composable
private fun SettingsBackupGroup(s: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val imported = stringResource(Res.string.settings_imported)
    val invalid = stringResource(Res.string.settings_import_invalid)
    SettingsGroup(stringResource(Res.string.settings_backup_group)) {
        SettingsActionRow(
            title = stringResource(Res.string.settings_export),
            summary = stringResource(Res.string.settings_export_sum),
            onClick = {
                platform.saveFile(
                    dev.cluvex.zedsecure.domain.model.SettingsBackup.FILE_NAME,
                    "application/json",
                    dev.cluvex.zedsecure.domain.model.SettingsBackup.export(s).encodeToByteArray(),
                )
            },
        )
        SettingsActionRow(
            title = stringResource(Res.string.settings_import),
            summary = stringResource(Res.string.settings_import_sum),
            onClick = {
                scope.launch {
                    val pick = platform.pickFileBytes() ?: return@launch
                    val restored = dev.cluvex.zedsecure.domain.model.SettingsBackup.read(pick.bytes.decodeToString())
                    if (restored == null) {
                        platform.toast(invalid)
                    } else {
                        onUpdate { restored }
                        platform.toast(imported)
                    }
                }
            },
        )
    }
}

private val SUB_INTERVAL_PRESETS = listOf(1, 3, 6, 12, 24, 48, 72, 168)

@Composable
private fun subIntervalLabel(hours: Int): String = when {
    hours == 1 -> stringResource(Res.string.settings_sub_interval_hour)
    hours == 24 -> stringResource(Res.string.settings_sub_interval_day)
    hours == 168 -> stringResource(Res.string.settings_sub_interval_week)
    hours % 24 == 0 -> stringResource(Res.string.settings_sub_interval_days, hours / 24)
    else -> stringResource(Res.string.settings_sub_interval_hours, hours)
}

@Composable
private fun ModePage(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    SettingsPageScaffold(
        title = stringResource(Res.string.title_mode_settings),
        art = SettingsArts.Mode,
        subtitle = stringResource(Res.string.summary_mode_settings),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        val desktop = LocalPlatform.current.supportsSystemProxy
        val tun = LocalPlatform.current.supportsTun
        SettingsGroup {
            SettingsListRow(
                title = stringResource(Res.string.title_mode),
                options = if (desktop) {
                    listOfNotNull(
                        RunMode.SystemProxy to stringResource(Res.string.mode_system_proxy),
                        RunMode.ProxyOnly to stringResource(Res.string.mode_socks),
                        (RunMode.Vpn to stringResource(Res.string.mode_tun)).takeIf { tun },
                    )
                } else {
                    listOf(
                        RunMode.Vpn to stringResource(Res.string.mode_vpn_explained),
                        RunMode.ProxyOnly to stringResource(Res.string.mode_proxy_explained),
                    )
                },
                selected = if (desktop && !tun && s.runMode == RunMode.Vpn) RunMode.SystemProxy else s.runMode,
                onSelected = { v -> onUpdate { it.copy(runMode = v) } },
            )
            if (desktop && tun && s.runMode == RunMode.Vpn) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.mode_tun_system_proxy),
                    summary = stringResource(Res.string.mode_tun_system_proxy_sum),
                    checked = s.tunSystemProxy,
                    onCheckedChange = { v -> onUpdate { it.copy(tunSystemProxy = v) } },
                )
            }
            if (s.runMode == RunMode.ProxyOnly) {
                val platform = LocalPlatform.current
                val copied = stringResource(Res.string.copied)
                val address = "127.0.0.1:${dev.cluvex.zedsecure.core.VpnManager.activeSocksPort ?: s.effectiveLocalProxyPort}"
                SettingsActionRow(
                    title = stringResource(Res.string.mode_local_proxy_title),
                    summary = stringResource(Res.string.mode_local_proxy_body, address),
                ) {
                    platform.copyToClipboard(address)
                    platform.toast(copied)
                }
            }
            if (desktop) {
                SettingsInfoRow(
                    title = stringResource(Res.string.mode_system_proxy),
                    body = stringResource(Res.string.mode_system_proxy_body),
                )
                SettingsInfoRow(
                    title = stringResource(Res.string.mode_socks),
                    body = stringResource(Res.string.mode_socks_body),
                )
                SettingsInfoRow(
                    title = stringResource(Res.string.mode_tun),
                    body = stringResource(Res.string.mode_tun_body),
                )
            } else {
                SettingsInfoRow(
                    title = stringResource(Res.string.mode_vpn_explained),
                    body = stringResource(Res.string.mode_vpn_explained_body),
                )
                SettingsInfoRow(
                    title = stringResource(Res.string.mode_proxy_explained),
                    body = stringResource(Res.string.mode_proxy_explained_body),
                )
            }
        }
    }
}
