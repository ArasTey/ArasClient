package com.aras.client.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.enums.EConfigType
import com.aras.client.extension.isComplexType
import com.aras.client.extension.toast
import com.aras.client.extension.toastError
import com.aras.client.handler.MmkvManager
import com.aras.client.handler.SettingsManager
import com.aras.client.helper.MessageHelper
import com.aras.client.root.RootManager
import com.aras.client.service.CoreProxyOnlyService
import com.aras.client.service.CoreRootService
import com.aras.client.service.CoreVpnService
import com.aras.client.service.IkeV2Service
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils

object LauncherManager {

    fun startServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            return false
        }
        return true
    }

    fun startService(context: Context, guid: String? = null): Boolean {
        LogUtil.i(AppConfig.TAG, "LauncherManager: startService from ${context::class.java.simpleName}")

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        return try {
            startContextService(context)
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    fun stopService(context: Context) {
        //context.toast(R.string.toast_services_stop)
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /** Restarts the active daemon without starting a stopped service. */
    fun restartService(context: Context) {
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
    }

    /** Restarts the active daemon, or delegates to the caller's permission-aware start flow. */
    fun restartServiceOrStart(context: Context, startIfStopped: () -> Unit) {
        MessageHelper.sendMsg2ServiceForResult(context, AppConfig.MSG_STATE_RESTART, "") { handled ->
            if (!handled) startIfStopped()
        }
    }

    @Throws(Exception::class)
    /**
     * Starts the platform IKEv2 session. Consent (first use) has to be shown from the
     * foreground app: from an activity the consent screen opens directly, from a tile
     * or widget the user is asked to connect once from the app.
     */
    private fun startIkeV2(context: Context) {
        if (!IkeV2Manager.isSupported()) {
            error(context.getString(R.string.toast_ikev2_requires_android10))
        }
        if (!SettingsManager.isVpnMode()) {
            error(context.getString(R.string.toast_ikev2_vpn_mode_required))
        }
        val guid = MmkvManager.getSelectServer()
        val config = guid?.let { MmkvManager.decodeServerConfig(it) }
            ?: error(context.getString(R.string.toast_config_file_invalid))
        val consent = IkeV2Manager.provisionIntent(context, config)
        if (consent != null) {
            if (context is Activity) {
                context.startActivity(consent)
            } else {
                context.toast(R.string.toast_ikev2_consent_needed)
            }
            return
        }
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context.applicationContext, IkeV2Service::class.java)
                    .setAction(AppConfig.ACTION_IKEV2_SERVICE_START)
            )
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Missing permission to start IKEv2 service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun startContextService(context: Context) {
        // Note: isRunning check is removed here to avoid loading Native libraries in the UI process.
        // The check is performed in CoreServiceManager when the service starts in the daemon process.

        val guid = MmkvManager.getSelectServer()
            ?: run {
                LogUtil.e(AppConfig.TAG, "LauncherManager: No server selected")
                error(context.getString(R.string.app_tile_first_use))
            }

        val config = MmkvManager.decodeServerConfig(guid)
            ?: run {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Failed to decode server config")
                error(context.getString(R.string.toast_config_file_invalid))
            }

        if (!config.configType.isComplexType()
            && !config.configType.isCarrierOnly
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Invalid server configuration")
            error(context.getString(R.string.toast_config_file_invalid))
        }

        SettingsManager.refreshRuntimeSocksPort()

        if (config.insecure == true && config.pinnedCA256.isNullOrEmpty()) {
            context.toastError(R.string.toast_allow_insecure_deprecated)
            Utils.setClipboard(context, context.getString(R.string.toast_allow_insecure_deprecated))
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isRootMode = SettingsManager.isRootMode()
        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_required))
        }

        // IKEv2 runs on the platform's own engine in VPN mode; the core service and its
        // intents do not apply to it at all.
        if (config.configType == EConfigType.IKEV2) {
            startIkeV2(context)
            return
        }

        val intent = if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }
}
