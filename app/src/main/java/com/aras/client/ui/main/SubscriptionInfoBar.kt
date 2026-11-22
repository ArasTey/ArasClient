package com.aras.client.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.entities.SubscriptionItem
import com.aras.client.extension.withLatinDigits
import com.aras.client.handler.MmkvManager
import com.aras.client.ui.compose.CardCornerRadius
import com.aras.client.ui.compose.LocalGlassPalette
import com.aras.client.ui.compose.glassPanel
import com.aras.client.util.JalaliDate
import com.aras.client.util.Utils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SubscriptionInfoBar(
    groupId: String,
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onEditSubscription: (() -> Unit)? = null,
    onUpdateSubscription: (() -> Unit)? = null,
    onPingSubscription: (() -> Unit)? = null,
    onCancelPingSubscription: (() -> Unit)? = null,
    onSortSubscription: (() -> Unit)? = null,
    onRemoveSubscription: (() -> Unit)? = null,
    onCopySubscriptionUrl: (() -> Unit)? = null,
    isProtectedSubscription: Boolean = false,
) {
    if (groupId.isEmpty()) return
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val showInfo = MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_SUB_INFO, true)
    // Shown by default and switchable in the bar itself: it is the subscription's
    // own word to the user, not something to keep hidden behind a settings screen.
    // Re-read on every resume: the same MMKV key is also edited in Settings and
    // the sub settings dialog, and this bar has no other way to hear about it.
    var showAnnouncement by remember {
        mutableStateOf(
            MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_SUB_ANNOUNCEMENT, true)
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                showAnnouncement =
                    MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_SUB_ANNOUNCEMENT, true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var sub by remember(groupId) { mutableStateOf<SubscriptionItem?>(null) }
    LaunchedEffect(groupId) {
        // initial load, then re-read on every update broadcast (5s poll as
        // a safety net so the bar always catches up)
        sub = withContext(Dispatchers.IO) { MmkvManager.decodeSubscription(groupId) }
        launch {
            while (true) {
                delay(5000)
                sub = withContext(Dispatchers.IO) { MmkvManager.decodeSubscription(groupId) }
                // Other pager pages toggle the same MMKV key without this activity
                // ever pausing, so the safety-net poll re-reads it too.
                showAnnouncement =
                    MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_SUB_ANNOUNCEMENT, true)
            }
        }
        com.aras.client.util.SubscriptionUpdateNotifier.flow.collect {
            sub = withContext(Dispatchers.IO) { MmkvManager.decodeSubscription(groupId) }
        }
    }
    val current = sub ?: return

    // Panels that do not cap volume send total=0; the used amount alone is still
    // worth showing, as "used · Unlimited".
    val hasInfo = showInfo &&
            (current.trafficTotal > 0 || current.trafficUsed > 0 || current.expireAt > 0)
    // Panel subs carry the volume/expiry headers together with profile-title but
    // often no announce header at all. The banner is the only place either text is
    // shown, so the creator message alone has to be enough to raise it.
    val hasAnnouncement = current.announcement?.isNotBlank() == true ||
            current.creatorMessage?.isNotBlank() == true
    val creatorMessage = current.creatorMessage?.takeIf { it.isNotBlank() }
    val announcement = current.announcement?.takeIf {
        showAnnouncement && it.isNotBlank()
    }
    val bannerText = announcement ?: creatorMessage?.takeIf { showAnnouncement }
    // The panel's links; the subscription URL answers with the panel's information
    // page, so it is the fallback target of the page button.
    val supportUrl = current.supportUrl?.takeIf { Utils.isSafeLink(it) }
    val webPageUrl = current.webPageUrl?.takeIf { Utils.isSafeLink(it) }
            ?: current.url.takeIf { Utils.isSafeLink(it) }

    // A glass card with nothing but five icons in it reads as an empty box, so
    // the panel only draws its chrome when it actually has information to show;
    // otherwise the actions sit directly on the backdrop like a toolbar. A hidden
    // announcement still frames the panel, or the toggle that brings it back
    // would move the whole bar on every tap.
    val framed = hasAnnouncement || hasInfo
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (framed) {
                        Modifier.glassPanel(
                            palette = LocalGlassPalette.current,
                            shape = RoundedCornerShape(16.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(
                    start = if (framed) 8.dp else 2.dp,
                    end = if (framed) 8.dp else 2.dp,
                    top = if (framed) 6.dp else 3.dp,
                    bottom = if (framed) 4.dp else 1.dp
                ),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            AnimatedVisibility(visible = bannerText != null) {
                bannerText?.let { text ->
                    // The subtitle only makes sense when the banner headline is the
                    // announcement; a banner raised by the creator message alone
                    // would otherwise repeat itself.
                    AnnouncementBanner(
                        text = text,
                        creatorMessage = if (text == announcement) creatorMessage else null
                    )
                }
            }
            if (hasInfo) TrafficExpiryBar(current)
            if (bannerText != null || hasInfo) {
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(LocalGlassPalette.current.hairline))
            }
            SubscriptionActions(
                groupId = groupId,
                supportUrl = supportUrl,
                webPageUrl = webPageUrl,
                onEdit = onEditSubscription.takeUnless { isProtectedSubscription },
                onUpdate = onUpdateSubscription,
                onPing = onPingSubscription,
                // The gauge turns into a stop button while its own round runs:
                // a tap then cancels the ping instead of restarting it.
                onCancelPing = onCancelPingSubscription,
                pinging = onCancelPingSubscription != null &&
                        uiState.pingTargetGroupId == groupId && uiState.isTesting,
                onSort = onSortSubscription,
                onRemove = onRemoveSubscription.takeUnless { isProtectedSubscription },
                onCopy = onCopySubscriptionUrl.takeUnless { isProtectedSubscription },
                hasAnnouncement = hasAnnouncement,
                // The banner can be raised by the creator message alone, so the
                // toggle must follow what is actually on screen, not the announce
                // header — otherwise the icon reads "off" while the text is shown.
                announcementVisible = bannerText != null,
                onToggleAnnouncement = if (hasAnnouncement) {
                    {
                        showAnnouncement = !showAnnouncement
                        MmkvManager.encodeSettings(
                            AppConfig.PREF_SHOW_SUB_ANNOUNCEMENT,
                            showAnnouncement
                        )
                    }
                } else null,
                // The actions always span the full width and spread evenly, so the
                // gap between icons grows when there are few and tightens when
                // there are many; the pill only differs in drawing its own chrome.
                modifier = Modifier,
                compact = !framed
            )
        }
    }
}

@Composable
private fun SubscriptionActions(
    groupId: String,
    supportUrl: String?,
    webPageUrl: String?,
    onEdit: (() -> Unit)?,
    onUpdate: (() -> Unit)?,
    onPing: (() -> Unit)?,
    onCancelPing: (() -> Unit)? = null,
    pinging: Boolean = false,
    onSort: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    hasAnnouncement: Boolean = false,
    announcementVisible: Boolean = false,
    onToggleAnnouncement: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var deleteStage by remember(groupId) { mutableStateOf(0) }
    val context = LocalContext.current
    val palette = LocalGlassPalette.current
    val editGreen = palette.pingGood
    val pingAmber = palette.amber
    // Deliberately bluer than the accent, so support never reads as the same
    // action as its neighbours.
    val supportBlue = if (palette.isDark) Color(0xFF7FA8FF) else Color(0xFF2F6FE4)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (compact) {
                    Modifier.glassPanel(
                        palette = palette,
                        shape = RoundedCornerShape(CardCornerRadius),
                        fill = if (palette.isDark) Color(0x1FCFE9F5) else Color(0xC9F7FCFF),
                        borderColor = if (palette.isDark) Color(0x3DCFE9F5) else Color(0xE0FFFFFF)
                    ).padding(horizontal = 4.dp, vertical = 1.dp)
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        onEdit?.let {
            SubBarAction(R.drawable.ic_edit_24dp, R.string.acc_edit, tint = editGreen, onClick = it)
        }
        onUpdate?.let {
            SubBarAction(R.drawable.ic_check_update_24dp, R.string.sub_bar_update, tint = palette.accent, onClick = it)
        }
        onPing?.let {
            if (pinging && onCancelPing != null) {
                SubBarAction(
                    R.drawable.ic_stop_24dp,
                    R.string.sub_bar_stop_ping,
                    tint = palette.danger,
                    onClick = onCancelPing,
                )
            } else {
                SubBarAction(R.drawable.ic_speedtest_24dp, R.string.title_ping_selected_subscription, tint = pingAmber, onClick = it)
            }
        }
        onSort?.let {
            SubBarAction(R.drawable.ic_outline_filter_alt_24, R.string.title_sort_by_test_results, tint = palette.accent, onClick = it)
        }
        onCopy?.let {
            SubBarAction(R.drawable.ic_copy, R.string.share_method_clipboard, tint = palette.accent, onClick = it)
        }
        // Only a subscription that has an announcement gets the switch: there is
        // nothing to show or hide otherwise.
        if (hasAnnouncement && onToggleAnnouncement != null) {
            SubBarAction(
                if (announcementVisible) R.drawable.ic_announcement_on_24dp
                else R.drawable.ic_announcement_off_24dp,
                if (announcementVisible) R.string.sub_info_hide_announcement
                else R.string.sub_info_show_announcement,
                tint = if (announcementVisible) palette.amber else palette.faint,
                onClick = onToggleAnnouncement
            )
        }
        // Remote links, so the sanitize check sits here too, not only at parse time.
        supportUrl?.let { url ->
            SubBarAction(
                if (Utils.isTelegramLink(url)) R.drawable.ic_telegram_24dp else R.drawable.ic_support_24dp,
                R.string.sub_info_open_support,
                tint = supportBlue,
                onClick = { Utils.openUri(context, url) },
            )
        }
        webPageUrl?.let { url ->
            SubBarAction(
                R.drawable.ic_about_24dp,
                R.string.sub_info_open_page,
                tint = palette.accent,
                onClick = { Utils.openUri(context, url) },
            )
        }
        onRemove?.let {
            SubBarAction(R.drawable.ic_delete_24dp, R.string.acc_delete, tint = palette.danger, onClick = { deleteStage = 1 })
        }
    }
    if (deleteStage > 0) {
        AlertDialog(
            onDismissRequest = { deleteStage = 0 },
            title = { Text(stringResource(if (deleteStage == 1) R.string.acc_delete else R.string.sub_bar_delete_final)) },
            text = { Text(stringResource(if (deleteStage == 1) R.string.confirm_delete_subscription_group else R.string.sub_bar_delete_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    if (deleteStage == 1) deleteStage = 2
                    else {
                        deleteStage = 0
                        onRemove?.invoke()
                    }
                }) { Text(stringResource(if (deleteStage == 1) android.R.string.ok else R.string.acc_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteStage = 0 }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun TrafficExpiryBar(sub: SubscriptionItem) {
    val palette = LocalGlassPalette.current
    // The configuration locale, not Locale.getDefault(): a per-app language pick
    // is what the user actually sees, and on older APIs it never reaches the VM default.
    val appLocale = LocalConfiguration.current.locales.get(0) ?: Locale.getDefault()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 1.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_subscriptions_24dp),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = trafficSummary(sub, stringResource(R.string.sub_traffic_unlimited)),
                style = MaterialTheme.typography.labelMedium,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (sub.expireAt > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = expiryLabel(sub.expireAt, appLocale),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = if (isExpiringSoon(sub.expireAt))
                        palette.danger
                    else
                        palette.faint
                )
            }
        }
        if (sub.trafficTotal > 0 && sub.trafficUsed >= 0) {
            Spacer(Modifier.height(6.dp))
            val ratio = (sub.trafficUsed.toDouble() / sub.trafficTotal)
                .coerceIn(0.0, 1.0).toFloat()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(palette.hairline)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (ratio > 0.9f) palette.danger else palette.accent)
                )
            }
        }
    }
}

@Composable
private fun SubBarAction(
    drawableRes: Int,
    contentDescRes: Int,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
    ) {
        Icon(
            painter = painterResource(drawableRes),
            contentDescription = stringResource(contentDescRes),
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun AnnouncementBanner(text: String, creatorMessage: String?) {
    val palette = LocalGlassPalette.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.accent.copy(alpha = if (palette.isDark) 0.13f else 0.08f))
            .border(0.7.dp, palette.accent.copy(alpha = 0.20f), shape)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_promotion_24dp),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        creatorMessage?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = palette.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun trafficSummary(sub: SubscriptionItem, unlimitedLabel: String): String {
    val used = sub.trafficUsed.coerceAtLeast(0)
    if (sub.trafficTotal <= 0) {
        if (used <= 0) return ""
        return "${formatBytes(used)} · $unlimitedLabel"
    }
    return "${formatBytes(used)} / ${formatBytes(sub.trafficTotal)}"
}

/**
 * Panels send `expire` in epoch seconds; anything below the millisecond scale
 * has to be lifted first, or a 2026 subscription date lands in January 1970.
 */
private fun expireMillis(expireAt: Long): Long =
    if (expireAt in 1 until 10_000_000_000L) expireAt * 1000 else expireAt

/**
 * When the volume runs out: the date itself, so it can be checked against the
 * panel's own page, and how long is left. The year only shows when it is not
 * this one, which is what keeps the line short.
 */
private fun expiryLabel(
    expireAt: Long,
    locale: Locale,
    now: Long = System.currentTimeMillis(),
): String {
    val millis = expireMillis(expireAt)
    val zone = ZoneId.systemDefault()
    val expiry = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone)
    // A Persian UI reads its dates in the Solar Hijri (Shamsi) calendar; every
    // other locale keeps the Gregorian formatter it already knows.
    val date = if (locale.language == "fa") {
        JalaliDate.formatShort(millis, now)
    } else {
        val pattern = if (expiry.year == today.year) "d MMM" else "d MMM yyyy"
        DateTimeFormatter.ofPattern(pattern, locale)
            .format(expiry)
            .withLatinDigits()
    }
    if (millis <= now) return date
    return "$date · ${expirySummary(millis, now)}"
}

private fun expirySummary(expireAt: Long, now: Long = System.currentTimeMillis()): String {
    val days = TimeUnit.MILLISECONDS.toDays(expireAt - now)
    return when {
        expireAt <= now -> "-"
        // No space before the unit: in an RTL line "6 d" is reordered to "d 6"
        // and reads like a malformed date, while "6d" stays a single token.
        days >= 1 -> "${days}d"
        else -> "${TimeUnit.MILLISECONDS.toHours(expireAt - now)}h"
    }
}

private fun isExpiringSoon(expireAt: Long): Boolean {
    val remaining = expireMillis(expireAt) - System.currentTimeMillis()
    return remaining in 1..TimeUnit.DAYS.toMillis(3)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}"
    else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
