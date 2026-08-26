package com.aras.client.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.handler.MmkvManager

/**
 * First-run setup: a few small, useful toggles so the app behaves
 * the way the user likes from the very first launch.
 */
@Composable
fun FirstRunSetupDialog(onDismiss: () -> Unit) {
    var autoSort by remember {
        mutableStateOf(MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, true))
    }
    var autoConnect by remember {
        mutableStateOf(MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_CONNECT_FASTEST, false))
    }
    var showDelay by remember {
        mutableStateOf(MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_DELAY_CHIPS, true))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.setup_title))
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.setup_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SetupToggle(
                    label = stringResource(R.string.setup_auto_sort),
                    checked = autoSort,
                    onChange = {
                        autoSort = it
                        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_SORT_AFTER_TEST, it)
                    }
                )
                SetupToggle(
                    label = stringResource(R.string.setup_auto_connect),
                    checked = autoConnect,
                    onChange = {
                        autoConnect = it
                        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_CONNECT_FASTEST, it)
                    }
                )
                SetupToggle(
                    label = stringResource(R.string.setup_show_delay),
                    checked = showDelay,
                    onChange = {
                        showDelay = it
                        MmkvManager.encodeSettings(AppConfig.PREF_SHOW_DELAY_CHIPS, it)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    )
}

@Composable
private fun SetupToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
