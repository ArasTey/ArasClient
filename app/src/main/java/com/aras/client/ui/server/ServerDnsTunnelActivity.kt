package com.aras.client.ui.server

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aras.client.R
import com.aras.client.core.dnstunnel.DnsTunnelConfigRenderer
import com.aras.client.core.dnstunnel.DnsTunnelEngine
import com.aras.client.core.dnstunnel.DnsTunnelProcessManager
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.nullIfBlank
import com.aras.client.extension.toastError
import com.aras.client.extension.toastSuccess
import com.aras.client.handler.MmkvManager
import com.aras.client.ui.compose.FormDropdownField
import com.aras.client.ui.compose.FormTextField
import com.aras.client.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor for a DNS-tunnel profile.
 *
 * A DNS tunnel is a transport, not a protocol: the engine carries TCP through
 * ordinary DNS queries and serves a local SOCKS, and the profile names the
 * engine, the tunnel zone and its key, the resolvers the engine asks, and the
 * profile whose traffic the tunnel carries.
 */
class ServerDnsTunnelActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.DNSTUNNEL

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig)
        }.apply {
            configType = EConfigType.DNSTUNNEL
        }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) }
        ) {
            CommonBasicFields(uiState)

            val engines = DnsTunnelEngine.entries
            val engine = DnsTunnelEngine.of(uiState.dnsTunnelEngine)
            FormDropdownField(
                label = stringResource(R.string.server_lab_dnstunnel_engine),
                value = engine.displayName,
                options = engines.map { it.displayName },
                onValueChange = { name ->
                    engines.firstOrNull { it.displayName == name }?.let { uiState.dnsTunnelEngine = it.id }
                },
            )
            FormTextField(
                stringResource(R.string.server_lab_dnstunnel_domain),
                uiState.dnsTunnelDomain,
                { uiState.dnsTunnelDomain = it }
            )
            FormTextField(
                // Soar authenticates the tunnel server with its public key; the
                // StormDNS family shares a secret with it.
                stringResource(
                    if (engine == DnsTunnelEngine.SOAR) {
                        R.string.server_lab_dnstunnel_server_key
                    } else {
                        R.string.server_lab_dnstunnel_key
                    }
                ),
                uiState.dnsTunnelKey,
                { uiState.dnsTunnelKey = it }
            )
            if (engine == DnsTunnelEngine.SOAR) {
                FormTextField(
                    stringResource(R.string.server_lab_dnstunnel_country),
                    uiState.dnsTunnelCountry,
                    { uiState.dnsTunnelCountry = it },
                    placeholder = "IR",
                )
            }
            val methods = DnsTunnelConfigRenderer.ENCRYPTION_METHODS
            FormDropdownField(
                label = stringResource(R.string.server_lab_dnstunnel_method),
                value = methods.firstOrNull { it.second.toString() == uiState.dnsTunnelMethod }?.first
                    ?: methods[1].first,
                options = methods.map { it.first },
                onValueChange = { name ->
                    methods.firstOrNull { it.first == name }?.let { uiState.dnsTunnelMethod = it.second.toString() }
                },
            )
            if (engine == DnsTunnelEngine.COTTEN) {
                FormDropdownField(
                    label = stringResource(R.string.server_lab_dnstunnel_preset),
                    value = uiState.dnsTunnelPreset.ifEmpty { DnsTunnelConfigRenderer.COTTEN_PRESETS.first() },
                    options = DnsTunnelConfigRenderer.COTTEN_PRESETS,
                    onValueChange = { uiState.dnsTunnelPreset = it },
                )
            }
            FormTextField(
                stringResource(R.string.server_lab_dnstunnel_resolvers),
                uiState.dnsTunnelResolvers,
                { uiState.dnsTunnelResolvers = it },
                placeholder = stringResource(R.string.server_lab_dnstunnel_resolvers_hint),
            )
            // The engine knows which of the resolvers carried the tunnel and which
            // did not; asking it is the only way to tell.
            var scanning by remember { mutableStateOf(false) }
            Button(
                onClick = {
                    val resolvers = DnsTunnelConfigRenderer.normalizeResolvers(uiState.dnsTunnelResolvers)
                    if (resolvers.isEmpty()) {
                        toastError(R.string.server_lab_dnstunnel_bad_resolvers)
                        return@Button
                    }
                    val profile = uiState.toProfileItem(initialConfig)
                    scanning = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            DnsTunnelProcessManager.scan(context, profile, engine, resolvers)
                        }
                        scanning = false
                        if (result.valid.isNotEmpty()) {
                            uiState.dnsTunnelResolvers = result.valid.joinToString("\n")
                            context.toastSuccess(
                                context.getString(R.string.server_lab_dnstunnel_scan_done, result.valid.size)
                            )
                        } else {
                            context.toastError(R.string.server_lab_dnstunnel_scan_none)
                        }
                    }
                },
                enabled = !scanning,
            ) {
                Text(stringResource(R.string.server_lab_dnstunnel_scan))
            }
            FormTextField(
                stringResource(R.string.server_lab_dnstunnel_port),
                uiState.dnsTunnelPort,
                { uiState.dnsTunnelPort = it },
                keyboardType = KeyboardType.Number
            )
            FormTextField(
                stringResource(R.string.server_lab_dnstunnel_dns_port),
                uiState.dnsTunnelDnsPort,
                { uiState.dnsTunnelDnsPort = it },
                keyboardType = KeyboardType.Number
            )

            // The target is the profile the tunnel carries. The list is the
            // remarks of every profile that has an outbound; a stale or
            // ambiguous name still saves, and the session resolves it then.
            val targetOptions = remember {
                listOf("") + buildList {
                    (listOf("") + MmkvManager.decodeSubsList()).forEach { subId ->
                        MmkvManager.decodeServerList(subId).forEach { guid ->
                            MmkvManager.decodeServerConfig(guid)
                                ?.takeIf { it.configType != EConfigType.DNSTUNNEL }
                                ?.let { add(it.remarks) }
                        }
                    }
                }.distinct()
            }
            FormDropdownField(
                label = stringResource(R.string.server_lab_dnstunnel_target),
                value = uiState.dnsTunnelTarget,
                options = targetOptions,
                onValueChange = { uiState.dnsTunnelTarget = it },
            )
            // The extra settings are the engine's own TOML keys, and the engine
            // that reads a JSON configuration has none of them.
            if (!engine.jsonConfig) {
                FormTextField(
                    stringResource(R.string.server_lab_dnstunnel_extra),
                    uiState.dnsTunnelExtra,
                    { uiState.dnsTunnelExtra = it },
                    placeholder = stringResource(R.string.server_lab_dnstunnel_extra_hint),
                )
            }
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        val domain = config.dnsTunnelDomain?.trim().orEmpty()
        if (domain.isEmpty() || !Utils.isDomainName(domain)) {
            toastError(R.string.server_lab_dnstunnel_bad_domain)
            return false
        }
        config.dnsTunnelDomain = domain

        if (config.dnsTunnelKey.isNullOrBlank()) {
            toastError(R.string.server_lab_dnstunnel_no_key)
            return false
        }
        if (DnsTunnelEngine.of(config.dnsTunnelEngine) == DnsTunnelEngine.SOAR &&
            !DnsTunnelConfigRenderer.isValidPublicKey(config.dnsTunnelKey)
        ) {
            toastError(R.string.server_lab_dnstunnel_bad_server_key)
            return false
        }

        val resolvers = DnsTunnelConfigRenderer.normalizeResolvers(config.dnsTunnelResolvers)
        if (resolvers.isEmpty() || resolvers.any { !DnsTunnelConfigRenderer.isValidResolver(it) }) {
            toastError(R.string.server_lab_dnstunnel_bad_resolvers)
            return false
        }
        config.dnsTunnelResolvers = resolvers.joinToString("\n")

        config.dnsTunnelPort = config.dnsTunnelPort?.nullIfBlank()
            ?.takeIf { it.toIntOrNull() in 1..65535 }
            ?: null
        config.dnsTunnelDnsPort = config.dnsTunnelDnsPort?.nullIfBlank()
            ?.takeIf { it.toIntOrNull() in 1..65535 }
            ?: null
        return true
    }
}
