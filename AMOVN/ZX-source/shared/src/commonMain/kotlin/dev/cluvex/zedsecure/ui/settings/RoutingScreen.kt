@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import dev.cluvex.zedsecure.data.assets.usesGeoFiles
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.domain.config.RoutingMigration
import dev.cluvex.zedsecure.domain.config.RulesetTransfer
import dev.cluvex.zedsecure.domain.model.AppSettings
import dev.cluvex.zedsecure.domain.model.DomainStrategy
import dev.cluvex.zedsecure.domain.model.RoutingMode
import dev.cluvex.zedsecure.domain.model.RulesetItem
import dev.cluvex.zedsecure.domain.model.RulesetPresets
import dev.cluvex.zedsecure.domain.model.RoutingPresetType
import dev.cluvex.zedsecure.domain.model.RoutingPresets
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.components.PickerField
import dev.cluvex.zedsecure.ui.components.SectionTitle
import dev.cluvex.zedsecure.ui.platform.LocalPlatform
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun RoutingScreen(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier,

    serverTargets: List<Pair<String, String>> = emptyList(),

    groupTargets: List<Pair<String, String>> = emptyList(),
) {
    val platform = LocalPlatform.current
    val importOkMsg = stringResource(Res.string.routing_import_ok)
    val importFailMsg = stringResource(Res.string.routing_import_failed)
    val exportOkMsg = stringResource(Res.string.routing_export_ok)

    val rules = remember(settings.rulesets, settings.routingMigrated, settings.customProxyRules,
        settings.customDirectRules, settings.customBlockRules) {
        RoutingMigration.effectiveRulesets(settings)
    }

    fun mutate(transform: (List<RulesetItem>) -> List<RulesetItem>) {
        val next = transform(RoutingMigration.effectiveRulesets(settings))
        onUpdate {
            it.copy(
                rulesets = next,
                routingMigrated = true,
                customProxyRules = "",
                customDirectRules = "",
                customBlockRules = "",
            )
        }
    }

    fun replaceAll(incoming: List<RulesetItem>) {
        mutate { existing -> RoutingPresets.apply(existing, incoming) }
    }

    var editing by remember { mutableStateOf<RulesetItem?>(null) }
    var deleting by remember { mutableStateOf<RulesetItem?>(null) }
    var unlocking by remember { mutableStateOf<RulesetItem?>(null) }
    var presetOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val duplicateMsg = stringResource(Res.string.routing_preset_duplicate)

    editing?.let { rule ->
        RuleEditSheet(
            initial = rule,
            serverTargets = serverTargets,
            groupTargets = groupTargets,
            sniffingEnabled = settings.sniffingEnabled,
            onDismiss = { editing = null },
            onSave = { saved ->
                mutate { list ->
                    if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                    else list + saved
                }
                editing = null
            },
        )
    }

    deleting?.let { rule ->
        val target = rule
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(Res.string.rule_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.rule_delete_confirm_body,
                        target.remarks.ifBlank { target.outboundTag },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    mutate { l -> l.filterNot { it.id == target.id } }
                    deleting = null
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    unlocking?.let { rule ->
        AlertDialog(
            onDismissRequest = { unlocking = null },
            title = { Text(stringResource(Res.string.rule_unlock_title)) },
            text = { Text(stringResource(Res.string.rule_locked_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    mutate { l -> l.map { if (it.id == rule.id) it.copy(locked = false) else it } }
                    unlocking = null
                }) { Text(stringResource(Res.string.rule_unlock)) }
            },
            dismissButton = {
                TextButton(onClick = { unlocking = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    fun requestDelete(rule: RulesetItem) {
        if (settings.confirmRemove) deleting = rule
        else mutate { l -> l.filterNot { it.id == rule.id } }
    }

    val listState = rememberLazyListState()
    var order by remember { mutableStateOf(rules) }
    LaunchedEffect(rules) { order = rules }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->

        val f = from.index - RULES_HEADER_ITEMS
        val t = to.index - RULES_HEADER_ITEMS
        if (f in order.indices && t in order.indices) {
            order = order.toMutableList().apply { add(t, removeAt(f)) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(contentPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Column {
                Spacer(Modifier.height(8.dp))
                SettingsHeader(
                    title = stringResource(Res.string.routing_title),
                    subtitle = stringResource(Res.string.routing_subtitle),
                    art = SettingsArts.Routing,
                )
                Spacer(Modifier.height(14.dp))
            }
        }

        item(key = "mode") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionTitle(stringResource(Res.string.routing_mode))
                RoutingCard {
                    PickerField(
                        label = stringResource(Res.string.routing_mode),
                        options = listOf(
                            RoutingMode.Global to stringResource(Res.string.routing_mode_global),
                            RoutingMode.BypassLan to stringResource(Res.string.routing_mode_bypass_lan),
                            RoutingMode.BypassIran to stringResource(Res.string.routing_mode_bypass_mainland),
                            RoutingMode.BypassLanAndIran to
                                stringResource(Res.string.routing_mode_bypass_lan_mainland),
                        ),
                        selected = settings.routingMode,
                        onSelect = { mode -> onUpdate { it.copy(routingMode = mode) } },
                    )
                    Spacer(Modifier.height(10.dp))
                    PickerField(
                        label = stringResource(Res.string.routing_domain_strategy),
                        options = DomainStrategy.entries.map { it to it.value },
                        selected = settings.domainStrategy,
                        onSelect = { s -> onUpdate { it.copy(domainStrategy = s) } },
                    )
                    Text(
                        stringResource(Res.string.routing_domain_strategy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    ToggleRow(
                        title = stringResource(Res.string.routing_block_ads),
                        subtitle = stringResource(Res.string.routing_block_ads_desc),
                        checked = settings.blockAds,
                        onCheckedChange = { v -> onUpdate { it.copy(blockAds = v) } },
                    )
                }
                platform.geoAssets?.let { geo -> GeoFilesBanner(geo, settings) }

                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle(stringResource(Res.string.routing_rules_title))
                    Spacer(Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { presetOpen = true }) {
                            Text(stringResource(Res.string.routing_add_preset))
                        }
                        DropdownMenu(expanded = presetOpen, onDismissRequest = { presetOpen = false }) {
                            RulesetPresets.all().forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.label) },
                                    onClick = {
                                        presetOpen = false

                                        val incoming = preset.rules()
                                        val fresh = incoming.filterNot { candidate ->
                                            rules.any { it.sameMatcher(candidate) }
                                        }
                                        if (fresh.isEmpty()) platform.toast(duplicateMsg)
                                        else mutate { it + fresh }
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        editing = RulesetItem(id = RulesetItem.newId())
                    }) {
                        Icon(painterResource(Res.drawable.ic_add), contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.routing_add_rule))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                painterResource(Res.drawable.ic_more_vert),
                                contentDescription = stringResource(Res.string.routing_ruleset_menu),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            RoutingPresetType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.label()) },
                                    onClick = {
                                        replaceAll(RoutingPresets.rules(type))
                                        menuOpen = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.routing_import_clipboard)) },
                                onClick = {
                                    menuOpen = false
                                    val parsed = RulesetTransfer.decode(platform.readClipboard())
                                    if (parsed.isNullOrEmpty()) {
                                        platform.toast(importFailMsg)
                                    } else {
                                        replaceAll(parsed)
                                        platform.toast(importOkMsg)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.routing_export_clipboard)) },
                                onClick = {
                                    menuOpen = false
                                    platform.copyToClipboard(RulesetTransfer.encode(rules))
                                    platform.toast(exportOkMsg)
                                },
                            )
                        }
                    }
                }
            }
        }

        if (order.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    RoutingCard {
                        Text(
                            stringResource(Res.string.routing_rules_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            itemsIndexed(order, key = { _, rule -> rule.id }) { index, rule ->
                ReorderableItem(reorderState, key = rule.id) {
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        RuleRow(
                            rule = rule,
                            isFirst = index == 0,
                            isLast = index == order.lastIndex,
                            targetLabel = RulesetItem.profileIdOf(rule.outboundTag)?.let { id ->
                                serverTargets.firstOrNull { it.first == id }?.second
                            } ?: groupTargets.firstOrNull { it.first == rule.outboundTag }?.second,
                            dragHandle = Modifier.draggableHandle(
                                onDragStopped = { mutate { order } },
                            ),
                            onToggle = { on ->
                                mutate { l -> l.map { if (it.id == rule.id) it.copy(enabled = on) else it } }
                            },
                            onEdit = { if (rule.locked) unlocking = rule else editing = rule },
                            onDelete = { requestDelete(rule) },
                            onMoveUp = { mutate { l -> l.swap(index, index - 1) } },
                            onMoveDown = { mutate { l -> l.swap(index, index + 1) } },
                        )
                    }
                }
            }
        }

        item(key = "hint") {
            Text(
                stringResource(Res.string.routing_rules_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
    }
}

private const val RULES_HEADER_ITEMS = 2

private fun RulesetItem.sameMatcher(other: RulesetItem): Boolean =
    outboundTag == other.outboundTag &&
        domain == other.domain &&
        ip == other.ip &&
        port == other.port &&
        network == other.network &&
        protocol == other.protocol &&
        networkType == other.networkType

@Composable
private fun RoutingPresetType.label(): String = when (this) {
    RoutingPresetType.IranWhitelist -> stringResource(Res.string.routing_preset_iran)
    RoutingPresetType.ChinaWhitelist -> stringResource(Res.string.routing_preset_china_white)
    RoutingPresetType.ChinaBlacklist -> stringResource(Res.string.routing_preset_china_black)
    RoutingPresetType.Global -> stringResource(Res.string.routing_preset_global)
    RoutingPresetType.RussiaWhitelist -> stringResource(Res.string.routing_preset_russia)
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> {
    if (a !in indices || b !in indices) return this
    val out = toMutableList()
    out[a] = this[b]
    out[b] = this[a]
    return out
}

@Composable
private fun RuleRow(
    rule: RulesetItem,
    isFirst: Boolean,
    isLast: Boolean,

    targetLabel: String?,

    dragHandle: Modifier,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val tagLabel = when {
        targetLabel != null -> targetLabel

        RulesetItem.profileIdOf(rule.outboundTag) != null ||
            dev.cluvex.zedsecure.domain.config.AutoSelectIds.isAuto(rule.outboundTag) ->
            stringResource(Res.string.rule_outbound_missing)
        rule.outboundTag == RulesetItem.OUTBOUND_DIRECT -> stringResource(Res.string.rule_outbound_direct)
        rule.outboundTag == RulesetItem.OUTBOUND_BLOCK -> stringResource(Res.string.rule_outbound_block)
        else -> stringResource(Res.string.rule_outbound_proxy)
    }
    val summary = buildList {
        rule.domain.take(2).forEach { add(it) }
        rule.ip.take(2).forEach { add(it) }
        if (rule.port.isNotBlank()) add("port:${rule.port}")
        if (rule.network.isNotBlank()) add(rule.network)
        rule.protocol.forEach { add(it) }
        rule.networkType.forEach { add(networkTypeLabel(it)) }
    }.joinToString(", ").ifBlank { stringResource(Res.string.routing_rule_no_match) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(Res.drawable.ic_drag_handle),
                contentDescription = stringResource(Res.string.rule_drag_handle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandle.size(24.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(
                Modifier.weight(1f)

                    .clickable(onClick = onEdit)
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rule.locked) {
                        Icon(
                            painterResource(Res.drawable.ic_lock),
                            contentDescription = stringResource(Res.string.rule_locked),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        rule.remarks.ifBlank { tagLabel },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "$tagLabel · $summary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(
                    painterResource(Res.drawable.ic_keyboard_arrow_down),
                    contentDescription = stringResource(Res.string.rule_move_up),
                    modifier = Modifier.graphicsLayer(rotationZ = 180f),
                )
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(
                    painterResource(Res.drawable.ic_keyboard_arrow_down),
                    contentDescription = stringResource(Res.string.rule_move_down),
                )
            }
            IconButton(onClick = onDelete, enabled = !rule.locked) {
                Icon(
                    painterResource(Res.drawable.ic_delete),
                    contentDescription = stringResource(Res.string.action_delete),
                    tint = if (rule.locked) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RoutingCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}

@Composable
private fun GeoFilesBanner(geo: dev.cluvex.zedsecure.data.assets.GeoAssets, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var present by remember { mutableStateOf(geo.allPresent()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    if (present || !settings.usesGeoFiles()) return
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(Res.string.routing_geo_missing_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                stringResource(if (failed) Res.string.routing_geo_failed else Res.string.routing_geo_missing_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        failed = false
                        scope.launch {
                            val ok = dev.cluvex.zedsecure.data.assets.GeoAutoFetch.fetchNow(geo, settings)
                            present = geo.allPresent()
                            failed = !ok
                            busy = false
                        }
                    },
                ) {
                    Text(stringResource(if (busy) Res.string.routing_geo_downloading else Res.string.routing_geo_download))
                }
            }
        }
    }
}
