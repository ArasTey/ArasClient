package com.aras.client.ui.shortcut

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.aras.client.AppConfig
import com.aras.client.dto.QuickConnectMessage
import com.aras.client.handler.MmkvManager
import com.aras.client.helper.MessageHelper
import com.aras.client.service.QuickConnectCoordinator
import com.aras.client.ui.base.BaseComponentActivity

/** Launcher-icon fallback for devices where a third-party QS tile is not added. */
class ScQuickConnectActivity : BaseComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        LaunchedEffect(Unit) {
            moveTaskToBack(true)
            val selectedGroup = MmkvManager
                .decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID)
                .orEmpty()
            val subId = selectedGroup.takeIf { MmkvManager.decodeSubscription(it) != null }
                ?: MmkvManager.getSelectServer()
                    ?.let { MmkvManager.decodeServerConfig(it) }
                    ?.subscriptionId
                    ?.ifBlank { AppConfig.DEFAULT_SUBSCRIPTION_ID }
                    ?.takeIf { MmkvManager.decodeSubscription(it) != null }
            if (subId != null && QuickConnectCoordinator.tryStart()) {
                if (!MessageHelper.sendMsg2QuickConnectService(
                        this@ScQuickConnectActivity,
                        QuickConnectMessage(subId),
                    )
                ) {
                    QuickConnectCoordinator.finish()
                }
            }
            finish()
        }
    }
}
