package com.aras.client.ui.repair

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aras.client.R
import com.aras.client.extension.toastError
import com.aras.client.extension.toastSuccess
import com.aras.client.handler.AngConfigManager
import com.aras.client.repair.ConfigRepairEngine
import com.aras.client.repair.ConfigRepairResult
import com.aras.client.repair.RepairedConfig
import com.aras.client.ui.compose.AppTheme
import com.aras.client.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class ConfigRepairActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val scope = rememberCoroutineScope()
                var input by remember {
                    mutableStateOf(Utils.getClipboard(this@ConfigRepairActivity))
                }
                var result by remember { mutableStateOf<ConfigRepairResult?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }

                Scaffold(topBar = { TopAppBar(title = { Text("تعمیرگاه کانفیگ") }) }) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "لینک خراب یا پینگ بالا را بچسبان. تعمیر فقط روی خود دستگاه انجام می‌شود " +
                                "(نرمال‌سازی fp / encryption / flow و واریانت CDN یا IP). " +
                                "UUID یا کلید Reality اشتباه را نمی‌توان حدس زد.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 8,
                            label = { Text("لینک کانفیگ") },
                            placeholder = { Text("vless://… یا trojan://…") }
                        )
                        Button(
                            enabled = !busy && input.isNotBlank(),
                            onClick = {
                                error = null
                                result = null
                                busy = true
                                scope.launch {
                                    try {
                                        result = withContext(Dispatchers.Default) {
                                            ConfigRepairEngine.repair(input)
                                        }
                                    } catch (e: Exception) {
                                        error = e.message ?: "تعمیر ناموفق"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "در حال تعمیر…" else "شروع تعمیر") }

                        OutlinedButton(
                            onClick = { input = Utils.getClipboard(this@ConfigRepairActivity) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("چسباندن از کلیپ‌بورد") }

                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                        result?.let { r ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${r.protocol.uppercase()} · ${r.host}:${r.port}", style = MaterialTheme.typography.titleSmall)
                                    r.warnings.forEach { w -> Text("• $w", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                            r.variants.forEach { v ->
                                VariantCard(
                                    variant = v,
                                    onCopy = {
                                        Utils.setClipboard(this@ConfigRepairActivity, v.uri)
                                        toastSuccess(R.string.toast_success)
                                    },
                                    onImport = {
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                AngConfigManager.importBatchConfig(v.uri, "", false)
                                            }
                                            if (ok.configCount > 0) toastSuccess(R.string.toast_success)
                                            else toastError(R.string.toast_failure)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VariantCard(variant: RepairedConfig, onCopy: () -> Unit, onImport: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(variant.label, style = MaterialTheme.typography.titleSmall)
            if (variant.changes.isEmpty()) {
                Text("بدون تغییر معنادار", style = MaterialTheme.typography.bodySmall)
            } else {
                variant.changes.take(8).forEach { c ->
                    Text("${c.field}: ${c.before} → ${c.after}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                variant.uri.take(120) + if (variant.uri.length > 120) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport) { Text("افزودن") }
                OutlinedButton(onClick = onCopy) { Text("کپی") }
            }
        }
    }
}
