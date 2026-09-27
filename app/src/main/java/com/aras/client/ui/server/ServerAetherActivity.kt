package com.aras.client.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.enums.EConfigType
import com.aras.client.ui.compose.FormDropdownField
import com.aras.client.ui.compose.FormTextField

/**
 * Editor for an Aether / gateway profile.
 *
 * Aether's own config is a gateway profile — a WARP-style key plus the transport to
 * dial it with — so this screen collects the same choices the Aether app shows and
 * lowers them onto a WireGuard or MASQUE outbound.
 *
 * The Aether daemon's runtime (endpoint scanning, Psiphon and Tor nesting, nested
 * modes) is not reproduced: those need the aether process, not a core config. The
 * options that do map are real — obfuscation applies AmneziaWG junk packets, and an
 * IPv4 preference forces v4.
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

            val protocolOptions = stringArrayResource(R.array.aether_protocols).toList()
            val ipVersionOptions = stringArrayResource(R.array.aether_ip_versions).toList()
            val obfuscationOptions = stringArrayResource(R.array.aether_obfuscations).toList()

            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_protocol),
                value = uiState.aetherProtocol,
                options = protocolOptions,
                onValueChange = { uiState.aetherProtocol = it },
            )
            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_obfuscation),
                value = uiState.aetherObfuscation,
                options = obfuscationOptions,
                onValueChange = { uiState.aetherObfuscation = it },
            )
            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_ip_version),
                value = uiState.aetherIpVersion,
                options = ipVersionOptions,
                onValueChange = { uiState.aetherIpVersion = it },
            )

            if (uiState.aetherProtocol == AppConfig.AETHER_PROTOCOL_MASQUE) {
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
        }
    }
}
