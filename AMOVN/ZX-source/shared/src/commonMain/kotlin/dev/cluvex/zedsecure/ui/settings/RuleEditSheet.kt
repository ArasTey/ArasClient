@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.domain.config.RuleValidation
import dev.cluvex.zedsecure.domain.model.RulesetItem
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.components.PickerField
import dev.cluvex.zedsecure.ui.platform.LocalPlatform
import org.jetbrains.compose.resources.stringResource

@Composable
fun RuleEditSheet(
    initial: RulesetItem,
    onDismiss: () -> Unit,
    onSave: (RulesetItem) -> Unit,
    serverTargets: List<Pair<String, String>> = emptyList(),

    groupTargets: List<Pair<String, String>> = emptyList(),

    sniffingEnabled: Boolean = true,
) {
    var remarks by rememberSaveable(initial.id) { mutableStateOf(initial.remarks) }
    var outbound by rememberSaveable(initial.id) { mutableStateOf(initial.outboundTag) }
    var domain by rememberSaveable(initial.id) { mutableStateOf(initial.domain.joinToString(", ")) }
    var ip by rememberSaveable(initial.id) { mutableStateOf(initial.ip.joinToString(", ")) }
    var port by rememberSaveable(initial.id) { mutableStateOf(initial.port) }
    var network by rememberSaveable(initial.id) { mutableStateOf(initial.network) }
    var protocol by rememberSaveable(initial.id) { mutableStateOf(initial.protocol.joinToString(", ")) }
    var networkTypes by rememberSaveable(initial.id) { mutableStateOf(initial.networkType.joinToString(",")) }
    var locked by rememberSaveable(initial.id) { mutableStateOf(initial.locked) }

    val domainList = domain.toList()
    val ipList = ip.toList()
    val protocolList = protocol.toList()
    val networkTypeList = networkTypes.split(',').filter { it in RulesetItem.NETWORK_TYPES }
    val domainError = domainList.firstNotNullOfOrNull { RuleValidation.checkDomainEntry(it) }
    val ipError = ipList.firstNotNullOfOrNull { RuleValidation.checkIpEntry(it) }
    val portValid = RuleValidation.isValidPort(port)
    val remarksBlank = remarks.isBlank()
    val canSave = domainError == null && ipError == null && portValid && !remarksBlank

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.rule_edit_title),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            OutlinedTextField(
                value = remarks,
                onValueChange = { remarks = it },
                label = { Text(stringResource(Res.string.rule_remarks)) },
                singleLine = true,
                isError = remarksBlank,
                supportingText = if (remarksBlank) {
                    { Text(stringResource(Res.string.rule_error_remarks)) }
                } else null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            val outboundOptions = buildList {
                add(RulesetItem.OUTBOUND_PROXY to stringResource(Res.string.rule_outbound_proxy))
                add(RulesetItem.OUTBOUND_DIRECT to stringResource(Res.string.rule_outbound_direct))
                add(RulesetItem.OUTBOUND_BLOCK to stringResource(Res.string.rule_outbound_block))
                addAll(groupTargets)
                serverTargets.forEach { (id, name) -> add(RulesetItem.profileTag(id) to name) }
            }
            PickerField(
                label = stringResource(Res.string.rule_outbound),
                options = outboundOptions,
                selected = if (outboundOptions.any { it.first == outbound }) outbound
                else RulesetItem.OUTBOUND_PROXY,
                onSelect = { outbound = it },
            )

            RuleField(
                label = stringResource(Res.string.rule_domain),
                value = domain,
                error = domainError?.message(),
                onChange = { domain = it },
            )
            RuleField(
                label = stringResource(Res.string.rule_ip),
                value = ip,
                error = ipError?.message(),
                onChange = { ip = it },
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text(stringResource(Res.string.rule_port)) },
                singleLine = true,
                isError = !portValid,
                supportingText = if (!portValid) {
                    { Text(stringResource(Res.string.rule_error_port)) }
                } else null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            PickerField(
                label = stringResource(Res.string.rule_network),
                options = listOf(
                    "" to stringResource(Res.string.rule_network_any),
                    "tcp" to "tcp",
                    "udp" to "udp",
                    "tcp,udp" to "tcp,udp",
                ),
                selected = network,
                onSelect = { network = it },
            )
            if (LocalPlatform.current.supportsNetworkTypeRules) {
                NetworkTypeField(selected = networkTypeList, onChange = { networkTypes = it.joinToString(",") })
            }
            RuleField(
                label = stringResource(Res.string.rule_protocol),
                value = protocol,
                error = null,
                onChange = { protocol = it },
            )

            if (protocolList.isNotEmpty() && !sniffingEnabled) {
                Text(
                    stringResource(Res.string.rule_warn_protocol_sniffing),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.rule_locked),
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(Res.string.rule_locked_desc),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = locked, onCheckedChange = { locked = it })
            }

            Text(
                stringResource(Res.string.rule_field_hint),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier.fillMaxWidth().navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.action_cancel))
                }
                Button(
                    enabled = canSave,
                    onClick = {
                        onSave(
                            initial.copy(
                                remarks = remarks.trim(),
                                outboundTag = outbound,
                                domain = domainList,
                                ip = ipList,
                                port = port.trim(),
                                network = network,
                                protocol = protocolList,
                                networkType = networkTypeList,
                                locked = locked,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(Res.string.action_save))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun NetworkTypeField(selected: List<String>, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(Res.string.rule_network_type),
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RulesetItem.NETWORK_TYPES.forEach { type ->
                val on = type in selected
                FilterChip(
                    selected = on,
                    onClick = {
                        onChange(RulesetItem.NETWORK_TYPES.filter { if (it == type) !on else it in selected })
                    },
                    label = { Text(networkTypeLabel(type), maxLines = 1) },
                )
            }
        }
        Text(
            stringResource(Res.string.rule_network_type_hint),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun networkTypeLabel(type: String): String = when (type) {
    RulesetItem.NETWORK_WIFI -> stringResource(Res.string.network_type_wifi)
    RulesetItem.NETWORK_CELLULAR -> stringResource(Res.string.network_type_cellular)
    RulesetItem.NETWORK_ETHERNET -> stringResource(Res.string.network_type_ethernet)
    else -> stringResource(Res.string.network_type_other)
}

@Composable
private fun RuleField(label: String, value: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 1,
        maxLines = 4,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RuleValidation.Problem.message(): String = when (this) {
    RuleValidation.Problem.GeoIpInDomainField -> stringResource(Res.string.rule_error_geoip_in_domain)
    RuleValidation.Problem.GeoSiteInIpField -> stringResource(Res.string.rule_error_geosite_in_ip)
    RuleValidation.Problem.NegationInDomainField -> stringResource(Res.string.rule_error_negation_domain)
    RuleValidation.Problem.DotlessWithDot -> stringResource(Res.string.rule_error_dotless)
    RuleValidation.Problem.NotAnAddress -> stringResource(Res.string.rule_error_not_address)
    RuleValidation.Problem.BadRegex -> stringResource(Res.string.rule_error_regex)
}

private fun String.toList(): List<String> =
    split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
