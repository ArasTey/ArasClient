package com.aras.client.ui.checkupdate

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aras.client.BuildConfig
import com.aras.client.R
import com.aras.client.extension.withLatinDigits
import com.aras.client.core.CoreNativeManager
import com.aras.client.ui.base.BaseComponentActivity
import com.aras.client.ui.compose.AppTopBar
import com.aras.client.ui.compose.NavigationBarsSpacer
import com.aras.client.ui.compose.SettingsMenuItem
import com.aras.client.ui.compose.SettingsSwitchItem
import com.aras.client.ui.compose.VersionInfoBlock
import com.aras.client.ui.compose.formatBytes
import com.aras.client.ui.compose.verticalScrollbar

class CheckUpdateActivity : BaseComponentActivity() {

    private val viewModel: CheckUpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            viewModel.checkForUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    @Composable
    override fun ScreenContent() {
        CheckUpdateScreen(viewModel = viewModel, onBackClick = { finish() })
    }
}

@Composable
fun CheckUpdateScreen(
    viewModel: CheckUpdateViewModel,
    onBackClick: () -> Unit
) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val checkPreRelease by viewModel.checkPreRelease.collectAsStateWithLifecycle()
    val showUpdateDialog by viewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val updateResult by viewModel.updateResult.collectAsStateWithLifecycle()
    val installState by viewModel.installState.collectAsStateWithLifecycle()

    val libVersion = CoreNativeManager.getLibVersion()
    val versionText = "v${BuildConfig.VERSION_NAME} ($libVersion)"

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.update_check_for_update),
                onBackClick = onBackClick,
                isLoading = isLoading
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSwitchItem(
                icon = painterResource(R.drawable.ic_source_code_24dp),
                title = stringResource(R.string.update_check_pre_release),
                checked = checkPreRelease,
                onCheckedChange = { viewModel.toggleCheckPreRelease(it) }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_check_update_24dp),
                title = stringResource(R.string.update_check_for_update),
                onClick = { viewModel.checkForUpdates() }
            )
            VersionInfoBlock(versionText = versionText)
            NavigationBarsSpacer()
        }
    }

    if (showUpdateDialog && updateResult != null) {
        val result = updateResult!!
        val downloading = installState as? UpdateInstallState.Downloading
        val applying = installState as? UpdateInstallState.Applying
        val verifying = installState == UpdateInstallState.Verifying
        val needsPermission = installState is UpdateInstallState.NeedsPermission
        val installing = downloading != null || applying != null || verifying

        AlertDialog(
            onDismissRequest = {
                if (needsPermission) viewModel.cancelUpdate() else viewModel.dismissUpdateDialog()
            },
            title = {
                Text(
                    if (needsPermission) {
                        stringResource(R.string.update_install_permission_title)
                    } else {
                        stringResource(R.string.update_new_version_found, result.latestVersion ?: "")
                    }
                )
            },
            text = {
                when {
                    needsPermission -> {
                        Text(stringResource(R.string.update_install_permission_message))
                    }

                    downloading != null -> {
                        UpdateProgress(
                            label = stringResource(
                                if (downloading.delta) {
                                    R.string.update_downloading_changes
                                } else {
                                    R.string.update_downloading
                                }
                            ),
                            bytesRead = downloading.bytesRead,
                            totalBytes = downloading.totalBytes
                        )
                    }

                    applying != null -> {
                        UpdateProgress(
                            label = stringResource(R.string.update_applying),
                            bytesRead = applying.bytesProcessed,
                            totalBytes = applying.totalBytes
                        )
                    }

                    verifying -> {
                        Column {
                            Text(stringResource(R.string.update_verifying))
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    else -> {
                        ReleaseNotesView(notes = result.releaseNotes.orEmpty())
                    }
                }
            },
            confirmButton = {
                when {
                    needsPermission -> {
                        TextButton(onClick = { viewModel.openInstallPermissionSettings() }) {
                            Text(stringResource(R.string.update_open_settings))
                        }
                    }

                    installing -> {
                    }

                    else -> {
                        TextButton(onClick = { viewModel.startUpdate() }) {
                            Text(stringResource(R.string.update_now))
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (installing || needsPermission) viewModel.cancelUpdate() else viewModel.dismissUpdateDialog()
                    }
                ) {
                    Text(
                        if (needsPermission) {
                            stringResource(R.string.update_not_now)
                        } else {
                            stringResource(R.string.action_cancel)
                        }
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun UpdateProgress(label: String, bytesRead: Long, totalBytes: Long) {
    Column {
        Text(label)
        Spacer(Modifier.height(12.dp))
        val knownTotal = totalBytes > 0
        val fraction = if (knownTotal) {
            (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }
        if (knownTotal) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        val progressText = if (knownTotal) {
            stringResource(
                R.string.update_download_progress,
                (fraction * 100).toInt(),
                formatBytes(bytesRead),
                formatBytes(totalBytes)
            ).withLatinDigits()
        } else {
            formatBytes(bytesRead)
        }
        // LRM keeps "2% · 1.6 MB / 69.8 MB" in reading order under RTL.
        Text(
            text = "\u200E$progressText\u200E",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
