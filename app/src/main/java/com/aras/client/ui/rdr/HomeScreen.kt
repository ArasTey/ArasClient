package com.aras.client.ui.rdr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aras.client.R

/** ابعاد مشترک نوار بالا؛ ArcMenu هم از همین‌ها برای قرار دادن دکمه‌ی بستن استفاده می‌کند. */
object RdrDims {
    val topBarHeight = 64.dp
    val topBarPadding = 16.dp
    val menuButton = 44.dp
}

/**
 * صفحه‌ی اصلی مطابق طرح:
 *  ┌ نوار بالا ┐
 *  (دایره)  رادار = دکمه‌ی اتصال
 *  [مستطیل بزرگ] کانفیگ فعلی → لمس: لیست کانفیگ‌ها
 *  [مستطیل کوچک] آپلود / دانلود / زمان
 */
@Composable
fun RdrHomeScreen(
    state: RdrHomeState,
    actions: RdrHomeActions,
    onOpenConfigs: () -> Unit,
    onMenu: () -> Unit,
    toolbar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        RdrTopBar(onMenu = onMenu, toolbar = toolbar)

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val radarSize = minOf(maxWidth * 0.80f, maxHeight * 0.96f, 330.dp)
            val hint = when (state.radar) {
                RadarState.IDLE -> stringResource(R.string.rdr_tap_to_connect)
                RadarState.CONNECTED -> stringResource(R.string.rdr_tap_to_disconnect)
                else -> null
            }
            RadarButton(
                state = state.radar,
                onClick = actions.onRadarClick,
                size = radarSize,
                subLabel = hint,
                modifier = Modifier.enterAnim(0),
            )
        }

        ConfigCard(
            server = state.selected,
            radar = state.radar,
            onClick = onOpenConfigs,
            onSmartConnect = actions.onSmartConnect,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .enterAnim(90),
        )
        Spacer(Modifier.height(14.dp))
        TrafficPill(
            traffic = state.traffic,
            active = state.radar == RadarState.CONNECTED,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .enterAnim(160),
        )
        Spacer(Modifier.height(18.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** ورود نرم عناصر (لغزش به بالا + محو شدن) با تأخیر پلکانی */
@Composable
private fun Modifier.enterAnim(delayMs: Int): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        p.animateTo(1f, tween(520))
    }
    return this.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 36.dp.toPx()
    }
}

// ───────────────────────── نوار بالا ─────────────────────────

@Composable
fun RdrTopBar(
    onMenu: () -> Unit,
    toolbar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // طبق طرح: همبرگری سمت راست، کپسول ابزار سمت چپ؛ همیشه (فارسی و غیر فارسی)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(RdrDims.topBarHeight)
                .padding(horizontal = RdrDims.topBarPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                Icons.Rounded.Menu,
                stringResource(R.string.rdr_menu_title),
                onMenu,
                size = RdrDims.menuButton,
            )
            Spacer(Modifier.width(10.dp))
            Wordmark()
            Spacer(Modifier.weight(1f))
            toolbar()
        }
    }
}

/** لوگوتایپ همیشه LTR و تک‌خطی (باگ «VP / N» قبلی همین‌جا حل شده). */
@Composable
private fun Wordmark() {
    val c = LocalRdrColors.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "RADAR",
                color = c.text,
                fontWeight = FontWeight.Black,
                fontSize = 19.sp,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "VPN",
                color = c.accent,
                fontWeight = FontWeight.Black,
                fontSize = 19.sp,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// ───────────────────────── مستطیل بزرگ: کانفیگ فعلی ─────────────────────────

@Composable
fun ConfigCard(
    server: ServerUi?,
    radar: RadarState,
    onClick: () -> Unit,
    onSmartConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalRdrColors.current
    val ring by animateColorAsState(
        when (radar) {
            RadarState.CONNECTED -> c.green.copy(alpha = 0.65f)
            RadarState.DANGER -> c.red.copy(alpha = 0.70f)
            RadarState.CONNECTING -> c.amber.copy(alpha = 0.60f)
            RadarState.IDLE -> Color.Transparent
        },
        tween(500),
        label = "cardRing",
    )
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier
            .fillMaxWidth()
            .bouncyClick(haptic = true, pressedScale = 0.975f, onClick = onClick)
            .glass(shape, strong = true, shadow = 18.dp)
            .border(1.6.dp, ring, shape)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagBadge(server?.flag ?: "🌐", size = 54.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (server == null) {
                Text(
                    stringResource(R.string.rdr_no_config),
                    color = c.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.rdr_pick_config),
                    color = c.textSecondary,
                    fontSize = 13.sp,
                )
            } else {
                Text(
                    server.name,
                    color = c.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    androidx.core.text.BidiFormatter.getInstance().unicodeWrap("${maskHost(server.host)}:${server.port}"),
                    color = c.textSecondary,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolChip(server.protocol)
                    Spacer(Modifier.width(8.dp))
                    PingBadge(server.pingMs, server.testing)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        GlassIconButton(
            Icons.Rounded.Bolt,
            stringResource(R.string.rdr_smart_connect),
            onSmartConnect,
            size = 42.dp,
            tint = c.accent,
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = c.textSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

// ───────────────────────── مستطیل کوچک: ترافیک و زمان ─────────────────────────

@Composable
fun TrafficPill(traffic: TrafficUi, active: Boolean, modifier: Modifier = Modifier) {
    val c = LocalRdrColors.current
    val tnum = TextStyle(fontFeatureSettings = "tnum")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier
                .glass(CircleShape, shadow = 12.dp)
                .graphicsLayer { alpha = if (active) 1f else 0.62f }
                .padding(horizontal = 22.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Stat(Icons.Rounded.ArrowUpward, c.accent, formatSpeed(traffic.upBytesPerSec), tnum)
            Box(Modifier.size(width = 1.dp, height = 16.dp).background(c.separator))
            Stat(Icons.Rounded.ArrowDownward, c.green, formatSpeed(traffic.downBytesPerSec), tnum)
            Box(Modifier.size(width = 1.dp, height = 16.dp).background(c.separator))
            Stat(Icons.Rounded.Timer, c.textSecondary, formatElapsed(traffic.elapsedSec), tnum)
        }
    }
}

@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String, style: TextStyle) {
    val c = LocalRdrColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, style = style.copy(color = c.text), maxLines = 1)
    }
}

// ───────────────────────── اجزای مشترک کارت‌ها ─────────────────────────

@Composable
fun FlagBadge(flag: String, size: Dp = 48.dp) {
    Box(
        Modifier
            .size(size)
            .glass(CircleShape, shadow = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(flag, fontSize = (size.value * 0.5f).sp)
    }
}

@Composable
fun ProtocolChip(protocol: String) {
    val c = LocalRdrColors.current
    Text(
        protocol.uppercase(),
        color = c.accent,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        maxLines = 1,
        modifier = Modifier
            .background(c.accent.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun PingBadge(pingMs: Int?, testing: Boolean) {
    val c = LocalRdrColors.current
    if (testing) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = c.accent,
        )
        return
    }
    val (text, color) = when {
        pingMs == null -> "—" to c.textSecondary
        pingMs < 0 -> stringResource(R.string.rdr_ping_timeout) to c.red
        pingMs < 200 -> "$pingMs ms" to c.green
        pingMs < 500 -> "$pingMs ms" to c.amber
        else -> "$pingMs ms" to c.red
    }
    Text(
        text,
        color = color,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .background(color.copy(alpha = 0.13f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}
