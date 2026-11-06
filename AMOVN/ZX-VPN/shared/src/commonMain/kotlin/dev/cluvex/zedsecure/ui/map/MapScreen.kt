package dev.cluvex.zedsecure.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.zedsecure.core.VpnManager
import dev.cluvex.zedsecure.data.map.WorldMap
import dev.cluvex.zedsecure.domain.model.ConnectionState
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.ic_info
import dev.cluvex.zedsecure.shared.resources.map_exit
import dev.cluvex.zedsecure.shared.resources.map_locating
import dev.cluvex.zedsecure.shared.resources.map_no_origin
import dev.cluvex.zedsecure.shared.resources.map_offline_here
import dev.cluvex.zedsecure.shared.resources.map_other_vpn
import dev.cluvex.zedsecure.shared.resources.map_ping
import dev.cluvex.zedsecure.shared.resources.map_sea_caspian
import dev.cluvex.zedsecure.shared.resources.map_sea_persian_gulf
import dev.cluvex.zedsecure.shared.resources.map_server
import dev.cluvex.zedsecure.shared.resources.map_subtitle
import dev.cluvex.zedsecure.shared.resources.map_title
import dev.cluvex.zedsecure.shared.resources.map_unknown
import dev.cluvex.zedsecure.shared.resources.map_vpn_warning
import dev.cluvex.zedsecure.shared.resources.map_you
import dev.cluvex.zedsecure.shared.resources.map_you_saved
import dev.cluvex.zedsecure.ui.format.ltrIsolate
import dev.cluvex.zedsecure.ui.theme.Personalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun MapScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    ipApiUrl: String = "",
    personalization: Personalization = Personalization.Default,

    savedOriginCode: String = "",
    savedOriginLabel: String = "",
    onRealLocation: (code: String, label: String) -> Unit = { _, _ -> },
    vm: MapViewModel = viewModel { MapViewModel() },
) {
    val status by VpnManager.status.collectAsStateWithLifecycle()
    val connected = status.state == ConnectionState.Connected

    val ownTunnel = status.state.isActive || status.state.isTransitioning

    var deviceVpn by remember { mutableStateOf(VpnManager.deviceVpnActive()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(VPN_POLL_MS)
            deviceVpn = withContext(Dispatchers.Default) { VpnManager.deviceVpnActive() }
        }
    }
    val vpnOn = ownTunnel || deviceVpn
    val countries by vm.countries.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()

    LaunchedEffect(ipApiUrl) { vm.preferredIpApiUrl = ipApiUrl.ifBlank { null } }

    val autoSession by dev.cluvex.zedsecure.core.AutoSelect.session.collectAsStateWithLifecycle()
    val exitGeneration = autoSession?.exitGeneration ?: 0

    LaunchedEffect(connected, status.sessionId, vpnOn, exitGeneration) { vm.refresh() }

    LaunchedEffect(vpnOn, info) {
        val code = info.countryCode
        if (!vpnOn && !info.underVpn && !info.loading && !code.isNullOrBlank()) {
            onRealLocation(code.uppercase(), locationLabel(info.city, info.country))
        }
    }

    val accent = personalization.connectedColor ?: MaterialTheme.colorScheme.primary
    val originTint = MaterialTheme.colorScheme.tertiary
    val land = MaterialTheme.colorScheme.onSurface

    val lookupIsExit = info.underVpn
    val originCode = when {
        vpnOn -> savedOriginCode
        lookupIsExit -> ""
        else -> info.countryCode.orEmpty()
    }
    val originLabel = when {
        vpnOn -> savedOriginLabel
        lookupIsExit -> ""
        else -> locationLabel(info.city, info.country)
    }
    val exitCode = if (vpnOn && lookupIsExit) info.countryCode.orEmpty() else ""
    val exitLabel = if (vpnOn && lookupIsExit) locationLabel(info.city, info.country) else ""
    val needsVpnOff = vpnOn && savedOriginCode.isBlank()

    val origin = WorldMap.anchorOf(countries, originCode)
        ?.let { MapPoint(it.first, it.second, originLabel, originCode.uppercase()) }
    val exit = WorldMap.anchorOf(countries, exitCode)
        ?.let { MapPoint(it.first, it.second, exitLabel, exitCode.uppercase()) }

    val persianGulf = stringResource(Res.string.map_sea_persian_gulf)
    val caspian = stringResource(Res.string.map_sea_caspian)
    val labels = remember(persianGulf, caspian) {
        listOf(
            seaCaption(persianGulf, lon = 51.8f, lat = 26.8f, widthDegrees = 6.5f),
            seaCaption(caspian, lon = 50.9f, lat = 41.6f, widthDegrees = 4.0f),
        )
    }

    Box(modifier.fillMaxSize()) {
        WorldMapCanvas(
            countries = countries,
            origin = origin,
            exit = exit,
            accent = accent,
            originColor = originTint,
            landColor = land,
            reduceMotion = reduceMotion,
            labels = labels,
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 20.dp),
        ) {
            dev.cluvex.zedsecure.ui.components.PageBackButton()
            Text(
                stringResource(Res.string.map_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(Res.string.map_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            AnimatedVisibility(
                visible = !originCode.isNullOrBlank() || !exitCode.isNullOrBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                RouteBadge(
                    originCode = originCode.takeIf { it.isNotBlank() },
                    originLabel = shortLabel(originLabel),
                    destinationCode = exitCode.takeIf { it.isNotBlank() },
                    destinationLabel = shortLabel(exitLabel),
                    accent = accent,
                    originColor = originTint,
                    reduceMotion = reduceMotion,
                    linked = vpnOn,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            AnimatedVisibility(info.loading, enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        stringResource(Res.string.map_locating),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    EndpointRow(
                        dot = originTint,
                        hollow = true,

                        caption = stringResource(
                            if (vpnOn && savedOriginCode.isNotBlank()) Res.string.map_you_saved else Res.string.map_you,
                        ),
                        value = originLabel.ifBlank {
                            stringResource(if (vpnOn) Res.string.map_no_origin else Res.string.map_unknown)
                        },
                    )
                    if (needsVpnOff) VpnOffWarning()
                    if (vpnOn) {
                        EndpointRow(
                            dot = accent,
                            hollow = false,
                            caption = stringResource(Res.string.map_exit),
                            value = exitLabel.ifBlank { stringResource(Res.string.map_unknown) },
                        )
                    }

                    val protocol = status.serverName
                    val ping = info.pingMs
                    if (connected && (protocol != null || ping != null)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            ping?.let { Stat(stringResource(Res.string.map_ping), ltrIsolate("$it ms")) }
                            protocol?.let { Stat(stringResource(Res.string.map_server), it) }
                        }
                    }
                    val note = when {
                        !vpnOn -> Res.string.map_offline_here

                        deviceVpn && !ownTunnel && !needsVpnOff -> Res.string.map_other_vpn
                        else -> null
                    }
                    note?.let {
                        Text(
                            stringResource(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VpnOffWarning() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(Res.drawable.ic_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(Res.string.map_vpn_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun EndpointRow(dot: Color, hollow: Boolean, caption: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(if (hollow) 11.dp else 12.dp)
                .clip(CircleShape),
        ) {
            Surface(
                color = if (hollow) Color.Transparent else dot,
                shape = CircleShape,
                border = if (hollow) androidx.compose.foundation.BorderStroke(2.dp, dot) else null,
                modifier = Modifier.fillMaxSize(),
            ) {}
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Stat(caption: String, value: String) {
    Column {
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun seaCaption(text: String, lon: Float, lat: Float, widthDegrees: Float): MapLabel {
    val (x, y) = WorldMap.gridOf(lon, lat)
    return MapLabel(x, y, text, span = widthDegrees / 360f * WorldMap.WIDTH)
}

private fun locationLabel(city: String?, country: String?): String = listOfNotNull(
    city?.takeIf { it.isNotBlank() },
    country?.takeIf { it.isNotBlank() },
).joinToString(", ")

private fun shortLabel(label: String): String =
    label.substringBefore(',').trim()

private const val VPN_POLL_MS = 2_000L
