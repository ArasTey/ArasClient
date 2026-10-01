package com.aras.client.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.nullIfBlank
import com.aras.client.extension.toastError
import com.aras.client.ui.compose.FormTextField
import com.aras.client.util.Utils

/**
 * Editor for a Mieru profile.
 *
 * The tunnel is dialled by the mieru client, a separate process that serves a local
 * SOCKS, so this collects the endpoint and the credentials the client is given rather
 * than transport settings.
 */
class ServerMieruActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.MIERU

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig)
        }.apply {
            configType = EConfigType.MIERU
        }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) }
        ) {
            CommonBasicFields(uiState)

            FormTextField(
                stringResource(R.string.server_lab_mieru_username),
                uiState.username,
                { uiState.username = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_mieru_password),
                uiState.password,
                { uiState.password = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_local_mtu),
                uiState.mtu,
                { uiState.mtu = it },
                keyboardType = KeyboardType.Number
            )
            FormTextField(
                stringResource(R.string.server_lab_mieru_listen_port),
                uiState.mieruListenPort,
                { uiState.mieruListenPort = it },
                keyboardType = KeyboardType.Number
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        val port = config.serverPort?.trim()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            toastError(R.string.server_lab_mieru_bad_port)
            return false
        }
        // The client is told an address or a name, never both.
        val host = config.server?.trim().orEmpty()
        if (host.isEmpty()) {
            toastError(R.string.server_lab_mieru_no_host)
            return false
        }
        if (!Utils.isPureIpAddress(host) && !Utils.isDomainName(host)) {
            toastError(R.string.server_lab_mieru_bad_host)
            return false
        }
        config.server = host
        config.mieruListenPort = config.mieruListenPort?.nullIfBlank()
            ?.takeIf { it.toIntOrNull() in 1..65535 }
            ?: null
        return true
    }
}