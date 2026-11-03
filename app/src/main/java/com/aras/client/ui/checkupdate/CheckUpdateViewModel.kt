package com.aras.client.ui.checkupdate

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.CheckUpdateResult
import com.aras.client.handler.DeltaPatch
import com.aras.client.handler.MmkvManager
import com.aras.client.handler.UpdateCheckerManager
import com.aras.client.handler.UpdateDownloader
import com.aras.client.handler.UpdateInstaller
import com.aras.client.ui.base.BaseViewModel
import com.aras.client.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState
    data class Downloading(
        val bytesRead: Long,
        val totalBytes: Long,
        val delta: Boolean = false
    ) : UpdateInstallState

    data class Applying(val bytesProcessed: Long, val totalBytes: Long) : UpdateInstallState
    data object Verifying : UpdateInstallState
    data class NeedsPermission(val file: File) : UpdateInstallState
}

class CheckUpdateViewModel(application: Application) : BaseViewModel(application) {

    private val _checkPreRelease = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
    )
    val checkPreRelease: StateFlow<Boolean> = _checkPreRelease.asStateFlow()

    private val _updateResult = MutableStateFlow<CheckUpdateResult?>(null)
    val updateResult: StateFlow<CheckUpdateResult?> = _updateResult.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _installState = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Idle)
    val installState: StateFlow<UpdateInstallState> = _installState.asStateFlow()

    private var updateJob: Job? = null

    fun toggleCheckPreRelease(enabled: Boolean) {
        _checkPreRelease.value = enabled
        MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, enabled)
    }

    fun checkForUpdates() {
        launchLoading {
            toast(R.string.update_checking_for_update)
            try {
                val (installedVersion, installedAbi) = withContext(Dispatchers.IO) {
                    UpdateInstaller.installedVersion(app) to UpdateInstaller.installedApkAbi(app)
                }
                val result = UpdateCheckerManager.checkForUpdate(
                    includePreRelease = _checkPreRelease.value,
                    installedVersion = installedVersion,
                    installedAbi = installedAbi
                )
                if (result.hasUpdate) {
                    _installState.value = UpdateInstallState.Idle
                    _updateResult.value = result
                    _showUpdateDialog.value = true
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to check for updates", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun startUpdate() {
        val result = _updateResult.value ?: return
        if (result.downloadUrl.isNullOrBlank()) {
            toastError(R.string.update_no_compatible_apk)
            return
        }
        if (_installState.value !is UpdateInstallState.Idle) return

        updateJob = viewModelScope.launch {
            _installState.value = UpdateInstallState.Downloading(
                bytesRead = 0L,
                totalBytes = -1L,
                delta = canUseDelta(result)
            )
            try {
                val file = downloadUpdate(result)
                _installState.value = UpdateInstallState.Idle
                launchInstaller(file)
            } catch (e: CancellationException) {
                _installState.value = UpdateInstallState.Idle
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to download update", e)
                _installState.value = UpdateInstallState.Idle
                toastError(R.string.update_download_failed)
            }
        }
    }

    private suspend fun downloadUpdate(result: CheckUpdateResult): File {
        if (canUseDelta(result)) {
            val patched = try {
                applyDelta(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Delta update failed, downloading the full APK", e)
                null
            }
            if (patched != null) return patched
        }
        return downloadFull(result)
    }

    private fun canUseDelta(result: CheckUpdateResult): Boolean =
        result.patchUrl != null &&
            result.patchFileName != null &&
            result.patchSha256 != null &&
            result.downloadSha256 != null

    private suspend fun applyDelta(result: CheckUpdateResult): File {
        val patchFile = UpdateDownloader.download(
            context = app,
            url = result.patchUrl!!,
            fileName = result.patchFileName!!,
            expectedSha256 = result.patchSha256,
            onProgress = { bytesRead, totalBytes ->
                _installState.value =
                    UpdateInstallState.Downloading(bytesRead, totalBytes, delta = true)
            }
        )
        try {
            val sourceApk = File(app.applicationInfo.sourceDir)
            val target = File(patchFile.parentFile ?: app.cacheDir, "update.apk")
            _installState.value = UpdateInstallState.Applying(0L, 0L)
            DeltaPatch.apply(sourceApk, patchFile, target) { bytesProcessed, totalBytes ->
                _installState.value = UpdateInstallState.Applying(bytesProcessed, totalBytes)
            }
            _installState.value = UpdateInstallState.Verifying
            if (!UpdateDownloader.hasSha256(target, result.downloadSha256!!)) {
                target.delete()
                throw IOException("Patched APK does not match the published checksum")
            }
            if (!UpdateInstaller.isUpdateApk(app, target)) {
                target.delete()
                throw IOException("Patched file is not a valid update APK")
            }
            return target
        } finally {
            patchFile.delete()
        }
    }

    private suspend fun downloadFull(result: CheckUpdateResult): File {
        _installState.value = UpdateInstallState.Downloading(0L, -1L, delta = false)
        val file = UpdateDownloader.download(
            context = app,
            url = result.downloadUrl!!,
            fileName = result.downloadFileName ?: "update.apk",
            expectedSha256 = result.downloadSha256,
            onProgress = { bytesRead, totalBytes ->
                _installState.value =
                    UpdateInstallState.Downloading(bytesRead, totalBytes, delta = false)
            },
            onVerifying = {
                _installState.value = UpdateInstallState.Verifying
            }
        )
        if (!UpdateInstaller.isUpdateApk(app, file)) {
            file.delete()
            throw IllegalStateException("Downloaded file is not a valid update APK")
        }
        return file
    }

    fun cancelUpdate() {
        updateJob?.cancel()
        updateJob = null
        val state = _installState.value
        if (state is UpdateInstallState.NeedsPermission) {
            state.file.delete()
        }
        _installState.value = UpdateInstallState.Idle
        _showUpdateDialog.value = false
    }

    fun openInstallPermissionSettings() {
        UpdateInstaller.openInstallPermissionSettings(app)
    }

    /** Called when the activity resumes; installs a pending update once permission is granted. */
    fun onResume() {
        val state = _installState.value as? UpdateInstallState.NeedsPermission ?: return
        if (UpdateInstaller.canInstallPackages(app)) {
            _installState.value = UpdateInstallState.Idle
            _showUpdateDialog.value = false
            launchInstaller(state.file)
        }
    }

    fun dismissUpdateDialog() {
        if (_installState.value !is UpdateInstallState.Idle) return
        _showUpdateDialog.value = false
    }

    private fun launchInstaller(file: File) {
        if (!UpdateInstaller.canInstallPackages(app)) {
            _installState.value = UpdateInstallState.NeedsPermission(file)
            return
        }
        _showUpdateDialog.value = false
        if (!UpdateInstaller.install(app, file)) {
            _installState.value = UpdateInstallState.Idle
            toastError(R.string.update_install_failed)
        }
    }
}
