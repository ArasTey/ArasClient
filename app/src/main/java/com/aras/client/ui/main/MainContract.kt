package com.aras.client.ui.main

import com.aras.client.dto.ConnectionTestResult
import com.aras.client.dto.GroupMapItem
import com.aras.client.dto.LocateTarget

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
}

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    // A start was requested and the daemon has not reported success or failure yet.
    // Long tunnels (Tor) need this: without a visible sign of work the app reads as
    // hung, and tapping again was the only feedback the user could get.
    val isConnecting: Boolean = false,
    val isTesting: Boolean = false,
    // Which configs the running round measures. A card must not pulse just because
    // a test happens to be running elsewhere; only its own round counts.
    val testingGuids: Set<String> = emptySet(),
    val status: MainStatus = MainStatus.Disconnected,
    // Presentation only: hiding a message must not change connection or test state.
    val statusMessageVisible: Boolean = false,
    // What the running session leaves through. Kept apart from `status` so the
    // five-second confirmation can clear without taking the egress line with it.
    val egressCountry: String? = null,
    val egressIp: String? = null,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val showSmartConnect: Boolean = true,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    val requestServiceStart: Boolean = false,
    val scrollToTopTick: Int = 0
)

internal fun MainUiState.withoutServerStatusMessage(): MainUiState = copy(
    statusMessageVisible = false,
    status = if (status is MainStatus.ConnectionTest || (status == MainStatus.Testing && !isTesting)) {
        if (isRunning) MainStatus.Connected else MainStatus.Disconnected
    } else status
)

internal data class MainBarText(
    val title: String,
    val subtitle: String,
    val subtitleIp: String = ""
)

/**
 * A full IPv6 address is wide enough to claim the whole bar row and squeeze the
 * country name to an ellipsis, so long addresses keep both ends and drop the
 * middle groups. IPv4 and short addresses pass through unchanged.
 */
internal fun compactAddress(address: String, maxGroups: Int = 4): String {
    val trimmed = address.trim()
    if (':' !in trimmed) return trimmed
    val groups = trimmed.split(':')
    if (groups.size <= maxGroups) return trimmed
    return "${groups.take(2).joinToString(":")}:…:${groups.takeLast(2).joinToString(":")}"
}

/**
 * Empty subtitle is intentional: never fall back to the connected guidance after dismissal.
 * While a session runs, the egress country and address stay in the subtitle past the
 * five-second confirmation; before this they were part of the transient message and the
 * bar lost them, which read as the flag and IP vanishing on their own.
 */
internal fun mainBarText(
    state: MainUiState,
    formattedStatus: String,
    connectedTitle: String,
    egressCountryName: String = ""
): MainBarText {
    val country = egressCountryName.takeIf { it.isNotBlank() }.orEmpty()
    val ip = state.egressIp?.takeIf { it.isNotBlank() }.orEmpty()
    val egress = country.isNotEmpty() || ip.isNotEmpty()
    return when (state.status) {
        MainStatus.Connected -> MainBarText(
            connectedTitle,
            when {
                egress -> country
                state.statusMessageVisible -> formattedStatus.replace('\n', ' ')
                else -> ""
            },
            if (egress) ip else ""
        )
        is MainStatus.ConnectionTest -> if (state.isRunning) {
            MainBarText(
                // The confirmation owns the title while it is fresh, then the config
                // name comes back with the egress line still under it.
                if (state.statusMessageVisible) formattedStatus.replace('\n', ' ') else connectedTitle,
                country,
                ip
            )
        } else {
            MainBarText(
                formattedStatus,
                if (state.statusMessageVisible) state.status.result.ipAddress.orEmpty() else ""
            )
        }
        else -> MainBarText(formattedStatus, "")
    }
}

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object PingSelectedSubscription : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object SmartConnect : MainAction
    data class SingleTestServer(val guid: String) : MainAction
    data class SortSubscriptionByTestResults(val groupId: String) : MainAction
    data object ExportAll : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data object ImportArascFile : MainAction
    data object ImportManualMenu : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.aras.client.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data class ShareTxtFile(val guid: String) : MainAction
    data class ShareArascFile(val guid: String) : MainAction
    data object ExportGroupTxt : MainAction
    data object DismissQRCodeDialog : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data object LocateHandled : MainAction
}
