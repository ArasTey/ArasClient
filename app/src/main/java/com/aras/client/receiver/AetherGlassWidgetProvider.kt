package com.aras.client.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.core.CoreServiceManager
import com.aras.client.core.LauncherManager
import com.aras.client.handler.MmkvManager
import com.aras.client.ui.main.MainActivity

/**
 * AETHER GLASS is a separate home-screen widget. The legacy WidgetProvider
 * and its layout are intentionally left untouched.
 *
 * Status is read from the app's existing service/profile managers; this
 * widget never invents ping or throughput values.
 */
class AetherGlassWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { render(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_TOGGLE -> {
                try {
                    when {
                        CoreServiceManager.isRunning() ->
                            LauncherManager.stopService(context)
                        !MmkvManager.getSelectServer().isNullOrBlank() ->
                            LauncherManager.startServiceFromToggle(context)
                        else ->
                            context.startActivity(
                                Intent(context, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                    }
                } finally {
                    refreshAll(context)
                }
            }

            ACTION_REFRESH,
            AppConfig.BROADCAST_ACTION_ACTIVITY -> refreshAll(context)
        }
    }

    private fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, AetherGlassWidgetProvider::class.java)
        manager.getAppWidgetIds(component).forEach { render(context, manager, it) }
    }

    private fun render(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ) {
        val running = try {
            CoreServiceManager.isRunning()
        } catch (_: Exception) {
            false
        }
        val selectedGuid = try {
            MmkvManager.getSelectServer()
        } catch (_: Exception) {
            null
        }
        val selectedProfile = try {
            selectedGuid?.let { MmkvManager.decodeServerConfig(it) }
        } catch (_: Exception) {
            null
        }
        val hasProfile = !selectedGuid.isNullOrBlank()
        val profileName = selectedProfile?.remarks?.takeIf { it.isNotBlank() }
            ?: if (hasProfile) "SELECTED PROFILE" else "NO ACTIVE PROFILE"
        val lastPing = try {
            selectedGuid?.let { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis }
        } catch (_: Exception) {
            null
        }

        val views = RemoteViews(context.packageName, R.layout.widget_aether_glass)

        views.setTextViewText(R.id.aether_brand, "Radar VPN · Glass")
        views.setTextViewText(
            R.id.aether_status,
            when {
                running -> "VPN SERVICE RUNNING"
                hasProfile -> "SYSTEM STANDBY"
                else -> "NO PROFILE SELECTED"
            },
        )
        views.setTextViewText(
            R.id.aether_status_detail,
            when {
                running -> "VPN service is running"
                hasProfile -> "Ready to establish tunnel"
                else -> "Open Radar VPN to add a profile"
            },
        )
        views.setTextViewText(
            R.id.aether_ping,
            when {
                lastPing == null || lastPing == 0L -> "— ms"
                lastPing < 0L -> "TIMEOUT"
                else -> "${lastPing} ms"
            },
        )
        views.setTextViewText(R.id.aether_node, profileName)
        views.setTextViewText(
            R.id.aether_action,
            when {
                running -> "DISCONNECT VPN"
                hasProfile -> "CONNECT VPN"
                else -> "Open Radar VPN"
            },
        )

        val accent = if (running) 0xFF55FFAA.toInt() else 0xFFFF6475.toInt()
        views.setTextColor(R.id.aether_status, accent)
        views.setTextColor(
            R.id.aether_ping,
            if (running) 0xFF55FFAA.toInt() else 0xFFB5C0BC.toInt(),
        )
        views.setImageViewResource(
            R.id.aether_status_dot,
            if (running) R.drawable.aether_glass_dot_green else R.drawable.aether_glass_dot_muted,
        )
        views.setInt(
            R.id.aether_radar,
            "setImageResource",
            if (running) R.drawable.aether_glass_radar_active
            else R.drawable.aether_glass_radar_idle,
        )
        views.setInt(
            R.id.aether_action,
            "setBackgroundResource",
            if (running) R.drawable.aether_glass_action_connected
            else R.drawable.aether_glass_action_idle,
        )

        val toggleIntent = Intent(context, AetherGlassWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context,
            widgetId,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.aether_action, togglePendingIntent)

        val refreshIntent = Intent(context, AetherGlassWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            widgetId + REFRESH_REQUEST_OFFSET,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.aether_refresh, refreshPendingIntent)

        manager.updateAppWidget(widgetId, views)
    }

    companion object {
        const val ACTION_TOGGLE = "com.aras.client.action.AETHER_WIDGET_TOGGLE"
        const val ACTION_REFRESH = "com.aras.client.action.AETHER_WIDGET_REFRESH"
        private const val REFRESH_REQUEST_OFFSET = 20000
    }
}
