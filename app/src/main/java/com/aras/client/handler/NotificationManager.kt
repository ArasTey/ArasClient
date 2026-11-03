package com.aras.client.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.core.CoreServiceManager
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.extension.delay
import com.aras.client.extension.toSpeedString
import com.aras.client.ui.main.MainActivity
import com.aras.client.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Foreground VPN notification for RADAR VPN.
 *
 * Collapsed: status + profile/ping line.
 * Expanded: live PING / DOWN / UP, session chronometer, connecting bar.
 * Actions: Disconnect · Restart (interactive).
 *
 * Channel is versioned so "reset" creates a fresh channel after upgrades.
 */
object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_XRAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_XRAY = 2
    private const val QUERY_INTERVAL_MS = 2500L

    private var lastQueryTime = 0L
    private var mBuilder: NotificationCompat.Builder? = null
    private var speedNotificationJob: Job? = null
    private var mNotificationManager: NotificationManager? = null
    private var sessionStartElapsed = 0L
    private var isConnectedState = false
    private var lastDownText = "—"
    private var lastUpText = "—"

    fun startSpeedNotification() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) return
        if (speedNotificationJob != null || CoreServiceManager.isRunning() == false) return

        var lastZeroSpeed = false
        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                lastZeroSpeed = updateSpeedNotificationOnce(lastZeroSpeed)
                delay(QUERY_INTERVAL_MS)
            }
        }
    }

    fun showNotification(currentConfig: ProfileItem?) {
        val service = getService() ?: return
        lastQueryTime = System.currentTimeMillis()
        sessionStartElapsed = SystemClock.elapsedRealtime()
        isConnectedState = false
        lastDownText = "—"
        lastUpText = "—"

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val contentPendingIntent = PendingIntent.getActivity(
            service,
            NOTIFICATION_PENDING_INTENT_CONTENT,
            Intent(service, MainActivity::class.java),
            flags
        )

        val stopXrayPendingIntent = PendingIntent.getBroadcast(
            service,
            NOTIFICATION_PENDING_INTENT_STOP_XRAY,
            Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
                `package` = AppConfig.ANG_PACKAGE
                putExtra("key", AppConfig.MSG_STATE_STOP)
            },
            flags
        )

        val restartXrayPendingIntent = PendingIntent.getBroadcast(
            service,
            NOTIFICATION_PENDING_INTENT_RESTART_XRAY,
            Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
                `package` = AppConfig.ANG_PACKAGE
                putExtra("key", AppConfig.MSG_STATE_RESTART)
            },
            flags
        )

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            ""
        }

        val collapsed = buildCollapsedViews(
            service,
            title = service.getString(R.string.radar_notif_title_connecting),
            subtitle = service.getString(R.string.radar_notif_subtitle_starting)
        )
        val expanded = buildExpandedViews(
            service,
            title = service.getString(R.string.radar_notif_title_connecting),
            statusLine = service.getString(R.string.radar_notif_status_starting),
            profile = currentConfig?.remarks.orEmpty().ifBlank {
                service.getString(R.string.radar_notif_profile_unknown)
            },
            ping = "—",
            down = "—",
            up = "—",
            connecting = true
        )

        mBuilder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_radar)
            .setColor(0xFF00C853.toInt())
            .setContentTitle(service.getString(R.string.radar_notif_title_connecting))
            .setContentText(service.getString(R.string.radar_notif_subtitle_starting))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_stop_24dp,
                service.getString(R.string.notification_action_disconnect),
                stopXrayPendingIntent
            )
            .addAction(
                R.drawable.ic_restore_24dp,
                service.getString(R.string.notification_action_restart),
                restartXrayPendingIntent
            )

        service.startForeground(NOTIFICATION_ID, mBuilder?.build())
    }

    /** Call after the core loop reports a successful start. */
    fun updateRadarConnected() {
        val service = getService() ?: return
        val builder = mBuilder ?: return
        isConnectedState = true
        if (sessionStartElapsed == 0L) {
            sessionStartElapsed = SystemClock.elapsedRealtime()
        }
        applyConnectedViews(service, builder)
        getNotificationManager()?.notify(NOTIFICATION_ID, builder.build())
    }

    fun ensureForeground() {
        val service = getService() ?: return
        val notification = mBuilder?.build()
        if (notification == null) showNotification(null) else service.startForeground(NOTIFICATION_ID, notification)
    }

    fun cancelNotification() {
        val service = getService() ?: return
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        mBuilder = null
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        mNotificationManager = null
        isConnectedState = false
        sessionStartElapsed = 0L
        lastDownText = "—"
        lastUpText = "—"
    }

    fun stopSpeedNotification() {
        speedNotificationJob?.let {
            it.cancel()
            speedNotificationJob = null
            lastDownText = "—"
            lastUpText = "—"
            val service = getService()
            val builder = mBuilder
            if (service != null && builder != null && isConnectedState) {
                applyConnectedViews(service, builder)
                getNotificationManager()?.notify(NOTIFICATION_ID, builder.build())
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val channelName = AppConfig.RAY_NG_CHANNEL_NAME
        val manager = getNotificationManager()
        // Reset: drop legacy channel ids so users get a clean channel after redesign.
        manager?.deleteNotificationChannel("CORE_M_CH_ID")
        manager?.deleteNotificationChannel("CORE_M_CH_ID_V2")
        val chan = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live tunnel status, speeds, and quick controls"
            lightColor = Color.GREEN
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager?.createNotificationChannel(chan)
        return channelId
    }

    private fun applyConnectedViews(service: Service, builder: NotificationCompat.Builder) {
        val profile = resolveProfileName()
        val ping = resolvePingText()
        val title = service.getString(R.string.radar_notif_title_connected)
        val subtitle = buildCollapsedSubtitle(service, profile, ping)

        builder.setContentTitle(title)
        builder.setContentText(subtitle)
        builder.setColor(0xFF00C853.toInt())
        builder.setSmallIcon(R.drawable.ic_radar)
        builder.setUsesChronometer(true)
        builder.setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - sessionStartElapsed))

        builder.setCustomContentView(
            buildCollapsedViews(service, title, subtitle)
        )
        builder.setCustomBigContentView(
            buildExpandedViews(
                service = service,
                title = title,
                statusLine = service.getString(R.string.radar_notif_status_active),
                profile = profile,
                ping = ping,
                down = lastDownText,
                up = lastUpText,
                connecting = false
            )
        )
        builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
    }

    private fun buildCollapsedViews(service: Service, title: String, subtitle: String): RemoteViews {
        return RemoteViews(service.packageName, R.layout.notification_radar_collapsed).apply {
            setTextViewText(R.id.notif_title, title)
            setTextViewText(R.id.notif_subtitle, subtitle)
            setImageViewResource(R.id.notif_status_dot, R.drawable.ic_radar)
        }
    }

    private fun buildExpandedViews(
        service: Service,
        title: String,
        statusLine: String,
        profile: String,
        ping: String,
        down: String,
        up: String,
        connecting: Boolean
    ): RemoteViews {
        return RemoteViews(service.packageName, R.layout.notification_radar_expanded).apply {
            setTextViewText(R.id.notif_title, title)
            setTextViewText(R.id.notif_status_line, statusLine)
            setTextViewText(
                R.id.notif_profile,
                service.getString(R.string.radar_notif_profile_line, profile.ifBlank { "—" })
            )
            setTextViewText(R.id.notif_ping, ping)
            setTextViewText(R.id.notif_down, down)
            setTextViewText(R.id.notif_up, up)
            setImageViewResource(R.id.notif_status_dot, R.drawable.ic_radar)

            if (connecting) {
                setViewVisibility(R.id.notif_connecting_bar, android.view.View.VISIBLE)
                setViewVisibility(R.id.notif_session, android.view.View.GONE)
            } else {
                setViewVisibility(R.id.notif_connecting_bar, android.view.View.GONE)
                setViewVisibility(R.id.notif_session, android.view.View.VISIBLE)
                setChronometer(
                    R.id.notif_session,
                    sessionStartElapsed,
                    "⏱ %s",
                    true
                )
            }
        }
    }

    private fun updateNotification(contentText: String?, proxyTraffic: Long, directTraffic: Long) {
        val builder = mBuilder ?: return
        val service = getService() ?: return
        if (isConnectedState) {
            applyConnectedViews(service, builder)
        } else {
            builder.setCustomContentView(
                buildCollapsedViews(
                    service,
                    service.getString(R.string.radar_notif_title_connecting),
                    service.getString(R.string.radar_notif_subtitle_starting)
                )
            )
        }
        getNotificationManager()?.notify(NOTIFICATION_ID, builder.build())
    }

    private fun resolveProfileName(): String {
        val guid = MmkvManager.getSelectServer().orEmpty()
        if (guid.isBlank()) return ""
        return MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty()
    }

    private fun resolvePingText(): String {
        val guid = MmkvManager.getSelectServer().orEmpty()
        if (guid.isBlank()) return "—"
        val ping = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: return "—"
        return if (ping >= 0L) "${ping} ms" else "—"
    }

    private fun buildCollapsedSubtitle(service: Service, profile: String, ping: String): String {
        return when {
            profile.isNotBlank() && ping != "—" ->
                service.getString(R.string.radar_notif_subtitle_profile_ping, profile, ping)
            profile.isNotBlank() ->
                service.getString(R.string.radar_notif_subtitle_profile, profile)
            else -> service.getString(R.string.radar_notif_status_active)
        }
    }

    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = getService() ?: return null
            mNotificationManager =
                service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    private fun appendSpeedString(text: StringBuilder, name: String?, up: Double, down: Double) {
        var n = name ?: "no tag"
        n = n.take(min(n.length, 6))
        text.append(n)
        for (i in n.length..6 step 2) {
            text.append("\t")
        }
        text.append("•  ${up.toLong().toSpeedString()}↑  ${down.toLong().toSpeedString()}↓\n")
    }

    private fun updateSpeedNotificationOnce(lastZeroSpeed: Boolean): Boolean {
        val queryTime = System.currentTimeMillis()
        val sinceLastQueryIn = (queryTime - lastQueryTime)
        if (sinceLastQueryIn < QUERY_INTERVAL_MS) {
            LogUtil.w(AppConfig.TAG, "Query interval too short: ${sinceLastQueryIn}ms, skipping")
            lastQueryTime = queryTime
            return lastZeroSpeed
        }
        val sinceLastQueryInSeconds = sinceLastQueryIn / 1000.0

        var proxyUplink = 0L
        var proxyDownlink = 0L
        var directUplink = 0L
        var directDownlink = 0L

        CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
            when {
                stat.tag == AppConfig.TAG_DIRECT -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> directUplink += stat.value
                        AppConfig.DOWNLINK -> directDownlink += stat.value
                    }
                }
                stat.tag != AppConfig.TAG_BLOCKED -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> proxyUplink += stat.value
                        AppConfig.DOWNLINK -> proxyDownlink += stat.value
                    }
                }
            }
        }

        val proxyTotal = proxyUplink + proxyDownlink
        val directTotal = directUplink + directDownlink
        val zeroSpeed = proxyTotal + directTotal == 0L
        if (!zeroSpeed || !lastZeroSpeed) {
            val proxyUpRate = proxyUplink / sinceLastQueryInSeconds
            val proxyDownRate = proxyDownlink / sinceLastQueryInSeconds
            lastUpText = proxyUpRate.toLong().toSpeedString()
            lastDownText = proxyDownRate.toLong().toSpeedString()
            val text = StringBuilder()
            appendSpeedString(text, AppConfig.TAG_PROXY, proxyUpRate, proxyDownRate)
            appendSpeedString(
                text, AppConfig.TAG_DIRECT,
                directUplink / sinceLastQueryInSeconds,
                directDownlink / sinceLastQueryInSeconds
            )
            updateNotification(text.toString(), proxyTotal, directTotal)
        }
        lastQueryTime = queryTime
        return zeroSpeed
    }

    private fun getService(): Service? {
        return CoreServiceManager.serviceControl?.get()?.getService()
    }
}
