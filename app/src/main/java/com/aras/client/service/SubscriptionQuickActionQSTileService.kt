package com.aras.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.QuickConnectFailure
import com.aras.client.dto.QuickConnectMessage
import com.aras.client.dto.QuickConnectResult
import com.aras.client.extension.serializable
import com.aras.client.handler.AppLocaleManager
import com.aras.client.handler.MmkvManager
import com.aras.client.helper.MessageHelper
import com.aras.client.util.Utils

/** Quick Settings action: update, ping, sort, and connect the selected subscription. */
class SubscriptionQuickActionQSTileService : TileService() {
    private var receiverRegistered = false
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_QUICK_CONNECT_PROGRESS -> {
                    if (QuickConnectCoordinator.isRunning()) renderRunning()
                }
                AppConfig.MSG_QUICK_CONNECT_SUCCESS -> renderReady()
                AppConfig.MSG_QUICK_CONNECT_FAILURE -> {
                    val result = intent.serializable<QuickConnectResult>("content")
                    renderReady(result?.reason)
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                applicationContext,
                stateReceiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
                Utils.receiverFlags(),
            )
            receiverRegistered = true
        }
        if (QuickConnectCoordinator.isRunning()) renderRunning() else renderReady()
    }

    override fun onStopListening() {
        if (receiverRegistered) {
            runCatching { applicationContext.unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (QuickConnectCoordinator.isRunning()) return
        val subId = resolveSubscriptionId()
        if (subId.isBlank()) {
            renderUnavailable()
            return
        }
        if (!QuickConnectCoordinator.tryStart()) return
        if (!MessageHelper.sendMsg2QuickConnectService(this, QuickConnectMessage(subId))) {
            QuickConnectCoordinator.finish()
            renderReady(QuickConnectFailure.UPDATE_FAILED)
            return
        }
        renderRunning()
    }

    private fun renderRunning() {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_stat_name)
        tile.state = Tile.STATE_ACTIVE
        tile.label = getString(R.string.quick_connect_tile_running)
        setSubtitle(tile, subscriptionRemarks())
        tile.updateTile()
    }

    private fun renderReady(failure: QuickConnectFailure? = null) {
        val tile = qsTile ?: return
        val subId = resolveSubscriptionId()
        if (subId.isBlank()) {
            renderUnavailable()
            return
        }
        tile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_stat_name)
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_quick_connect_tile_name)
        setSubtitle(tile, failure?.let(::failureLabel) ?: subscriptionRemarks())
        tile.updateTile()
    }

    private fun renderUnavailable() {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_stat_name)
        tile.state = Tile.STATE_UNAVAILABLE
        tile.label = getString(R.string.app_quick_connect_tile_name)
        setSubtitle(tile, getString(R.string.toast_select_subscription_for_ping))
        tile.updateTile()
    }

    private fun resolveSubscriptionId(): String {
        val selectedGroup = MmkvManager
            .decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID)
            .orEmpty()
        if (selectedGroup.isNotBlank() && MmkvManager.decodeSubscription(selectedGroup) != null) {
            return selectedGroup
        }
        val selectedProfile = MmkvManager.getSelectServer()
            ?.let { MmkvManager.decodeServerConfig(it) }
            ?: return ""
        val profileGroup = selectedProfile.subscriptionId
            .ifBlank { AppConfig.DEFAULT_SUBSCRIPTION_ID }
        return if (MmkvManager.decodeSubscription(profileGroup) != null) profileGroup else ""
    }

    private fun subscriptionRemarks(): String {
        val subId = resolveSubscriptionId()
        return MmkvManager.decodeSubscription(subId)?.remarks
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.title_sub_setting)
    }

    private fun failureLabel(failure: QuickConnectFailure): String = when (failure) {
        QuickConnectFailure.NO_SUBSCRIPTION,
        QuickConnectFailure.NO_SERVERS -> getString(R.string.quick_connect_no_server)
        QuickConnectFailure.VPN_PERMISSION_REQUIRED -> getString(R.string.quick_connect_vpn_permission)
        QuickConnectFailure.ALREADY_RUNNING -> getString(R.string.quick_connect_already_running)
        else -> getString(R.string.quick_connect_failure)
    }

    private fun setSubtitle(tile: Tile, value: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = value
    }
}
