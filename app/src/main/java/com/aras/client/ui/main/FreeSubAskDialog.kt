package com.aras.client.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aras.client.R

/**
 * The one-time question about the Free subscription: shown until answered,
 * even for users whose onboarding happened before the question existed.
 * The choice is display-only — show or hide the Free group tab; the group
 * itself keeps syncing either way, and the toggle sits in the subscription
 * settings for changing it later.
 */
@Composable
fun FreeSubAskDialog(
    onChoice: (showFreeSub: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onChoice(true) },
        title = { Text(stringResource(R.string.free_sub_ask_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.free_sub_ask_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoice(true) }) {
                Text(stringResource(R.string.free_sub_ask_show))
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoice(false) }) {
                Text(stringResource(R.string.free_sub_ask_hide))
            }
        }
    )
}
