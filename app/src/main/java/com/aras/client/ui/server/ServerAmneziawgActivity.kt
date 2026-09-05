package com.aras.client.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aras.client.R
import com.aras.client.enums.EConfigType
import com.aras.client.ui.compose.FormTextField

class ServerAmneziawgActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.AMNEZIAWG

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(
                initialConfig = initialConfig
            )
        }.apply {
            configType = EConfigType.AMNEZIAWG
        }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) }
        ) {
            CommonBasicFields(uiState)
            AmneziawgProtocolFields(uiState)
        }
    }

    @Composable
    private fun AmneziawgProtocolFields(state: ServerUiState) {
        FormTextField(
            stringResource(R.string.server_lab_secret_key),
            state.secretKey,
            { state.secretKey = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_public_key),
            state.publicKey,
            { state.publicKey = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_preshared_key),
            state.preSharedKey,
            { state.preSharedKey = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_reserved),
            state.reserved,
            { state.reserved = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_local_address),
            state.localAddress,
            { state.localAddress = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_local_mtu),
            state.mtu,
            { state.mtu = it },
            keyboardType = KeyboardType.Number
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.server_amnezia_section),
                style = MaterialTheme.typography.titleSmall
            )
        }
        FormTextField(
            stringResource(R.string.server_awg_junk_packet_count),
            state.junkPacketCount,
            { state.junkPacketCount = it },
            keyboardType = KeyboardType.Number
        )
        FormTextField(
            stringResource(R.string.server_awg_junk_packet_min),
            state.junkPacketMinSize,
            { state.junkPacketMinSize = it },
            keyboardType = KeyboardType.Number
        )
        FormTextField(
            stringResource(R.string.server_awg_junk_packet_max),
            state.junkPacketMaxSize,
            { state.junkPacketMaxSize = it },
            keyboardType = KeyboardType.Number
        )
        FormTextField(
            stringResource(R.string.server_awg_init_packet_junk),
            state.initPacketJunkSize,
            { state.initPacketJunkSize = it },
            keyboardType = KeyboardType.Number
        )
        FormTextField(
            stringResource(R.string.server_awg_response_packet_junk),
            state.responsePacketJunkSize,
            { state.responsePacketJunkSize = it },
            keyboardType = KeyboardType.Number
        )
        FormTextField(
            stringResource(R.string.server_awg_init_header),
            state.initPacketJunkHeader,
            { state.initPacketJunkHeader = it }
        )
        FormTextField(
            stringResource(R.string.server_awg_response_header),
            state.responsePacketJunkHeader,
            { state.responsePacketJunkHeader = it }
        )
        FormTextField(
            stringResource(R.string.server_awg_transport_header),
            state.transportPacketJunkHeader,
            { state.transportPacketJunkHeader = it }
        )
    }
}
