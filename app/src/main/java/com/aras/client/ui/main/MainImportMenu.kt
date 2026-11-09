package com.aras.client.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aras.client.R
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.isComplexType
import com.aras.client.handler.FreeTrashManager
import com.aras.client.ui.compose.GlassMenuDivider
import com.aras.client.ui.compose.GlassMenuRow
import com.aras.client.ui.compose.GlassPalette
import com.aras.client.ui.compose.LocalGlassPalette
import com.aras.client.ui.compose.SelectListDialog
import com.aras.client.ui.compose.glassPanel

private enum class ImportMenuAction(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val action: MainAction
) {
    QRCode(R.string.menu_item_import_config_qrcode, R.drawable.ic_qu_scan_24dp, MainAction.ImportQRcode),
    Clipboard(R.string.menu_item_import_config_clipboard, R.drawable.ic_copy, MainAction.ImportClipboard),
    LocalFile(R.string.menu_item_import_config_local, R.drawable.ic_file_24dp, MainAction.ImportConfigLocal),
    ArascFile(R.string.menu_item_import_config_arasc, R.drawable.ic_description_24dp, MainAction.ImportArascFile),
    // Deliberately below the .arasc entry rather than inside the "Add manually"
    // protocol submenu.
    Aether(R.string.menu_item_import_config_manually_aether, R.drawable.ic_flash_on_24dp, MainAction.ImportManually(EConfigType.AETHER.value)),
    // Psiphon and Tor without the Aether tunnel: the core runs the carrier alone.
    Psiphon(R.string.menu_item_import_config_manually_psiphon, R.drawable.ic_privacy_24dp, MainAction.ImportManually(EConfigType.PSIPHON.value)),
    Tor(R.string.menu_item_import_config_manually_tor, R.drawable.ic_routing_24dp, MainAction.ImportManually(EConfigType.TOR.value)),
    Manual(R.string.menu_item_import_config_manually, R.drawable.ic_add_24dp, MainAction.ImportManualMenu),
    PolicyGroup(R.string.menu_item_import_config_policy_group, R.drawable.ic_link_24dp, MainAction.ImportManually(EConfigType.POLICYGROUP.value)),
    ProxyChain(R.string.menu_item_import_config_proxy_chain, R.drawable.ic_routing_24dp, MainAction.ImportManually(EConfigType.PROXYCHAIN.value))
}

// Protocol names are proper nouns: the submenu is already titled "Add manually",
// so each row only needs the bare name.
private enum class ImportManualProtocolMenuAction(val label: String, val action: MainAction) {
    Vmess("VMess", MainAction.ImportManually(EConfigType.VMESS.value)),
    Vless("VLESS", MainAction.ImportManually(EConfigType.VLESS.value)),
    Shadowsocks("Shadowsocks", MainAction.ImportManually(EConfigType.SHADOWSOCKS.value)),
    Socks("SOCKS", MainAction.ImportManually(EConfigType.SOCKS.value)),
    Http("HTTP", MainAction.ImportManually(EConfigType.HTTP.value)),
    Trojan("Trojan", MainAction.ImportManually(EConfigType.TROJAN.value)),
    WireGuard("WireGuard", MainAction.ImportManually(EConfigType.WIREGUARD.value)),
    Hysteria2("Hysteria2", MainAction.ImportManually(EConfigType.HYSTERIA2.value)),
    AnyTLS("AnyTLS", MainAction.ImportManually(EConfigType.ANYTLS.value)),
    AmneziaWG("AmneziaWG", MainAction.ImportManually(EConfigType.AMNEZIAWG.value)),
    Mieru("Mieru", MainAction.ImportManually(EConfigType.MIERU.value))
}

enum class MainMoreMenuAction(@StringRes val labelRes: Int) {
    RestartService(R.string.title_service_restart),
    DeleteAll(R.string.title_del_all_config),
    DeleteDuplicate(R.string.title_del_duplicate_config),
    DeleteInvalid(R.string.title_del_invalid_config),
    RestoreFreeConfigs(R.string.menu_more_restore_free_configs),
    ExportAll(R.string.title_export_all),
    ExportSubTxt(R.string.title_export_sub_txt),
    LocateSelected(R.string.title_locate_selected_config),
    SortByTestResults(R.string.title_sort_by_test_results),
    TestAll(R.string.title_ping_all_server)
}

internal enum class ServerMenuAction(
    @StringRes val labelRes: Int,
    val isShareAction: Boolean,
    val supportsComplexProfiles: Boolean,
) {
    ShareQRCode(R.string.share_method_qrcode, isShareAction = true, supportsComplexProfiles = false),
    ShareClipboard(R.string.share_method_clipboard, isShareAction = true, supportsComplexProfiles = false),
    ShareFullContent(R.string.share_method_full_content, isShareAction = true, supportsComplexProfiles = true),
    ShareTxtFile(R.string.share_method_txtfile, isShareAction = true, supportsComplexProfiles = false),
    ShareArasc(R.string.share_method_arasc, isShareAction = true, supportsComplexProfiles = false),
    Edit(R.string.action_edit, isShareAction = false, supportsComplexProfiles = true),
    Delete(R.string.action_delete, isShareAction = false, supportsComplexProfiles = true),
}

internal fun serverMenuActions(
    isComplexProfile: Boolean,
    includeManagementActions: Boolean,
    isProtected: Boolean = false,
    editable: Boolean = true,
): List<ServerMenuAction> = ServerMenuAction.entries.filter { action ->
    // Protected configs: only test/delete are allowed — no share, no edit,
    // no full-content view. Enforced here so every entry point is covered.
    if (isProtected && (action.isShareAction || action == ServerMenuAction.Edit)) return@filter false
    // Import-only protocols have no editor; offering one would open a screen that
    // saves the profile back as a different protocol.
    if (!editable && action == ServerMenuAction.Edit) return@filter false
    (includeManagementActions || action.isShareAction) && (!isComplexProfile || action.supportsComplexProfiles)
}

@Composable
fun ImportMenuContent(onAction: (MainAction) -> Unit) {
    var showProtocolMenu by remember { mutableStateOf(false) }

    ImportMenuAction.entries.forEachIndexed { index, item ->
        GlassMenuRow(
            iconRes = item.iconRes,
            label = stringResource(item.labelRes),
            showsSubmenu = item == ImportMenuAction.Manual,
            onClick = {
                if (item == ImportMenuAction.Manual) showProtocolMenu = true
                else onAction(item.action)
            }
        )
        if (index == 3 || item == ImportMenuAction.Manual) GlassMenuDivider()
    }

    if (showProtocolMenu) {
        // Second-stage menu: pick a protocol to create manually.
        SelectListDialog(
            options = ImportManualProtocolMenuAction.entries,
            optionText = { it.label },
            onSelected = { selected ->
                showProtocolMenu = false
                onAction(selected.action)
            },
            onDismiss = { showProtocolMenu = false },
            title = stringResource(R.string.menu_item_import_config_manually)
        )
    }
}

@Composable
fun MoreMenuContent(onSelected: (MainMoreMenuAction) -> Unit) {
    // The restore item only lists when trashed Free configs exist.
    MainMoreMenuAction.entries
        .filter {
            it != MainMoreMenuAction.RestoreFreeConfigs || FreeTrashManager.hasTrash()
        }
        .forEach { action ->
            GlassMenuRow(
                iconRes = action.iconRes(),
                label = stringResource(action.labelRes),
                onClick = { onSelected(action) }
            )
            if (action == MainMoreMenuAction.RestartService ||
                action == MainMoreMenuAction.RestoreFreeConfigs ||
                action == MainMoreMenuAction.ExportSubTxt
            ) {
                GlassMenuDivider()
            }
        }
}

@DrawableRes
private fun MainMoreMenuAction.iconRes(): Int = when (this) {
    MainMoreMenuAction.RestartService -> R.drawable.ic_restore_24dp
    MainMoreMenuAction.RestoreFreeConfigs -> R.drawable.ic_restore_24dp
    MainMoreMenuAction.DeleteAll -> R.drawable.ic_delete_24dp
    MainMoreMenuAction.DeleteDuplicate -> R.drawable.ic_copy
    MainMoreMenuAction.DeleteInvalid -> R.drawable.ic_outline_filter_alt_24
    MainMoreMenuAction.ExportAll -> R.drawable.ic_save_24dp
    MainMoreMenuAction.ExportSubTxt -> R.drawable.ic_description_24dp
    MainMoreMenuAction.LocateSelected -> R.drawable.ic_search_24dp
    MainMoreMenuAction.SortByTestResults -> R.drawable.ic_outline_filter_alt_24
    MainMoreMenuAction.TestAll -> R.drawable.ic_speedtest_24dp
}

@DrawableRes
private fun ServerMenuAction.iconRes(): Int = when (this) {
    ServerMenuAction.ShareQRCode -> R.drawable.ic_qu_scan_24dp
    ServerMenuAction.ShareClipboard -> R.drawable.ic_copy
    ServerMenuAction.ShareFullContent -> R.drawable.ic_description_24dp
    ServerMenuAction.ShareTxtFile -> R.drawable.ic_save_24dp
    ServerMenuAction.ShareArasc -> R.drawable.ic_file_24dp
    ServerMenuAction.Edit -> R.drawable.ic_edit_24dp
    ServerMenuAction.Delete -> R.drawable.ic_delete_24dp
}

private fun ServerMenuAction.tint(palette: GlassPalette): Color = when (this) {
    // One accent for every share path; only delete carries the danger hue.
    ServerMenuAction.Delete -> palette.danger
    else -> palette.accent
}

@Composable
fun ShareMethodDialog(
    guid: String,
    profile: ProfileItem,
    more: Boolean,
    onDismiss: () -> Unit,
    onAction: (MainAction) -> Unit,
    onRemove: (String) -> Unit,
) {
    val menuActions = serverMenuActions(
        isComplexProfile = profile.configType.isComplexType(),
        includeManagementActions = more,
        isProtected = com.aras.client.handler.ArasExportImportManager.isProtected(guid),
        editable = profile.configType.hasEditor,
    )
    val palette = LocalGlassPalette.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(
                    palette = palette,
                    shape = RoundedCornerShape(24.dp),
                    fill = if (palette.isDark) Color(0xF7161D24) else Color(0xF8FFFFFF),
                    borderColor = if (palette.isDark) Color(0x52FFFFFF) else Color(0xE6FFFFFF)
                )
                .padding(horizontal = 6.dp, vertical = 8.dp)
        ) {
            menuActions.forEach { action ->
                GlassMenuRow(
                    iconRes = action.iconRes(),
                    label = stringResource(action.labelRes),
                    tint = action.tint(palette),
                    onClick = {
                        onDismiss()
                        when (action) {
                            ServerMenuAction.ShareQRCode -> onAction(MainAction.ShareQRCode(guid))
                            ServerMenuAction.ShareClipboard -> onAction(MainAction.ShareClipboard(guid))
                            ServerMenuAction.ShareFullContent -> onAction(MainAction.ShareFullContent(guid))
                            ServerMenuAction.ShareTxtFile -> onAction(MainAction.ShareTxtFile(guid))
                            ServerMenuAction.ShareArasc -> onAction(MainAction.ShareArascFile(guid))
                            ServerMenuAction.Edit -> onAction(MainAction.EditServer(guid, profile))
                            ServerMenuAction.Delete -> onRemove(guid)
                        }
                    }
                )
            }
        }
    }
}
