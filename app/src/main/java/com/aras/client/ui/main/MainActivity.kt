package com.aras.client.ui.main

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aras.client.AngApplication
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.core.LauncherManager
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.enums.PermissionType
import com.aras.client.extension.toast
import com.aras.client.extension.toastError
import com.aras.client.extension.toastSuccess
import com.aras.client.handler.AngConfigManager
import com.aras.client.handler.ArasExportImportManager
import com.aras.client.handler.MmkvManager
import com.aras.client.handler.SettingsChangeManager
import com.aras.client.handler.SettingsManager
import com.aras.client.ui.AboutActivity
import com.aras.client.ui.backup.BackupActivity
import com.aras.client.ui.base.HelperBaseComponentActivity
import com.aras.client.ui.checkupdate.CheckUpdateActivity
import com.aras.client.ui.export.ArascPasswordDialog
import com.aras.client.ui.export.ExportActivity
import com.aras.client.ui.logcat.LogcatActivity
import com.aras.client.ui.perappproxy.PerAppProxyActivity
import com.aras.client.ui.routing.RoutingSettingActivity
import com.aras.client.ui.server.ProfileEditorResult
import com.aras.client.ui.server.ServerCustomConfigActivity
import com.aras.client.ui.server.ServerGroupActivity
import com.aras.client.ui.server.ServerHttpActivity
import com.aras.client.ui.server.ServerHysteria2Activity
import com.aras.client.ui.server.ServerProxyChainActivity
import com.aras.client.ui.server.ServerShadowsocksActivity
import com.aras.client.ui.server.ServerSocksActivity
import com.aras.client.ui.server.ServerTrojanActivity
import com.aras.client.ui.server.ServerVlessActivity
import com.aras.client.ui.server.ServerVmessActivity
import com.aras.client.ui.server.ServerWireguardActivity
import com.aras.client.ui.settings.SettingsActivity
import com.aras.client.ui.subscription.SubSettingActivity
import com.aras.client.ui.userasset.UserAssetActivity
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startXray()
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService) LauncherManager.restartService(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel.onAction(MainAction.Initialize)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
        maybeImportOpenedArasc(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        maybeImportOpenedArasc(intent)
    }

    /**
     * When the user opens a .arasc file from a file manager, offer to import
     * it straight into the app (password prompt appears if required).
     */
    private fun maybeImportOpenedArasc(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != android.content.Intent.ACTION_VIEW) return
        val type = intent.type ?: ""
        val name = uri.lastPathSegment ?: ""
        val looksArasc = type == "application/x-arasc" || name.endsWith(".arasc", true)
        if (!looksArasc) return
        // Guard against re-handling the same intent after rotation.
        val key = "arasc_opened_$uri"
        if (MmkvManager.decodeSettingsString(key) != null) return
        MmkvManager.encodeSettings(key, "1")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val data = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (data != null) {
                    withContext(Dispatchers.Main) { handleArascAttempt(data, null) }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read opened .arasc file", e)
                withContext(Dispatchers.Main) { toastError(R.string.toast_failure) }
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        var showOnboarding by remember {
            mutableStateOf(!MmkvManager.decodeSettingsBool(AppConfig.PREF_ONBOARDING_DONE, false))
        }
        val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()

        if (showOnboarding) {
            FirstRunSetupDialog(
                onDismiss = {
                    MmkvManager.encodeSettings(AppConfig.PREF_ONBOARDING_DONE, true)
                    showOnboarding = false
                }
            )
        }

        LaunchedEffect(uiState.requestServiceStart) {
            if (uiState.requestServiceStart) {
                mainViewModel.consumeServiceStartRequest()
                if (mainViewModel.uiState.value.isRunning) {
                    LauncherManager.restartService(this@MainActivity)
                } else {
                    requestServiceStart()
                }
            }
        }
        MainScreen(
            mainViewModel = mainViewModel,
            onAction = { action ->
                when (action) {
                    MainAction.ToggleService -> handleFabAction()
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    MainAction.ImportArascFile -> importArascFile()
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> LauncherManager.restartServiceOrStart(this, ::requestServiceStart)
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    is MainAction.ShareTxtFile -> shareAsTxtFile(action.guid)
                    is MainAction.ShareArascFile -> shareAsArascFile(action.guid)
                    MainAction.ExportGroupTxt -> shareTextFile(
                        mainViewModel.exportGroupText(), "aras_subscription.txt"
                    )
                    else -> mainViewModel.onAction(action)
                }
            },
            onNavigate = { route -> navigateTo(route) },
        )

        ArascPasswordDialog(
            show = composeDialogHolder.show,
            error = composeDialogHolder.error,
            onDismiss = {
                composeDialogHolder.show = false
                pendingArascBytes = null
            },
            onConfirm = { password ->
                importArascWithPassword(password)
            }
        )

        composeDialogHolder.note?.let { note ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { composeDialogHolder.note = null },
                title = { androidx.compose.material3.Text(stringResource(R.string.arasc_note_title)) },
                text = { androidx.compose.material3.Text(note) },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { composeDialogHolder.note = null }
                    ) {
                        androidx.compose.material3.Text(stringResource(R.string.action_ok))
                    }
                },
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
            )
        }
    }

    private fun shareToClipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(this, guid) == 0

    private fun shareAsTxtFile(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            shareTextFile(AngConfigManager.getShareText(guid), "aras_config.txt")
        }
    }

    /** Packs a single config into an .arasc container and shares the file. */
    private fun shareAsArascFile(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (ArasExportImportManager.isProtected(guid)) {
                // Data-layer policy: protected configs never leave as raw files.
                withContext(Dispatchers.Main) { toastError(R.string.export_badge_protected) }
                return@launch
            }
            try {
                val bytes = ArasExportImportManager.buildExport(listOf(Triple("", "", listOf(guid))), null)
                val remarks = MmkvManager.decodeServerConfig(guid)?.remarks ?: "config"
                val safeName = remarks.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
                val dir = File(cacheDir, "share").apply { mkdirs() }
                val file = File(dir, "$safeName.arasc")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.cache", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/x-arasc"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    startActivity(
                        Intent.createChooser(intent, getString(R.string.share_method_arasc))
                    )
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to export .arasc", e)
                withContext(Dispatchers.Main) { toastError(R.string.toast_failure) }
            }
        }
    }

    private fun shareTextFile(text: String, fileName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (text.isBlank()) {
                withContext(Dispatchers.Main) { toastError(R.string.toast_failure) }
                return@launch
            }
            try {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                val file = File(dir, fileName)
                file.writeText(text)
                val uri = FileProvider.getUriForFile(
                    this@MainActivity, "$packageName.cache", file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    startActivity(
                        Intent.createChooser(intent, getString(R.string.share_method_txtfile))
                    )
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to export TXT", e)
                withContext(Dispatchers.Main) { toastError(R.string.toast_failure) }
            }
        }
    }

    private fun shareFullContentAsync(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.Export -> Intent(this, ExportActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.Logcat -> Intent(this, LogcatActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
            MainDestination.Promotion -> {
                Utils.openUri(
                    this,
                    "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}"
                )
                return
            }
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        if (mainViewModel.uiState.value.isRunning) {
            LauncherManager.stopService(this)
        } else {
            requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        if (!SettingsManager.isVpnMode()) {
            startXray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startXray() else requestVpnPermission.launch(intent)
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        } else {
            toast(R.string.toast_connect_first_to_test)
        }
    }

    private fun startXray() {
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this)
    }

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
        }
    }

    private fun importConfigLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    mainViewModel.onAction(MainAction.ImportBatchConfig(reader.readText()))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        }
    }

    /** Pending bytes of a Protected .arasc waiting for the password dialog. */
    @Volatile private var pendingArascBytes: ByteArray? = null

    private fun importArascFile() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val data = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (data == null) {
                        withContext(Dispatchers.Main) { toastError(R.string.toast_failure) }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { handleArascAttempt(data, null) }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to read .arasc file", e)
                    withContext(Dispatchers.Main) { toastError(R.string.toast_failure) }
                }
            }
        }
    }

    /** Runs one import attempt with the given password and routes the outcome. */
    private fun handleArascAttempt(bytes: ByteArray, password: CharArray?) {
        LogUtil.i(AppConfig.TAG, "arasc: attempt start, bytes=${bytes.size}, pw=${password != null}")
        lifecycleScope.launch(Dispatchers.IO) {
            when (val attempt = ArasExportImportManager.tryImport(bytes, password)) {
                is ArasExportImportManager.ImportAttempt.NeedPassword -> {
                    pendingArascBytes = bytes
                    withContext(Dispatchers.Main) {
                        composeDialogHolder.error = false
                        composeDialogHolder.show = true
                    }
                }
                is ArasExportImportManager.ImportAttempt.WrongPassword -> {
                    pendingArascBytes = bytes
                    withContext(Dispatchers.Main) {
                        // Keep the dialog open with an error hint so the user
                        // can retry without picking the file again.
                        composeDialogHolder.error = true
                        composeDialogHolder.show = true
                    }
                }
                is ArasExportImportManager.ImportAttempt.Done -> {
                    pendingArascBytes = null
                    withContext(Dispatchers.Main) {
                        composeDialogHolder.show = false
                        mainViewModel.onAction(MainAction.RefreshGroups)
                        toastSuccess(getString(R.string.arasc_import_success, attempt.imported))
                        composeDialogHolder.note = attempt.note
                    }
                }
                is ArasExportImportManager.ImportAttempt.Error -> {
                    pendingArascBytes = null
                    withContext(Dispatchers.Main) {
                        composeDialogHolder.show = false
                        toastError(
                            when (attempt.error) {
                                ArasExportImportManager.ImportError.NOT_ARASC ->
                                    R.string.arasc_err_not_arasc
                                ArasExportImportManager.ImportError.UNSUPPORTED_VERSION ->
                                    R.string.arasc_err_version
                                ArasExportImportManager.ImportError.CORRUPTED ->
                                    R.string.arasc_err_integrity
                                ArasExportImportManager.ImportError.EMPTY ->
                                    R.string.arasc_err_empty
                            }
                        )
                    }
                }
            }
        }
    }

    private fun importArascWithPassword(password: String) {
        val bytes = pendingArascBytes
        if (bytes == null) {
            LogUtil.w(AppConfig.TAG, "arasc: OK tapped with no pending file — dialog dismissed")
            composeDialogHolder.show = false
            return
        }
        // Keep the dialog open during the decryption attempt; it closes on
        // success or re-prompts with an error on wrong password.
        handleArascAttempt(bytes, password.toCharArray())
    }

    /** Compose dialog state exposed from the activity to ScreenContent. */
    private class ArascDialogState {
        var show by androidx.compose.runtime.mutableStateOf(false)
        var error by androidx.compose.runtime.mutableStateOf(false)
        var note by androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    private val composeDialogHolder = ArascDialogState()

    private fun editServer(guid: String, profile: ProfileItem) {
        if (ArasExportImportManager.isProtected(guid)) {
            toastError(R.string.protected_config_hidden)
            return
        }
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            mainViewModel.updateSelectedGuid(guid)
            LauncherManager.restartService(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
