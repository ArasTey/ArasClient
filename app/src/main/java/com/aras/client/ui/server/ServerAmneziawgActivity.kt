package com.aras.client.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aras.client.R
import com.aras.client.enums.EConfigType
import com.aras.client.ui.compose.FormTextField
import com.aras.client.util.MimicPackets

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
        FormTextField(
            stringResource(R.string.server_lab_keepalive),
            state.keepAlive,
            { state.keepAlive = it },
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
            stringResource(R.string.server_awg_cookie_header),
            state.cookiePacketJunkHeader,
            { state.cookiePacketJunkHeader = it }
        )
        FormTextField(
            stringResource(R.string.server_awg_transport_header),
            state.transportPacketJunkHeader,
            { state.transportPacketJunkHeader = it }
        )
        MimicPacketField(
            value = state.mimicPackets,
            onValueChange = { state.mimicPackets = it },
        )
    }
}

/**
 * Mimic packets I1..I5, one spec per line.
 *
 * A spec the bundled core cannot parse fails the whole tunnel, not just the decoy,
 * so the field names the offending line as you type, and the builders drop bad
 * specs rather than handing them to the core.
 */
@Composable
private fun MimicPacketField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val problems = remember(value) { MimicPackets.validateAll(value) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        FormTextField(
            label = stringResource(R.string.server_awg_mimic_packets),
            value = value,
            onValueChange = onValueChange,
        )
        TextButton(
            onClick = {
                val next = (MimicPackets.specs(value) + MimicPackets.sipPreset())
                onValueChange(MimicPackets.specs(next.joinToString("\n")).joinToString("\n"))
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.server_awg_mimic_sip_preset))
        }
        if (problems.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.server_awg_mimic_invalid,
                    problems.joinToString("\n"),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
