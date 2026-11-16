package com.aras.client.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.core.IkeV2Manager
import com.aras.client.enums.EConfigType
import com.aras.client.handler.AppLocaleManager
import com.aras.client.handler.MmkvManager
import com.aras.client.helper.MessageHelper
import com.aras.client.util.LogUtil
import com.aras.client.ui.main.MainActivity
import com.aras.client.util.Utils

/**
 * Runs the platform IKEv2 session for the selected IKEv2 profile.
 *
 * The tunnel belongs to Android's own IKE engine; this service holds only the
 * foreground notification, the service message contract every other part of the app
 * speaks (start/stop/register), and the session flags. It must never run while the
 * Xray VPN service is up: both own the device's single VPN.
 */
class IkeV2Service : Service() {

    private var receiverRegistered = false

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerMessageReceiver()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                unregisterReceiver(mMsgReceive)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "IkeV2: Failed to unregister receiver", e)
            }
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when {
            intent?.action == android.net.VpnManager.ACTION_VPN_MANAGER_EVENT -> {
                handleVpnManagerEvent(intent)
            }
            // An OS restart delivers a null intent; re-attach to the session the flags
            // say is still up instead of tearing it down.
            intent == null -> startSession()
            intent.action == AppConfig.ACTION_IKEV2_SERVICE_START -> {
                val key = intent.getIntExtra("key", 0)
                if (key == AppConfig.MSG_STATE_STOP || key == AppConfig.MSG_STATE_RESTART) {
                    stopSession()
                } else {
                    startSession()
                }
            }
            // Exported only because the platform's event delivery needs it; no other
            // app is entitled to raise or drop the user's VPN tunnel.
            else -> LogUtil.w(AppConfig.TAG, "IkeV2: Ignored foreign intent action ${intent.action}")
        }
        return START_STICKY
    }

    /**
     * The platform IKE engine reports session losses here — fatal errors (rejected
     * auth, unreachable server) never come back as a callback, so without this the
     * UI would keep saying "Connected" while no tunnel exists.
     */
    private fun handleVpnManagerEvent(intent: Intent) {
        val category = intent.categories?.firstOrNull()
        val errorClass = intent.getIntExtra(
            android.net.VpnManager.EXTRA_ERROR_CLASS,
            android.net.VpnManager.ERROR_CLASS_RECOVERABLE,
        )
        val errorCode = intent.getIntExtra(android.net.VpnManager.EXTRA_ERROR_CODE, 0)
        LogUtil.i(
            AppConfig.TAG,
            "IkeV2: VPN manager event cat=$category errorClass=$errorClass errorCode=$errorCode",
        )
        val fatal = when (category) {
            // Starting a session while the previous runner is still being set up makes
            // the platform report a deactivation that is not a real user action; the
            // platform's own session state, not the event, says whether the tunnel died.
            android.net.VpnManager.CATEGORY_EVENT_DEACTIVATED_BY_USER -> platformSessionDown()
            android.net.VpnManager.CATEGORY_EVENT_IKE_ERROR ->
                errorClass == android.net.VpnManager.ERROR_CLASS_NOT_RECOVERABLE
            else -> false
        }
        if (fatal) {
            stopSession()
        }
    }

    /** The platform's view of the provisioned session, or null below API 33. */
    private fun platformSessionState(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(android.net.VpnManager::class.java)?.provisionedVpnProfileState?.state
        } else {
            null
        }

    private fun platformSessionUp(): Boolean {
        val state = platformSessionState() ?: return false
        return state == android.net.VpnProfileState.STATE_CONNECTED ||
            state == android.net.VpnProfileState.STATE_CONNECTING
    }

    private fun platformSessionDown(): Boolean = !platformSessionUp()

    private fun startSession() {
        val guid = MmkvManager.getSelectServer()
        val config = guid?.let { MmkvManager.decodeServerConfig(it) }
        if (config == null || config.configType != EConfigType.IKEV2) {
            LogUtil.e(AppConfig.TAG, "IkeV2: Selected profile is not an IKEv2 config")
            stopSession()
            return
        }

        // A second start while the platform session is already up or still coming up
        // would tear down the live runner and rebuild it — which the platform reports
        // as a user deactivation. Re-attach to the running session instead. The flag
        // keeps a start right after our own stop (restart flow) going through.
        if (IkeV2Manager.isActive() && platformSessionUp()) {
            LogUtil.i(AppConfig.TAG, "IkeV2: Session already active; re-attaching")
            showNotification(config.remarks)
            notifyState(AppConfig.MSG_STATE_RUNNING)
            return
        }

        val error = IkeV2Manager.start(this, config)
        if (error != null) {
            LogUtil.e(AppConfig.TAG, "IkeV2: Start failed: $error")
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifyState(AppConfig.MSG_STATE_START_FAILURE, error)
            stopSelf()
            return
        }

        showNotification(config.remarks)
        notifyState(AppConfig.MSG_STATE_START_SUCCESS)
        notifyState(AppConfig.MSG_STATE_RUNNING)
    }

    private fun stopSession() {
        IkeV2Manager.stop(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyState(AppConfig.MSG_STATE_STOP_SUCCESS)
        stopSelf()
    }

    /** The tiles read the same broadcasts the UI does, so both are told together. */
    private fun notifyState(what: Int, content: String = "") {
        MessageHelper.sendMsg2UI(this, what, content)
        QSTileService.requestStateRefresh(this)
    }

    private fun ensureForeground() {
        runCatching { startForeground(NOTIFICATION_ID, SessionNotification.pending(this)) }
    }

    private fun showNotification(remarks: String?) {
        runCatching { startForeground(NOTIFICATION_ID, SessionNotification.builder(this, remarks).build()) }
            .onFailure { LogUtil.e(AppConfig.TAG, "IkeV2: Failed to show notification", it) }
    }

    private fun registerMessageReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        ContextCompat.registerReceiver(this, mMsgReceive, filter, Utils.receiverFlags())
        receiverRegistered = true
    }

    private val mMsgReceive = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_STOP -> stopSession()

                AppConfig.MSG_STATE_RESTART -> {
                    // The platform session cannot reload in place; end it and let the
                    // caller's start flow bring it back up.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    stopSession()
                }

                AppConfig.MSG_REGISTER_CLIENT -> {
                    val what = if (IkeV2Manager.isActive()) {
                        AppConfig.MSG_STATE_RUNNING
                    } else {
                        AppConfig.MSG_STATE_NOT_RUNNING
                    }
                    MessageHelper.sendMsg2UI(this@IkeV2Service, what, "")
                }
            }
        }
    }

    /** A minimal session notification of its own: the shared one is bound to the Xray service. */
    private object SessionNotification {
        private const val CHANNEL_ID = "ikev2_session"

        fun builder(context: Context, title: String?): NotificationCompat.Builder {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                    manager?.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            context.getString(R.string.app_name),
                            android.app.NotificationManager.IMPORTANCE_LOW,
                        )
                    )
                }
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title ?: context.getString(R.string.app_name))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
        }

        fun pending(context: Context): Notification = builder(context, null).build()
    }

    private companion object {
        const val NOTIFICATION_ID = 2
    }
}
