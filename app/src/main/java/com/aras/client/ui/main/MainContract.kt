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
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    val requestServiceStart: Boolean = false,
    val scrollToTopTick: Int = 0
)

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object SmartConnect : MainAction
    data class SingleTestServer(val guid: String) : MainAction
    data object UpdateSubscriptions : MainAction
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
