package com.aras.client.ui.server

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.aras.client.R
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.fmt.CarrierFmt
import com.aras.client.ui.compose.CarrierPortField
import com.aras.client.ui.compose.FormDropdownField
import com.aras.client.ui.compose.FormTextField

/**
 * Editor for a Tor profile that runs on its own, without an Aether tunnel.
 *
 * Tor picks its exit country itself — the bundled core has no option to name one —
 * so the settings here are the bridges it may start with and its local listen port.
 */
class ServerTorActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.TOR

    // Whether this save filled the listen port itself, which is what the name says.
    private var listenPortWasAuto = false

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig).apply {
                configType = EConfigType.TOR
            }
        }

        ServerEditorScaffold(
            title = "Tor",
            onSaveClick = {
                listenPortWasAuto = uiState.aetherListenPort.isBlank()
                if (listenPortWasAuto) {
                    uiState.aetherListenPort = pickCarrierListenPort()
                }
                saveServer(uiState)
            }
        ) {
            FormTextField(
                stringResource(R.string.server_lab_remarks),
                uiState.remarks,
                { uiState.remarks = it }
            )
            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_tor_bridges),
                value = uiState.aetherTorBridges,
                options = stringArrayResource(R.array.aether_tor_bridges).toList(),
                onValueChange = { uiState.aetherTorBridges = it },
            )
            if (uiState.aetherTorBridges == "own") {
                FormTextField(
                    stringResource(R.string.server_lab_aether_tor_bridge_lines),
                    uiState.aetherTorBridgeLines,
                    { uiState.aetherTorBridgeLines = it },
                    maxLines = 8,
                )
            } else {
                FormDropdownField(
                    label = stringResource(R.string.server_lab_aether_tor_relays),
                    value = uiState.aetherTorRelays,
                    options = stringArrayResource(R.array.aether_tor_relays).toList(),
                    onValueChange = { uiState.aetherTorRelays = it },
                )
            }
            CarrierPortField(
                value = uiState.aetherListenPort,
                onValueChange = { uiState.aetherListenPort = it },
                pickAuto = { pickCarrierListenPort() },
            )
            Text(
                stringResource(R.string.server_lab_tor_note),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    /**
     * A Tor profile has no address to check and its name is optional — a blank one
     * is filled in after every check passes.
     */
    override fun validateBasicConfig(state: ServerUiState): Boolean = true

    override fun nameProfile(state: ServerUiState, config: ProfileItem) {
        if (config.remarks.isBlank()) {
            config.remarks = uniqueRemarks(CarrierFmt.defaultRemarks(config, listenPortWasAuto))
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean = validateCarrierConfig(config)
}
