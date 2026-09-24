package com.aras.client.ui

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.extension.toast
import com.aras.client.extension.toastError
import com.aras.client.handler.AngConfigManager
import com.aras.client.ui.base.BaseComponentActivity
import com.aras.client.ui.main.MainActivity
import com.aras.client.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.action == Intent.ACTION_VIEW &&
                intent.data?.host in setOf("install-config", "install-sub") ->
                intent.data?.getQueryParameter("url")
            else -> null
        }
        val fragment = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW }?.fragment

        if (sharedText.isNullOrEmpty()) {
            toastError(R.string.toast_failure)
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val decodedUrl = URLDecoder.decode(sharedText, "UTF-8").let { decoded ->
                    if (decoded.contains('#') || fragment.isNullOrEmpty()) decoded
                    else "$decoded#$fragment"
                }
                val result = AngConfigManager.importBatchConfig(decodedUrl, "", false)
                withContext(Dispatchers.Main) {
                    if (result.totalCount > 0) {
                        toast(R.string.import_subscription_success)
                    } else {
                        toast(R.string.import_subscription_failure)
                    }
                    startActivity(
                        Intent(this@UrlSchemeActivity, MainActivity::class.java).apply {
                            if (result.newSubscriptionIds.isNotEmpty()) {
                                action = com.aras.client.ui.main.SubscriptionNavigation.ACTION_OPEN_SUBSCRIPTION
                                putStringArrayListExtra(
                                    com.aras.client.ui.main.SubscriptionNavigation.EXTRA_SUBSCRIPTION_IDS,
                                    ArrayList(result.newSubscriptionIds),
                                )
                            }
                        }
                    )
                    finish()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Error processing shared subscription", e)
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    finish()
                }
            }
        }
    }

    @Composable
    override fun ScreenContent() {
    }

}
