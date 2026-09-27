package com.aras.client.ui.server

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aras.client.core.aether.AetherCore
import com.aras.client.core.aether.AetherCoreManager
import com.aras.client.core.aether.AetherIdentityManager
import com.aras.client.core.aether.AetherIdentityStatus
import com.aras.client.enums.AetherProtocol
import com.aras.client.enums.AetherPsiphon
import com.aras.client.enums.AetherTor
import com.aras.client.fmt.AetherFmt
import com.aras.client.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.extension.nullIfBlank
import com.aras.client.extension.toastError
import com.aras.client.enums.EConfigType
import com.aras.client.ui.compose.FormDropdownField
import com.aras.client.ui.compose.FormTextField
import com.aras.client.ui.compose.FormToggleField

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

            FormTextField(
                stringResource(R.string.server_lab_aether_command),
                uiState.aetherCommand,
                { uiState.aetherCommand = it },
            )

            AetherCarriersSection(uiState)

            AetherIdentitySection(uiState, scope)
        }
    }

    /**
     * Refuses a profile the core would not take.
     *
     * Each of these builds a valid-looking config and then fails to connect, so they
     * are caught here rather than left to be discovered on a network.
     */
    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        config.aetherCommand?.nullIfBlank()?.let { command ->
            if (AetherCore.ofCommand(command) == null) {
                toastError(R.string.server_lab_aether_command_bad)
                return false
            }
        }
        when (AetherFmt.normalize(config)) {
            null -> Unit
            AetherFmt.Problem.PSIPHON_NEEDS_MASQUE, AetherFmt.Problem.TOR_NEEDS_MASQUE -> {
                toastError(R.string.server_lab_aether_needs_masque)
                return false
            }

            AetherFmt.Problem.TOR_PSIPHON_CONFLICT -> {
                toastError(R.string.server_lab_aether_tor_psiphon_conflict)
                return false
            }

            AetherFmt.Problem.TOR_BRIDGES_MISSING -> {
                toastError(R.string.server_lab_aether_tor_bridges_missing)
                return false
            }

            AetherFmt.Problem.INVALID_EXIT_LOC -> {
                toastError(R.string.server_lab_aether_exit_loc_bad)
                return false
            }

            AetherFmt.Problem.INVALID_DNS -> {
                toastError(R.string.server_lab_aether_dns_bad)
                return false
            }

            AetherFmt.Problem.INVALID_PEER -> {
                toastError(R.string.server_lab_aether_peer_bad)
                return false
            }

            AetherFmt.Problem.INVALID_HOP, AetherFmt.Problem.SHARED_HOP -> {
                toastError(R.string.server_lab_aether_hop_bad)
                return false
            }

            AetherFmt.Problem.INVALID_LISTEN_PORT -> {
                toastError(R.string.server_lab_aether_listen_port_bad)
                return false
            }
        }
        return true
    }

    /**
     * Psiphon and Tor, the two programs that can carry the tunnel or sit inside it.
     *
     * The pairings the core refuses are stated on the fields rather than left to be
     * discovered at connect time: either one in *reverse* needs MASQUE, and the two of
     * them go together only nested.
     */
    @Composable
    private fun AetherCarriersSection(state: ServerUiState) {
        val psiphon = AetherPsiphon.fromString(state.aetherPsiphon)
        val tor = AetherTor.fromString(state.aetherTor)
        val overMasque = AetherProtocol.fromString(state.aetherProtocol).overMasque

        FormDropdownField(
            label = stringResource(R.string.server_lab_aether_psiphon),
            value = state.aetherPsiphon,
            options = stringArrayResource(R.array.aether_psiphons).toList(),
            onValueChange = { state.aetherPsiphon = it },
            supportingText = if (psiphon == AetherPsiphon.REVERSE && !overMasque) {
                stringResource(R.string.server_lab_aether_needs_masque)
            } else {
                null
            },
        )
        if (psiphon != AetherPsiphon.OFF) {
            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_psiphon_mode),
                value = state.aetherPsiphonMode,
                options = stringArrayResource(R.array.aether_psiphon_modes).toList(),
                onValueChange = { state.aetherPsiphonMode = it },
            )
            FormTextField(
                stringResource(R.string.server_lab_aether_psiphon_cdn_ips),
                state.aetherPsiphonCdnIps,
                { state.aetherPsiphonCdnIps = it },
            )
            FormTextField(
                stringResource(R.string.server_lab_aether_psiphon_cdn_sni),
                state.aetherPsiphonCdnSni,
                { state.aetherPsiphonCdnSni = it },
            )
            FormTextField(
                stringResource(R.string.server_lab_aether_psiphon_cdn_sets),
                state.aetherPsiphonCdnSets,
                { state.aetherPsiphonCdnSets = it },
            )
            FormTextField(
                stringResource(R.string.server_lab_aether_psiphon_region),
                state.aetherPsiphonRegion,
                { state.aetherPsiphonRegion = it },
            )
            FormToggleField(
                label = stringResource(R.string.server_lab_aether_psiphon_bundled),
                checked = state.aetherPsiphonBundledList,
                onCheckedChange = { state.aetherPsiphonBundledList = it },
            )
        }

        FormDropdownField(
            label = stringResource(R.string.server_lab_aether_tor),
            value = state.aetherTor,
            options = stringArrayResource(R.array.aether_tors).toList(),
            onValueChange = { state.aetherTor = it },
            supportingText = when {
                tor == AetherTor.REVERSE && !overMasque ->
                    stringResource(R.string.server_lab_aether_needs_masque)

                tor != AetherTor.OFF && psiphon != AetherPsiphon.OFF &&
                    !(tor == AetherTor.CHAIN && psiphon == AetherPsiphon.REVERSE) &&
                    !(tor == AetherTor.REVERSE && psiphon == AetherPsiphon.CHAIN) ->
                    stringResource(R.string.server_lab_aether_tor_psiphon_conflict)

                else -> null
            },
        )
        if (tor != AetherTor.OFF) {
            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_tor_bridges),
                value = state.aetherTorBridges,
                options = stringArrayResource(R.array.aether_tor_bridges).toList(),
                onValueChange = { state.aetherTorBridges = it },
            )
            if (state.aetherTorBridges == "own") {
                FormTextField(
                    stringResource(R.string.server_lab_aether_tor_bridge_lines),
                    state.aetherTorBridgeLines,
                    { state.aetherTorBridgeLines = it },
                )
            } else {
                FormDropdownField(
                    label = stringResource(R.string.server_lab_aether_tor_relays),
                    value = state.aetherTorRelays,
                    options = stringArrayResource(R.array.aether_tor_relays).toList(),
                    onValueChange = { state.aetherTorRelays = it },
                )
            }
        }
    }

    /**
     * The WARP identity the core is holding, with the two actions that change it.
     *
     * The core registers a device by itself on first run, so this reports what it
     * wrote rather than creating anything. Renewing moves the old files aside and
     * lets the core register again; a renewal that fails leaves the working one in
     * place.
     */
    @Composable
    private fun AetherIdentitySection(state: ServerUiState, scope: kotlinx.coroutines.CoroutineScope) {
        val context = LocalContext.current
        val protocol = AetherProtocol.fromString(state.aetherProtocol)
        var identity by remember(protocol) { mutableStateOf<AetherIdentityStatus?>(null) }
        var busy by remember { mutableStateOf(false) }
        var log by remember { mutableStateOf("") }

        // A scan and a renewal both run a core of their own, which is why the buttons
        // sit on a port clear of the session's.
        fun runOneShot(source: String, block: () -> String?) {
            if (busy) return
            busy = true
            log = ""
            scope.launch(Dispatchers.IO) {
                val lines = StringBuilder()
                val ok = try {
                    block() != null
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "AetherCore: $source failed", e)
                    lines.append(e.message ?: e.javaClass.simpleName)
                    false
                }
                identity = withContext(Dispatchers.IO) {
                    AetherIdentityManager.status(context, protocol)
                }
                log = if (ok) "" else lines.toString()
                busy = false
            }
        }

        Button(
            enabled = !busy,
            onClick = {
                runOneShot("scan") {
                    AetherCoreManager.runUntil(
                        context = context,
                        arguments = AetherCoreManager.buildArguments(
                            state.toProfileItem(initialConfig),
                            AetherCoreManager.scanPort(state.toProfileItem(initialConfig)),
                            scan = true,
                        ),
                        timeoutMs = AetherCoreManager.ONE_SHOT_TIMEOUT_MS,
                        source = "aether-scan",
                        onOutput = { line -> LogUtil.d(AppConfig.TAG, "aether scan | $line") },
                    ) { true }
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.server_lab_aether_scan))
        }

        Button(
            enabled = !busy,
            onClick = {
                runOneShot("renew") {
                    AetherIdentityManager.renew(
                        context,
                        state.toProfileItem(initialConfig),
                    ) { line -> LogUtil.d(AppConfig.TAG, "aether key | $line") }?.primary?.deviceId
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.server_lab_aether_new_key))
        }

        val current = identity
        if (current != null) {
            FormTextField(
                stringResource(R.string.server_lab_aether_identity),
                buildString {
                    append(current.primary?.deviceId.orEmpty())
                    if (!current.primary?.ipv4.isNullOrBlank()) append("\n").append(current.primary?.ipv4)
                    if (!current.primary?.ipv6.isNullOrBlank()) append("\n").append(current.primary?.ipv6)
                },
                onValueChange = {},
                enabled = false,
            )
        }
        if (log.isNotBlank()) {
            FormTextField(
                stringResource(R.string.server_lab_aether_log),
                log,
                onValueChange = {},
                enabled = false,
            )
        }
    }
}
