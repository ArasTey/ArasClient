package com.aras.client.ui.shortcut

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import com.aras.client.R
import com.aras.client.extension.toastError
import com.aras.client.extension.toastSuccess
import com.aras.client.handler.AngConfigManager
import com.aras.client.ui.base.HelperBaseComponentActivity
import com.aras.client.ui.main.MainActivity
import com.aras.client.ui.main.SubscriptionNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScScannerActivity : HelperBaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        LaunchedEffect(Unit) {
            importQRcode()
        }
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult == null) {
                finish()
                return@launchQRCodeScanner
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val result = AngConfigManager.importBatchConfig(scanResult, "", false)
                withContext(Dispatchers.Main) {
                    if (result.totalCount > 0) {
                        toastSuccess(R.string.toast_success)
                    } else {
                        toastError(R.string.toast_failure)
                    }
                    startActivity(
                        Intent(this@ScScannerActivity, MainActivity::class.java).apply {
                            if (result.newSubscriptionIds.isNotEmpty()) {
                                action = SubscriptionNavigation.ACTION_OPEN_SUBSCRIPTION
                                putStringArrayListExtra(
                                    SubscriptionNavigation.EXTRA_SUBSCRIPTION_IDS,
                                    ArrayList(result.newSubscriptionIds),
                                )
                            }
                        }
                    )
                    finish()
                }
            }
        }
    }
}