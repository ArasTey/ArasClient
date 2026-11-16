package com.aras.client.core

import android.content.Context
import android.content.Intent
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.handler.MmkvManager
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils

/**
 * The platform half of an IKEv2 session. The tunnel itself is built by Android's own
 * IKE engine ([Ikev2VpnProfile]); this object only provisions it, starts and stops it,
 * and keeps the session flags the tiles and the UI read in step with it.
 */
object IkeV2Manager {

    /** The platform IKE session is live — true also after the app process was replaced. */
    fun isActive(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_IKEV2_SESSION_RUNNING, false)

    private fun markSession(running: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_IKEV2_SESSION_RUNNING, running)
        // One flag drives both the tiles and the UI; a session of either kind clears it.
        MmkvManager.encodeSettings(AppConfig.PREF_SESSION_RUNNING, running)
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * The identity the profile presents to the gateway: the dedicated local id, or the
     * username when the profile has no id of its own. The gateway address is the last
     * resort for setups that authenticate by address.
     */
    private fun localIdentity(config: ProfileItem): String =
        config.ikev2LocalId?.takeIf { it.isNotBlank() }
            ?: config.username?.takeIf { it.isNotBlank() }
            ?: config.server.orEmpty().ifBlank { AppConfig.ANG_PACKAGE }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun buildProfile(config: ProfileItem): Ikev2VpnProfile {
        val builder = Ikev2VpnProfile.Builder(config.server.orEmpty(), localIdentity(config))
        val username = config.username?.takeIf { it.isNotBlank() }
        if (username != null) {
            builder.setAuthUsernamePassword(username, config.password.orEmpty(), null)
        }
        return builder.build()
    }

    /**
     * The consent intent when the profile has never been approved, or null when the
     * platform VPN can be started straight away.
     */
    fun provisionIntent(context: Context, config: ProfileItem): Intent? {
        if (!isSupported()) return null
        return try {
            val vpnManager = context.getSystemService(VpnManager::class.java)
            vpnManager?.provisionVpnProfile(buildProfile(config))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "IkeV2: Failed to provision profile", e)
            null
        }
    }

    /**
     * Starts the platform IKE session for a validated IKEv2 profile. Returns a user
     * readable error, or null on success.
     */
    fun start(context: Context, config: ProfileItem): String? {
        if (!isSupported()) return context.getString(R.string.toast_ikev2_requires_android10)
        return try {
            val vpnManager = context.getSystemService(VpnManager::class.java)
                ?: return context.getString(R.string.toast_failure)
            vpnManager.startProvisionedVpnProfileSession()
            markSession(true)
            LogUtil.i(AppConfig.TAG, "IkeV2: Platform session started for ${describe(config)}")
            null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "IkeV2: Failed to start platform VPN", e)
            e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }

    /** Ends the platform IKE session, if one was started for this app. */
    fun stop(context: Context) {
        if (!isSupported()) return
        try {
            context.getSystemService(VpnManager::class.java)?.stopProvisionedVpnProfile()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "IkeV2: Failed to stop platform VPN", e)
        }
        if (isActive()) markSession(false)
    }

    /** The gateway address with its port, for logs and diagnostics. */
    fun describe(config: ProfileItem): String =
        "${Utils.getIpv6Address(config.server)}:${config.serverPort.orEmpty()}"
}
