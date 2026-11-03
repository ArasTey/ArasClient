package com.aras.client.ui.rdrn

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Hexagon
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aras.client.R
import com.aras.client.ui.main.MainAction

@Composable
fun RnConfigsScreen(
    servers: List<RnServer>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onTest: (String) -> Unit,
    onAction: (MainAction) -> Unit,
    onOpenManager: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var protocol by rememberSaveable { mutableStateOf("All") }
    var sortByPing by rememberSaveable { mutableStateOf(false) }
    val protocols = remember(servers) {
        listOf("All") + servers.groupingBy { it.protocol }.eachCount().entries.sortedByDescending { it.value }.take(5).map { it.key }
    }
    val rows = remember(servers, query, protocol, sortByPing) {
        servers.asSequence()
            .filter { protocol == "All" || it.protocol == protocol }
            .filter { query.isBlank() || it.name.contains(query, true) || it.host.contains(query, true) || it.protocol.contains(query, true) }
            .let { seq -> if (sortByPing) seq.sortedBy { s -> s.pingMs?.takeIf { it > 0 } ?: Int.MAX_VALUE } else seq }
            .toList()
    }
    var addOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp)) {
        RnHeader("Radar Config", onMenu, Modifier.rnRise(0)) {
            RnIconButton(Icons.Rounded.Tune, "Manage configs", onOpenManager)
            Box {
                RnIconButton(Icons.Rounded.Add, "Add config", { addOpen = true }, tint = RnColors.Green)
                DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                    listOf(
                        R.string.menu_item_import_config_qrcode to MainAction.ImportQRcode,
                        R.string.menu_item_import_config_clipboard to MainAction.ImportClipboard,
                        R.string.menu_item_import_config_local to MainAction.ImportConfigLocal,
                        R.string.menu_item_import_config_arasc to MainAction.ImportArascFile,
                        R.string.menu_item_import_config_manually to MainAction.ImportManualMenu,
                        R.string.title_sub_update to MainAction.UpdateSubscriptions,
                    ).forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { addOpen = false; onAction(action) })
                    }
                    DropdownMenuItem(
                        text = { Text("تعمیرگاه کانفیگ") },
                        onClick = { addOpen = false; onAction(MainAction.OpenConfigRepair) }
                    )
                }
            }
        }
        RnSearchField(query, { query = it }, "Search configs...", Modifier.rnRise(1))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().rnRise(2), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(protocols) { p -> RnChip(p, protocol == p, { protocol = p }) }
            }
            RnIconButton(Icons.Rounded.FilterList, "Sort by ping", { sortByPing = !sortByPing }, tint = if (sortByPing) RnColors.Green else Color.White)
        }
        Spacer(Modifier.height(10.dp))
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().rnCard().padding(16.dp)) {
                Text(
                    if (servers.isEmpty()) "No configs yet. Tap + to import from QR, clipboard, a file or a subscription." else "Nothing matches your search.",
                    color = RnColors.Muted, fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(rows, key = { _, it -> it.id }) { index, s ->
                    ConfigRow(s, s.id == selectedId, { onSelect(s.id) }, { onTest(s.id) }, Modifier.rnRise(3 + index))
                }
            }
        }
    }
}

private fun tileColors(protocol: String): Pair<Color, Color> = when (protocol) {
    "VLESS" -> Color(0xFF2A6DF4) to Color(0xFFF08A24)
    "VMess" -> Color(0xFFE23A3A) to Color(0xFF2B4EA8)
    "Shadowsocks" -> Color(0xFF1F5FBF) to Color(0xFF3AB0FF)
    "Trojan" -> Color(0xFF1F5FBF) to Color(0xFF2F8CFF)
    else -> Color(0xFFD8332B) to Color(0xFF7A1A14)
}

private fun tileIcon(protocol: String): ImageVector = when (protocol) {
    "Shadowsocks" -> Icons.Rounded.Shield
    "Trojan" -> Icons.Rounded.Hexagon
    "VLESS", "VMess" -> Icons.AutoMirrored.Rounded.Send
    else -> Icons.Rounded.Public
}

@Composable
private fun ConfigRow(s: RnServer, selected: Boolean, onClick: () -> Unit, onTest: () -> Unit, modifier: Modifier) {
    val (c1, c2) = tileColors(s.protocol)
    Row(
        modifier.fillMaxWidth()
            .rnCard(tint = RnColors.Gold, borderAlpha = if (selected) 1f else 0.35f)
            .clickable(onClick = onClick).padding(start = 12.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(c1, c2))), contentAlignment = Alignment.Center) {
            Icon(tileIcon(s.protocol), null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(s.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ping = s.pingMs
            Text(
                "${s.flag} ${s.protocol} • ${s.port}" + if (ping != null && ping > 0) " • $ping ms" else "",
                color = Color(0xFFA9BDB0), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        RnBadge(RnUtil.pingBadge(s.pingMs), good = (s.pingMs ?: -1) in 1..249)
        RnIconButton(Icons.Rounded.Bolt, "Test ping", onTest, tint = RnColors.Gold)
    }
}

@Composable
fun RnCountryScreen(
    servers: List<RnServer>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var region by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(servers, region, query) {
        servers.groupBy { it.iso }
            .map { (iso, list) ->
                val best = list.filter { (it.pingMs ?: -1) > 0 }.minByOrNull { it.pingMs!! } ?: list.first()
                CountryGroup(iso, RnUtil.countryName(iso), list.first().flag, list.size, best)
            }
            .filter { region == "All" || RnUtil.regionOf(it.iso) == region }
            .filter { query.isBlank() || it.name.contains(query, true) }
            .sortedWith(compareBy({ it.best.pingMs?.takeIf { p -> p > 0 } ?: Int.MAX_VALUE }, { it.name }))
    }
    Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp)) {
        RnHeader("Country", onMenu, Modifier.rnRise(0))
        Row(Modifier.fillMaxWidth().rnRise(1), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Asia", "Europe", "America").forEach { r -> RnChip(r, region == r, { region = r }, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(10.dp))
        RnSearchField(query, { query = it }, "Search country...", Modifier.rnRise(2))
        Spacer(Modifier.height(10.dp))
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxWidth().rnCard().padding(16.dp)) {
                Text(if (servers.isEmpty()) "No configs yet. Import some in the Configs tab." else "No country matches.", color = RnColors.Muted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(groups, key = { _, it -> it.iso.ifBlank { "other" } }) { index, g ->
                    val ping = g.best.pingMs
                    Row(
                        Modifier.fillMaxWidth().rnRise(3 + index)
                            .rnCard(borderAlpha = if (g.best.id == selectedId) 1f else 0.35f)
                            .clickable { onSelect(g.best.id) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RnFlag(g.flag)
                        Column(Modifier.weight(1f)) {
                            Text(g.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${g.count} config${if (g.count == 1) "" else "s"}" + if (ping != null && ping > 0) " • best $ping ms" else "",
                                color = Color(0xFFA9BDB0), fontSize = 11.5.sp,
                            )
                        }
                        RnBars(rnPingLevel(ping), rnPingColor(ping))
                    }
                }
            }
        }
    }
}

private data class CountryGroup(val iso: String, val name: String, val flag: String, val count: Int, val best: RnServer)
