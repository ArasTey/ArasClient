package com.aras.client.ui.export

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.aras.client.R

/**
 * Password prompt for importing a Protected .arasc file. Stays open with an
 * inline error when the password is wrong so the user can retry without
 * picking the file again. The password is used only for decryption and never
 * persisted.
 */
@Composable
fun ArascPasswordDialog(
    show: Boolean,
    error: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (!show) return
    var password by remember { mutableStateOf("") }
    // Clear the typed password whenever the dialog re-opens or a wrong
    // password is rejected, so a stale value is never re-submitted.
    androidx.compose.runtime.LaunchedEffect(show, error) {
        password = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.arasc_password_title)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.export_password)) },
                supportingText = {
                    if (error) {
                        Text(
                            text = stringResource(R.string.arasc_err_password),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                isError = error,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty()
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
