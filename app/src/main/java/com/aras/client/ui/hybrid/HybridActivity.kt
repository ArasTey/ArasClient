package com.aras.client.ui.hybrid

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.core.HybridConnectManager
import com.aras.client.core.LauncherManager
import com.aras.client.handler.SettingsManager
import com.aras.client.ui.base.BaseComponentActivity
import com.aras.client.util.Utils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HybridActivity : BaseComponentActivity() {
    private var startAfterPermission: (() -> Unit)? = null

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent ?: return
            when (message.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    statusResource = R.string.hybrid_status_connected
                    statusMessage = null
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    val failure = message.getStringExtra("content")
                        ?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.hybrid_start_failed)
                    statusResource = R.string.hybrid_status_stopping
                    statusMessage = null
                    HybridConnectManager.stop(this@HybridActivity) {
                        statusResource = null
                        statusMessage = failure
                    }
                }
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    if (HybridConnectManager.hasSession()) {
                        statusResource = R.string.hybrid_status_idle
                    }
                }
            }
        }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startAfterPermission?.invoke()
            } else {
                startAfterPermission = null
                statusResource = R.string.hybrid_status_stopping
                val failure = getString(R.string.hybrid_permission_denied)
                statusMessage = null
                HybridConnectManager.stop(this) {
                    statusResource = null
                    statusMessage = failure
                }
            }
        }

    private var receiverRegistered = false
    private var statusResource by mutableStateOf<Int?>(R.string.hybrid_status_idle)
    private var statusMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            serviceStateReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags()
        )
        receiverRegistered = true
    }

    @Composable
    override fun ScreenContent() {
        HybridScreen(
            statusResource = statusResource,
            statusMessage = statusMessage,
            onBack = { leaveHybrid() },
            onConnect = { rawConfig -> connectHybrid(rawConfig) },
            onDisconnect = { leaveHybrid() },
        )
    }

    private fun connectHybrid(rawConfig: String) {
        val error = HybridConnectManager.prepare(this, rawConfig)
        if (error != null) {
            statusMessage = error
            statusResource = null
            return
        }

        statusResource = R.string.hybrid_status_starting
        statusMessage = null

        // Stop any previously selected tunnel before launching the temporary chain.
        LauncherManager.stopService(this)
        lifecycleScope.launch {
            delay(1200)
            val start = { startPreparedHybrid() }
            if (SettingsManager.isVpnMode()) {
                val permissionIntent = VpnService.prepare(this@HybridActivity)
                if (permissionIntent != null) {
                    startAfterPermission = start
                    vpnPermissionLauncher.launch(permissionIntent)
                } else {
                    start()
                }
            } else {
                start()
            }
        }
    }

    private fun startPreparedHybrid() {
        startAfterPermission = null
        if (HybridConnectManager.start(this)) {
            statusResource = R.string.hybrid_status_request_sent
            statusMessage = null
        } else {
            statusResource = R.string.hybrid_status_stopping
            val failure = getString(R.string.hybrid_start_failed)
            statusMessage = null
            HybridConnectManager.stop(this) {
                statusResource = null
                statusMessage = failure
            }
        }
    }

    private fun leaveHybrid() {
        if (!HybridConnectManager.hasSession()) {
            finish()
            return
        }
        statusResource = R.string.hybrid_status_stopping
        statusMessage = null
        HybridConnectManager.stop(this) {
            finish()
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(serviceStateReceiver) }
            receiverRegistered = false
        }
        if (isFinishing && HybridConnectManager.hasSession()) {
            HybridConnectManager.stop(this)
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HybridScreen(
    statusResource: Int?,
    statusMessage: String?,
    onBack: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    var configText by rememberSaveable { mutableStateOf("") }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hybrid_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.hybrid_status_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.hybrid_route_badge),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Text(
                        text = stringResource(R.string.hybrid_route_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = statusMessage
                            ?: statusResource?.let { stringResource(id = it) }
                            ?: stringResource(R.string.hybrid_status_idle),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (statusMessage != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.hybrid_config_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = configText,
                    onValueChange = { configText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 9,
                    label = { Text(stringResource(R.string.hybrid_config_hint)) },
                    placeholder = { Text("vless://…  ·  vmess://…  ·  trojan://…") },
                    shape = RoundedCornerShape(18.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            configText = clipboard.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                .orEmpty()
                        }
                    ) { Text(stringResource(R.string.hybrid_paste)) }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { configText = "" }
                    ) { Text(stringResource(R.string.hybrid_clear)) }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.hybrid_how_it_works),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.hybrid_route_steps),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = {
                    onConnect(configText)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = configText.isNotBlank() && statusResource !in setOf<Int?>(
                    R.string.hybrid_status_starting,
                    R.string.hybrid_status_request_sent,
                    R.string.hybrid_status_connected,
                    R.string.hybrid_status_stopping,
                )
            ) {
                if (statusResource == R.string.hybrid_status_starting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(stringResource(R.string.hybrid_connect))
            }

            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.hybrid_stop_restore))
            }

            Text(
                stringResource(R.string.hybrid_cleanup_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
