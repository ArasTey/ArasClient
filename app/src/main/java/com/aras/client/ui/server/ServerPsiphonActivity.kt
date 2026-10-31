package com.aras.client.ui.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aras.client.R
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.ui.compose.FlagIcon
import com.aras.client.ui.compose.FormDropdownField
import com.aras.client.ui.compose.FormTextField
import java.util.Locale

/**
 * Editor for a Psiphon profile that runs on its own, without an Aether tunnel.
 *
 * The app dials the core's loopback listener, so there is no server address to ask
 * for; the region is the setting that decides which country Psiphon exits from.
 */
class ServerPsiphonActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.PSIPHON

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig).apply {
                configType = EConfigType.PSIPHON
                // The carrier runs on its own listener, so it gets a free port up front
                // rather than sharing the app default with every other carrier profile.
                if (aetherListenPort.isBlank()) aetherListenPort = pickCarrierListenPort()
            }
        }

        ServerEditorScaffold(
            title = "Psiphon",
            onSaveClick = {
                if (uiState.aetherListenPort.isBlank()) {
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
            RegionPickerField(
                value = uiState.aetherPsiphonRegion,
                onPick = { uiState.aetherPsiphonRegion = it },
            )
            FormDropdownField(
                label = stringResource(R.string.server_lab_aether_psiphon_mode),
                value = uiState.aetherPsiphonMode,
                options = stringArrayResource(R.array.aether_psiphon_modes).toList(),
                onValueChange = { uiState.aetherPsiphonMode = it },
            )
            FormTextField(
                stringResource(R.string.server_lab_aether_listen_port),
                uiState.aetherListenPort,
                { uiState.aetherListenPort = it },
                keyboardType = KeyboardType.Number
            )
            Text(
                stringResource(R.string.server_lab_psiphon_note),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean = validateCarrierConfig(config)
}

/** The countries the bundled flag set knows, by ISO code and display name. */
private data class RegionOption(val code: String, val name: String)

@Composable
private fun RegionPickerField(value: String, onPick: (String) -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val code = value.takeIf { it.isNotBlank() }
    val automatic = stringResource(R.string.server_lab_carrier_region_auto)
    val name = code?.let { Locale("", it).getDisplayCountry(locale).ifBlank { it } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            stringResource(R.string.server_lab_carrier_region),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { show = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (code != null) {
                    FlagIcon(iso = code, width = 26.dp, height = 18.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    if (code != null) "$name ($code)" else automatic,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more_24dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (show) {
        RegionPickerDialog(
            selected = value,
            onPick = onPick,
            onDismiss = { show = false },
        )
    }
}

@Composable
private fun RegionPickerDialog(
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val regions = remember(locale) {
        val files = runCatching { context.assets.list("flags")?.toSet() }.getOrNull().orEmpty()
        Locale.getISOCountries()
            .filter { "${it.lowercase(Locale.ROOT)}.svg" in files }
            .map { code ->
                RegionOption(code, Locale("", code).getDisplayCountry(locale).ifBlank { code })
            }
            .sortedBy { it.name.lowercase(locale) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(query, regions) {
        val text = query.trim()
        if (text.isEmpty()) {
            regions
        } else {
            regions.filter {
                it.name.contains(text, ignoreCase = true) || it.code.contains(text, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_lab_carrier_region)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.server_lab_carrier_region_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    item {
                        RegionRow(
                            code = null,
                            name = stringResource(R.string.server_lab_carrier_region_auto),
                            selected = selected.isBlank(),
                            onClick = {
                                onPick("")
                                onDismiss()
                            },
                        )
                    }
                    items(visible, key = { it.code }) { option ->
                        RegionRow(
                            code = option.code,
                            name = option.name,
                            selected = selected == option.code,
                            onClick = {
                                onPick(option.code)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun RegionRow(
    code: String?,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        if (code != null) {
            FlagIcon(iso = code, width = 24.dp, height = 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (code != null) {
                Text(
                    code,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
