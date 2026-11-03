package com.aras.client.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aras.client.handler.SpeedtestManager
import com.aras.client.ui.radar.RadarDisplay
import com.aras.client.ui.radar.RadarPalette
import com.aras.client.ui.radar.RadarNode
import com.aras.client.ui.radar.RadarState
import com.aras.client.ui.radar.TunnelSnapshot
import com.aras.client.ui.radar.TunnelPhase
import com.aras.client.ui.radar.IpCheck
import com.aras.client.ui.radar.resolveRadarState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private enum class RadarVerification { DISCONNECTED, CHECKING, VERIFIED, TUNNEL_IP_UNVERIFIED, TUNNEL_LOST }

private data class RadarIpResult(val tunnelActive: Boolean, val verified: Boolean, val ip: String? = null)

/** Last measured ping for one saved profile. Non-positive values mean unverified. */
data class RadarConfigSignal(val guid: String, val name: String, val pingMs: Long)

@Composable
fun RadarDashboard(
    isRunning: Boolean,
    hasPing: Boolean,
    selectedGuid: String?,
    isDarkTheme: Boolean,
    serverTitle: String,
    configPings: List<RadarConfigSignal> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dangerTransition = rememberInfiniteTransition(label = "radar-danger-blink")
    val dangerAlpha by dangerTransition.animateFloat(
        initialValue = 0.38f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(620), repeatMode = RepeatMode.Reverse),
        label = "danger-pulse"
    )
    var verification by remember { mutableStateOf(RadarVerification.DISCONNECTED) }
    var publicIp by remember { mutableStateOf<String?>(null) }
    var speedRunning by remember { mutableStateOf(false) }
    var speedLatency by remember { mutableStateOf(-1L) }
    var speedDown by remember { mutableStateOf(-1.0) }
    var speedUp by remember { mutableStateOf(-1.0) }
    var speedError by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(speedRunning) {
        if (!speedRunning) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            SpeedtestManager.runFullTest()
        }
        speedLatency = result.latencyMs
        speedDown = result.downloadMbps
        speedUp = result.uploadMbps
        speedError = result.error
        speedRunning = false
    }

    LaunchedEffect(isRunning, hasPing, selectedGuid) {
        publicIp = null
        if (!isRunning) {
            verification = RadarVerification.DISCONNECTED
            return@LaunchedEffect
        }

        verification = RadarVerification.CHECKING
        while (true) {
            val result = withContext(Dispatchers.IO) { verifyVpnEgress(context) }
            publicIp = result.ip
            val snapshot = TunnelSnapshot(
                sessionId = selectedGuid?.hashCode()?.toLong() ?: 0L,
                phase = if (result.tunnelActive) TunnelPhase.RUNNING else TunnelPhase.ERROR,
                tunInterfaceUp = result.tunnelActive,
                trafficBytesLastSample = if (result.verified && !result.ip.isNullOrBlank()) 1L else 0L,
                ipCheck = if (result.verified && !result.ip.isNullOrBlank()) {
                    IpCheck.Verified(result.ip)
                } else {
                    IpCheck.Failed
                }
            )
            verification = when (resolveRadarState(snapshot)) {
                RadarState.CONNECTED_VERIFIED -> RadarVerification.VERIFIED
                RadarState.TUNNEL_IP_UNVERIFIED -> RadarVerification.TUNNEL_IP_UNVERIFIED
                RadarState.ERROR -> RadarVerification.TUNNEL_LOST
                RadarState.DISCONNECTED -> RadarVerification.DISCONNECTED
                RadarState.CONNECTING -> RadarVerification.CHECKING
            }
            delay(5_000)
        }
    }

    val panel = when (verification) {
        RadarVerification.VERIFIED -> Color(0xFF00170A)
        RadarVerification.TUNNEL_IP_UNVERIFIED -> Color(0xFF210307)
        RadarVerification.TUNNEL_LOST -> Color(0xFF210307)
        RadarVerification.DISCONNECTED -> Color(0xFF191C20)
        RadarVerification.CHECKING -> if (isDarkTheme) Color(0xFF111821) else Color(0xFFFFFFFF)
    }
    val outline = when (verification) {
        RadarVerification.VERIFIED -> Color(0xFF07552C)
        RadarVerification.TUNNEL_IP_UNVERIFIED -> Color(0xFF74121C)
        RadarVerification.TUNNEL_LOST -> Color(0xFF74121C)
        RadarVerification.DISCONNECTED -> Color(0xFF414852)
        RadarVerification.CHECKING -> if (isDarkTheme) Color(0xFF273442) else Color(0xFFD2D9E1)
    }
    val primaryText = when (verification) {
        RadarVerification.VERIFIED -> Color(0xFFB8FFD0)
        RadarVerification.TUNNEL_IP_UNVERIFIED -> Color(0xFFFFD9DC)
        RadarVerification.TUNNEL_LOST -> Color(0xFFFFD9DC)
        RadarVerification.DISCONNECTED -> Color(0xFFE2E5E9)
        RadarVerification.CHECKING -> if (isDarkTheme) Color(0xFFEAF1F7) else Color(0xFF17212E)
    }
    val secondaryText = when (verification) {
        RadarVerification.VERIFIED -> Color(0xFF64B982)
        RadarVerification.TUNNEL_IP_UNVERIFIED -> Color(0xFFFF737D)
        RadarVerification.TUNNEL_LOST -> Color(0xFFFF737D)
        RadarVerification.DISCONNECTED -> Color(0xFFFF525C)
        RadarVerification.CHECKING -> if (isDarkTheme) Color(0xFF8997A7) else Color(0xFF5D6978)
    }

    val statusColor = when (verification) {
        RadarVerification.DISCONNECTED -> Color(0xFFFF525C)
        RadarVerification.CHECKING -> Color(0xFFC18A32)
        RadarVerification.TUNNEL_IP_UNVERIFIED -> Color(0xFFFF2938)
        RadarVerification.TUNNEL_LOST -> Color(0xFFFF2938)
        RadarVerification.VERIFIED -> Color(0xFF20FF79)
    }
    val statusLabel = when (verification) {
        RadarVerification.DISCONNECTED -> "NO SIGNAL"
        RadarVerification.CHECKING -> "VERIFYING TUNNEL"
        RadarVerification.TUNNEL_IP_UNVERIFIED -> "DANGER · NO VERIFIED EGRESS"
        RadarVerification.TUNNEL_LOST -> "DANGER"
        RadarVerification.VERIFIED -> "SIGNAL ACTIVE"
    }
    val statusDetail = when (verification) {
        RadarVerification.DISCONNECTED -> "VPN tunnel inactive"
        RadarVerification.CHECKING -> "Checking VPN tunnel, exit IP and selected-config ping"
        RadarVerification.TUNNEL_IP_UNVERIFIED -> if (!hasPing) "DANGER: selected configuration has no successful ping" else "DANGER: traffic through the VPN could not be verified"
        RadarVerification.TUNNEL_LOST -> "VPN is enabled but the VPN network is not active"
        RadarVerification.VERIFIED -> "SECURE TUNNEL ESTABLISHED"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = panel,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, outline)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RADAR // NETWORK STATUS",
                        color = primaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "LIVE TUNNEL MONITOR",
                        color = secondaryText,
                        fontSize = 10.sp,
                        letterSpacing = 1.4.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = when (verification) {
                        RadarVerification.VERIFIED -> "ONLINE"
                        RadarVerification.DISCONNECTED -> "OFFLINE"
                        RadarVerification.TUNNEL_IP_UNVERIFIED -> "UNVERIFIED"
                        RadarVerification.TUNNEL_LOST -> "DANGER"
                        RadarVerification.CHECKING -> "CHECKING"
                    },
                    color = statusColor.copy(
                        alpha = if (verification == RadarVerification.TUNNEL_IP_UNVERIFIED ||
                            verification == RadarVerification.TUNNEL_LOST) dangerAlpha else 1f
                    ),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (expanded) "MINIMIZE" else "EXPAND",
                    color = statusColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 5.dp, vertical = 7.dp)
                )
            }

            Spacer(modifier = Modifier.height(if (expanded) 8.dp else 2.dp))

            RadarDisplay(
                state = when (verification) {
                    RadarVerification.DISCONNECTED -> RadarState.DISCONNECTED
                    RadarVerification.CHECKING -> RadarState.CONNECTING
                    RadarVerification.VERIFIED -> RadarState.CONNECTED_VERIFIED
                    RadarVerification.TUNNEL_IP_UNVERIFIED -> RadarState.TUNNEL_IP_UNVERIFIED
                    RadarVerification.TUNNEL_LOST -> RadarState.ERROR
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (expanded) 18.dp else 42.dp)
                    .heightIn(max = if (expanded) 280.dp else 145.dp),
                palette = if (isDarkTheme) RadarPalette.Night else RadarPalette.Day,
                title = statusLabel,
                subtitle = statusDetail,
                nodes = when (verification) {
                    RadarVerification.DISCONNECTED, RadarVerification.CHECKING -> emptyList()
                    else -> if (configPings.isNotEmpty()) {
                        configPings.mapIndexed { index, config ->
                            val profileHealthy = config.pingMs > 0L &&
                                (config.guid != selectedGuid || hasPing)
                            val tunnelHealthy = verification == RadarVerification.VERIFIED
                            RadarNode(
                                bearingDeg = ((kotlin.math.abs(config.guid.hashCode().toLong()) % 360L).toFloat() + index * 17f) % 360f,
                                distance01 = 0.24f + (index % 5) * 0.14f,
                                pingMs = if (profileHealthy && tunnelHealthy) config.pingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null,
                                tunnelWorking = tunnelHealthy,
                                checked = true,
                                id = config.guid,
                                selected = config.guid == selectedGuid
                            )
                        }
                    } else when (verification) {
                        RadarVerification.VERIFIED -> listOf(
                            RadarNode(45f, 0.52f, if (hasPing) 75 else null, tunnelWorking = true, checked = hasPing)
                        )
                        RadarVerification.TUNNEL_IP_UNVERIFIED, RadarVerification.TUNNEL_LOST ->
                            listOf(RadarNode(45f, 0.52f, null, tunnelWorking = false, checked = false))
                        else -> emptyList()
                    }
                },
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(when (verification) {
                            RadarVerification.VERIFIED -> Color(0xFF00230F)
                            RadarVerification.TUNNEL_IP_UNVERIFIED, RadarVerification.TUNNEL_LOST -> Color(0xFF33060C)
                            RadarVerification.DISCONNECTED -> Color(0xFF252A30)
                            RadarVerification.CHECKING -> if (isDarkTheme) Color(0xFF171F29) else Color(0xFFF1F4F7)
                        })
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PUBLIC EGRESS IP", color = secondaryText, fontSize = 9.sp, letterSpacing = 1.sp)
                        Text(
                            text = publicIp ?: if (verification == RadarVerification.CHECKING) "Checking…" else "—",
                            color = primaryText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                        Text("SELECTED NODE", color = secondaryText, fontSize = 9.sp, letterSpacing = 1.sp)
                        Text(
                            text = if (isRunning) serverTitle.take(22).ifBlank { "Connected profile" } else "No active node",
                            color = primaryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End,
                            maxLines = 1
                        )
                    }
                }
                Text(
                    text = "IP check uses the active VPN network when Android exposes it.",
                    color = secondaryText,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 7.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "SPEED TEST",
                    color = secondaryText,
                    fontSize = 9.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when (verification) {
                                RadarVerification.VERIFIED -> Color(0xFF00230F)
                                RadarVerification.TUNNEL_IP_UNVERIFIED, RadarVerification.TUNNEL_LOST -> Color(0xFF33060C)
                                RadarVerification.DISCONNECTED -> Color(0xFF252A30)
                                RadarVerification.CHECKING -> if (isDarkTheme) Color(0xFF171F29) else Color(0xFFF1F4F7)
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text("PING", color = secondaryText, fontSize = 9.sp, letterSpacing = 0.8.sp)
                        Text(
                            SpeedtestManager.formatLatency(speedLatency),
                            color = primaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text("DOWNLOAD", color = secondaryText, fontSize = 9.sp, letterSpacing = 0.8.sp)
                        Text(
                            SpeedtestManager.formatMbps(speedDown),
                            color = primaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text("UPLOAD", color = secondaryText, fontSize = 9.sp, letterSpacing = 0.8.sp)
                        Text(
                            SpeedtestManager.formatMbps(speedUp),
                            color = primaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.18f))
                        .clickable(enabled = !speedRunning) {
                            speedRunning = true
                            speedError = null
                        }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (speedRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = statusColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("TESTING…", color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    } else {
                        Text("RUN SPEED TEST", color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
                speedError?.let {
                    Text(it, color = Color(0xFFFF737D), fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    text = "Measures real throughput via Cloudflare endpoints (VPN path when connected).",
                    color = secondaryText,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

private fun verifyVpnEgress(context: Context): RadarIpResult {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return RadarIpResult(tunnelActive = false, verified = false)

    val activeNetwork = connectivity.activeNetwork
    val vpnNetworks = buildList {
        if (activeNetwork != null) {
            val caps = connectivity.getNetworkCapabilities(activeNetwork)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add(activeNetwork)
        }
        connectivity.allNetworks.forEach { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            if (
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                network !in this
            ) {
                add(network)
            }
        }
    }

    if (vpnNetworks.isEmpty()) {
        return RadarIpResult(tunnelActive = false, verified = false)
    }

    val endpoints = listOf(
        "https://api.ipify.org",
        "https://icanhazip.com/"
    )

    for (network in vpnNetworks) {
        for (endpoint in endpoints) {
            val connection = try {
                network.openConnection(URL(endpoint)) as? HttpURLConnection
            } catch (_: Exception) {
                null
            } ?: continue

            try {
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "text/plain")
                connection.instanceFollowRedirects = true

                if (connection.responseCode !in 200..299) continue

                val ip = connection.inputStream.bufferedReader().use { it.readText() }
                    .trim()
                    .take(64)
                val looksLikeIp = ip.isNotBlank() &&
                    ip.matches(Regex("^[0-9a-fA-F:.]+$")) &&
                    (ip.contains('.') || ip.contains(':'))
                if (looksLikeIp) {
                    return RadarIpResult(tunnelActive = true, verified = true, ip = ip)
                }
            } catch (_: Exception) {
            } finally {
                try { connection.disconnect() } catch (_: Exception) {}
            }
        }
    }
    return RadarIpResult(tunnelActive = true, verified = false)
}
