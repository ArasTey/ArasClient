package com.aras.client.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.core.CoreServiceManager
import com.aras.client.core.LauncherManager
import com.aras.client.handler.AppLocaleManager
import com.aras.client.handler.MmkvManager
import com.aras.client.helper.MessageHelper
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils
import java.lang.ref.SoftReference

class QSTileService : TileService() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    /**
     * Sets the state of the tile.
     * @param state The state to set.
     */
    fun setState(state: Int) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_stat_name)
        tile.state = state
        tile.label = if (state == Tile.STATE_ACTIVE) {
            CoreServiceManager.getRunningServerName().ifBlank {
                MmkvManager.getSelectServer()
                    ?.let { MmkvManager.decodeServerConfig(it)?.remarks }
                    .orEmpty()
            }.ifBlank { getString(R.string.app_name) }
        } else {
            ""
        }
        tile.updateTile()
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    private fun ensureReceiverRegistered() {
        if (receiverRegistered) return
        mMsgReceive = ReceiveMessageHandler(this)
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(applicationContext, mMsgReceive, mFilter, Utils.receiverFlags())
        receiverRegistered = true
    }

    override fun onStartListening() {
        super.onStartListening()

        setState(
            if (CoreServiceManager.isRunning()) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
        )
        ensureReceiverRegistered()
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Called when the tile stops listening.
     */
    override fun onStopListening() {
        if (receiverRegistered) {
            try {
                applicationContext.unregisterReceiver(mMsgReceive)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to unregister receiver", e)
            }
            mMsgReceive = null
            receiverRegistered = false
        }
        super.onStopListening()
    }

    /**
     * Called when the tile is clicked.
     */
    override fun onClick() {
        super.onClick()
        ensureReceiverRegistered()
        if (CoreServiceManager.isRunning()) {
            setState(Tile.STATE_INACTIVE)
            LauncherManager.stopService(this)
        } else if (LauncherManager.startServiceFromToggle(this)) {
            setState(Tile.STATE_ACTIVE)
        } else {
            setState(Tile.STATE_INACTIVE)
        }
    }

    private var receiverRegistered = false
    private var mMsgReceive: BroadcastReceiver? = null

    private class ReceiveMessageHandler(context: QSTileService) : BroadcastReceiver() {
        var mReference: SoftReference<QSTileService> = SoftReference(context)
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val context = mReference.get()
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    context?.setState(Tile.STATE_ACTIVE)
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    context?.setState(Tile.STATE_INACTIVE)
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    context?.setState(Tile.STATE_ACTIVE)
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    context?.setState(Tile.STATE_INACTIVE)
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    context?.setState(Tile.STATE_INACTIVE)
                }
            }
        }
    }

    companion object {
        fun requestStateRefresh(context: Context) {
            TileService.requestListeningState(
                context,
                ComponentName(context, QSTileService::class.java),
            )
        }
    }
}
