package com.aras.client.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aras.client.R
import com.aras.client.enums.AetherProtocol
import com.aras.client.enums.EConfigType
import com.aras.client.ui.compose.FormDropdownField
import com.aras.client.ui.compose.FormTextField

/**
 * Editor for an Aether profile.
 *
 * Field names and values follow the aether core's own, so a link or a .arasc file
 * moves between ArasClient, the Aether app and PattNG unchanged.
 *
 * gool and mim are the core's two-hop tunnels and lower to a WireGuard profile here;
 * Psiphon and Tor are the daemon's runtime and are not offered, because ArasClient
 * runs the gateway in-process rather than spawning the aether binary.
 */
class ServerAetherActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.AETHER

    @Composable
    override fun ScreenContent() {
        val scope = rememberCoroutineScope()
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig)
        }.apply {
            configType = EConfigType.AETHER
        }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) }
        ) {
            CommonBasicFields(uiState)

            val protocol = AetherProtocol.fromString(uiState.aetherProtocol)
            val overMasque = protocol.overMasque
            val twoHops = protocol.twoHops

            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_protocol),
                value = uiState.aetherProtocol,
                options = stringArrayResource(R.array.aether_protocols).toList(),
                onValueChange = { uiState.aetherProtocol = it },
            )
            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_scan_mode),
                value = uiState.aetherScanMode,
                options = stringArrayResource(R.array.aether_scan_modes).toList(),
                onValueChange = { uiState.aetherScanMode = it },
            )
            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_obfuscation),
                value = uiState.aetherObfuscation,
                options = stringArrayResource(R.array.aether_obfuscations).toList(),
                onValueChange = { uiState.aetherObfuscation = it },
            )
            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_ip_version),
                value = uiState.aetherIpVersion,
                options = stringArrayResource(R.array.aether_ip_versions).toList(),
                onValueChange = { uiState.aetherIpVersion = it },
            )
            if (overMasque) {
                FormDropdownField(
                    label = stringResource(R.string.server_lab_aether_transport),
                    value = uiState.aetherTransport,
                    options = stringArrayResource(R.array.aether_transports).toList(),
                    onValueChange = { uiState.aetherTransport = it },
                )
            }

            if (twoHops) {
                // Two-hop tunnels name both gateways instead of one address/port pair.
                FormTextField(
                    stringResource(R.string.server_lab_aether_outer_hop),
                    uiState.aetherWiwOuter,
                    { uiState.aetherWiwOuter = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_aether_inner_hop),
                    uiState.aetherWiwInner,
                    { uiState.aetherWiwInner = it }
                )
            }

            if (protocol == AetherProtocol.MASQUE) {
                FormTextField(
                    stringResource(R.string.server_lab_sni),
                    uiState.sni,
                    { uiState.sni = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_path),
                    uiState.path,
                    { uiState.path = it }
                )
            } else {
                FormTextField(
                    stringResource(R.string.server_lab_secret_key),
                    uiState.secretKey,
                    { uiState.secretKey = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_public_key),
                    uiState.publicKey,
                    { uiState.publicKey = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_preshared_key),
                    uiState.preSharedKey,
                    { uiState.preSharedKey = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_local_address),
                    uiState.localAddress,
                    { uiState.localAddress = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_reserved),
                    uiState.reserved,
                    { uiState.reserved = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_local_mtu),
                    uiState.mtu,
                    { uiState.mtu = it },
                    keyboardType = KeyboardType.Number
                )
            }

            FormTextField(
                stringResource(R.string.server_lab_aether_dns),
                uiState.aetherDns,
                { uiState.aetherDns = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_aether_exit_loc),
                uiState.aetherExitLoc,
                { uiState.aetherExitLoc = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_aether_listen_port),
                uiState.aetherListenPort,
                { uiState.aetherListenPort = it },
                keyboardType = KeyboardType.Number
            )
        }
    }
}
