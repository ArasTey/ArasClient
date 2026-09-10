package com.aras.client.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aras.client.R
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.isComplexType
import com.aras.client.ui.compose.AppDropdownMenuItems
import com.aras.client.ui.compose.SelectListDialog

private enum class ImportMenuAction(@StringRes val labelRes: Int, val action: MainAction) {
    QRCode(R.string.menu_item_import_config_qrcode, MainAction.ImportQRcode),
    Clipboard(R.string.menu_item_import_config_clipboard, MainAction.ImportClipboard),
    LocalFile(R.string.menu_item_import_config_local, MainAction.ImportConfigLocal),
    ArascFile(R.string.menu_item_import_config_arasc, MainAction.ImportArascFile),
    Manual(R.string.menu_item_import_config_manually, MainAction.ImportManualMenu),
    PolicyGroup(R.string.menu_item_import_config_policy_group, MainAction.ImportManually(EConfigType.POLICYGROUP.value)),
    ProxyChain(R.string.menu_item_import_config_proxy_chain, MainAction.ImportManually(EConfigType.PROXYCHAIN.value))
}

private enum class ImportManualProtocolMenuAction(@StringRes val labelRes: Int, val action: MainAction) {
    Vmess(R.string.menu_item_import_config_manually_vmess, MainAction.ImportManually(EConfigType.VMESS.value)),
    Vless(R.string.menu_item_import_config_manually_vless, MainAction.ImportManually(EConfigType.VLESS.value)),
    Shadowsocks(R.string.menu_item_import_config_manually_ss, MainAction.ImportManually(EConfigType.SHADOWSOCKS.value)),
    Socks(R.string.menu_item_import_config_manually_socks, MainAction.ImportManually(EConfigType.SOCKS.value)),
    Http(R.string.menu_item_import_config_manually_http, MainAction.ImportManually(EConfigType.HTTP.value)),
    Trojan(R.string.menu_item_import_config_manually_trojan, MainAction.ImportManually(EConfigType.TROJAN.value)),
    WireGuard(R.string.menu_item_import_config_manually_wireguard, MainAction.ImportManually(EConfigType.WIREGUARD.value)),
    Hysteria2(R.string.menu_item_import_config_manually_hysteria2, MainAction.ImportManually(EConfigType.HYSTERIA2.value)),
    AnyTLS(R.string.menu_item_import_config_manually_anytls, MainAction.ImportManually(EConfigType.ANYTLS.value)),
    AmneziaWG(R.string.menu_item_import_config_manually_amneziawg, MainAction.ImportManually(EConfigType.AMNEZIAWG.value))
}

enum class MainMoreMenuAction(@StringRes val labelRes: Int) {
    RestartService(R.string.title_service_restart),
    DeleteAll(R.string.title_del_all_config),
    DeleteDuplicate(R.string.title_del_duplicate_config),
    DeleteInvalid(R.string.title_del_invalid_config),
    ExportAll(R.string.title_export_all),
    ExportSubTxt(R.string.title_export_sub_txt),
    LocateSelected(R.string.title_locate_selected_config),
    SortByTestResults(R.string.title_sort_by_test_results),
    TestAll(R.string.title_ping_all_server),
    TestAllRealPing(R.string.title_real_ping_all_server),
    UpdateSubscriptions(R.string.title_sub_update)
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
): List<ServerMenuAction> = ServerMenuAction.entries.filter { action ->
    // Protected configs: only test/delete are allowed — no share, no edit,
    // no full-content view. Enforced here so every entry point is covered.
    if (isProtected && (action.isShareAction || action == ServerMenuAction.Edit)) return@filter false
    (includeManagementActions || action.isShareAction) && (!isComplexProfile || action.supportsComplexProfiles)
}

@Composable
fun ImportMenuContent(onAction: (MainAction) -> Unit) {
    var showProtocolMenu by remember { mutableStateOf(false) }

    ImportMenuAction.entries.forEach { item ->
        if (item == ImportMenuAction.Manual) {
            // Submenu hint: arrow so users see this opens a second menu.
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(item.labelRes))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "❯",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                onClick = { showProtocolMenu = true }
            )
        } else {
            DropdownMenuItem(
                text = { Text(stringResource(item.labelRes)) },
                onClick = { onAction(item.action) }
            )
        }
    }

    if (showProtocolMenu) {
        // Second-stage menu: pick a protocol to create manually.
        SelectListDialog(
            options = ImportManualProtocolMenuAction.entries,
            optionText = { stringResource(it.labelRes) },
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
fun MoreMenuContent(onSelected: (MainMoreMenuAction) -> Unit) = AppDropdownMenuItems(
    items = MainMoreMenuAction.entries,
    labelRes = { it.labelRes },
    onSelected = onSelected
)

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
    )
    SelectListDialog(
        options = menuActions,
        optionText = { stringResource(it.labelRes) },
        onSelected = { action ->
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
        },
        onDismiss = onDismiss
    )
}
