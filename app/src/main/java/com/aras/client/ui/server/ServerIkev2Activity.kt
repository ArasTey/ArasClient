package com.aras.client.ui.server

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aras.client.AppConfig.DEFAULT_PORT
import com.aras.client.R
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.toastError
import com.aras.client.fmt.Ikev2Fmt
import com.aras.client.ui.compose.FormTextField

/**
 * Editor for an IKEv2/IPsec profile. The tunnel is built by the platform's own
 * IKE engine, so the profile only names the gateway, the auth pair and the
 * identities; there is no port to pick and no transport settings.
 */
class ServerIkev2Activity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.IKEV2

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig).apply {
                configType = EConfigType.IKEV2
                // The port is only cosmetic: the platform IKE dials UDP 500/4500.
                if (port == DEFAULT_PORT.toString()) port = "500"
            }
        }

        ServerEditorScaffold(
            title = "IKEv2",
            onSaveClick = { saveServer(uiState) }
        ) {
            FormTextField(
                stringResource(R.string.server_lab_remarks),
                uiState.remarks,
                { uiState.remarks = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_address),
                uiState.address,
                { uiState.address = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_ikev2_username),
                uiState.username,
                { uiState.username = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_ikev2_password),
                uiState.password,
                { uiState.password = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_ikev2_local_id),
                uiState.ikev2LocalId,
                { uiState.ikev2LocalId = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_ikev2_remote_id),
                uiState.ikev2RemoteId,
                { uiState.ikev2RemoteId = it }
            )
            Text(
                stringResource(R.string.server_lab_ikev2_note),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.server.isNullOrBlank()) {
            toastError(R.string.server_lab_ikev2_no_host)
            return false
        }
        return true
    }

    override fun nameProfile(state: ServerUiState, config: ProfileItem) {
        if (config.remarks.isBlank()) {
            config.remarks = uniqueRemarks(Ikev2Fmt.defaultRemarks(config))
        }
    }
}
