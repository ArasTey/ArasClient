package com.aras.client.ui.export

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.handler.ArasExportImportManager
import com.aras.client.handler.MmkvManager
import com.aras.client.util.LogUtil
import com.aras.client.extension.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExportType(@StringRes val labelRes: Int) {
    NORMAL(R.string.export_type_normal),
    PROTECTED(R.string.export_type_protected),
}

data class ExportConfigEntry(
    val guid: String,
    val remarks: String,
    val isProtected: Boolean,
)

data class ExportGroup(
    val subscriptionId: String,
    val title: String,
    val configs: List<ExportConfigEntry>,
    val selectedGuids: Set<String> = emptySet(),
)

data class ExportUiState(
    val groups: List<ExportGroup> = emptyList(),
    val expandedGroups: Set<String> = emptySet(),
    val exportType: ExportType = ExportType.NORMAL,
    val password: String = "",
    val confirmPassword: String = "",
    val isBusy: Boolean = false,
    val includeSubs: Boolean = true,
    val note: String = "",
) {
    val selectedCount: Int
        get() = groups.sumOf { it.selectedGuids.size }

    val canExport: Boolean
        get() = selectedCount > 0 &&
            (exportType == ExportType.NORMAL || password.isNotEmpty()) &&
            (exportType == ExportType.NORMAL || password == confirmPassword)
}

class ExportViewModel : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    fun loadGroups() {
        viewModelScope.launch(Dispatchers.IO) {
            val subscriptions = MmkvManager.decodeSubscriptions()
            val groups = mutableListOf<ExportGroup>()

            subscriptions.forEach { sub ->
                val guids = MmkvManager.decodeServerList(sub.guid)
                val entries = guids.mapNotNull { guid ->
                    MmkvManager.decodeServerConfig(guid)?.let { profile ->
                        ExportConfigEntry(guid, profile.remarks, ArasExportImportManager.isProtected(guid))
                    }
                }
                if (entries.isNotEmpty()) {
                    groups.add(ExportGroup(sub.guid, sub.subscription.remarks, entries))
                }
            }

            // Standalone configs: guids in the default index that are not part
            // of any subscription group already listed above.
            val grouped = groups.flatMap { it.configs.map { c -> c.guid } }.toSet()
            val standalone = MmkvManager.decodeServerList("")
                .filter { it !in grouped }
                .mapNotNull { guid ->
                    MmkvManager.decodeServerConfig(guid)?.let { profile ->
                        ExportConfigEntry(guid, profile.remarks, ArasExportImportManager.isProtected(guid))
                    }
                }
            if (standalone.isNotEmpty()) {
                groups.add(ExportGroup("", "", standalone))
            }

            withContext(Dispatchers.Main) {
                _state.update { it.copy(groups = groups) }
            }
        }
    }

    fun toggleGroupExpanded(subscriptionId: String) {
        _state.update { s ->
            val next = s.expandedGroups.toMutableSet()
            if (!next.remove(subscriptionId)) next.add(subscriptionId)
            s.copy(expandedGroups = next)
        }
    }

    fun toggleConfigSelected(subscriptionId: String, guid: String) {
        _state.update { s ->
            s.copy(groups = s.groups.map { group ->
                if (group.subscriptionId != subscriptionId) group
                else group.copy(
                    selectedGuids = if (group.selectedGuids.contains(guid))
                        group.selectedGuids - guid
                    else
                        group.selectedGuids + guid
                )
            })
        }
    }

    fun toggleGroupSelected(subscriptionId: String, selected: Boolean) {
        _state.update { s ->
            s.copy(groups = s.groups.map { group ->
                if (group.subscriptionId != subscriptionId) group
                else group.copy(
                    selectedGuids = if (selected) group.configs.map { it.guid }.toSet() else emptySet()
                )
            })
        }
    }

    fun setExportType(type: ExportType) {
        _state.update { it.copy(exportType = type) }
    }

    fun setPassword(password: String) {
        _state.update { it.copy(password = password) }
    }

    fun setConfirmPassword(confirm: String) {
        _state.update { it.copy(confirmPassword = confirm) }
    }

    fun setIncludeSubs(enabled: Boolean) {
        _state.update { it.copy(includeSubs = enabled) }
    }

    fun setNote(note: String) {
        _state.update { it.copy(note = note) }
    }

    fun suggestedFileName(): String = "ArasClient-Configs.${ArascMime.EXT}"

    /** Validates the form; returns false (with inline state) when invalid. */
    fun startExport(): Boolean {
        val s = _state.value
        if (s.selectedCount == 0) return false
        if (s.exportType == ExportType.PROTECTED) {
            if (s.password.isEmpty() || s.password != s.confirmPassword) return false
        }
        _state.update { it.copy(isBusy = true) }
        return true
    }

    /** Writes the container to the picked location; must run after startExport. */
    fun writeTo(context: Context, uri: Uri) {
        val s = _state.value
        val items = s.groups.mapNotNull { group ->
            if (group.selectedGuids.isEmpty()) null
            else Triple(group.subscriptionId, group.title.ifBlank { "ArasClient" }, group.selectedGuids.toList())
        }
        val subLinks = if (s.includeSubs) {
            s.groups.filter { it.subscriptionId.isNotBlank() && it.selectedGuids.isNotEmpty() }
                .mapNotNull { group ->
                    val item = MmkvManager.decodeSubscription(group.subscriptionId)
                        ?: return@mapNotNull null
                    group.subscriptionId to (item to group.selectedGuids.toList())
                }.toMap()
        } else {
            emptyMap()
        }
        val password = if (s.exportType == ExportType.PROTECTED) s.password.toCharArray() else null

        viewModelScope.launch(Dispatchers.IO) {
            var pass = password
            try {
                val bytes = ArasExportImportManager.buildExport(items, pass, subLinks, s.note.takeIf { it.isNotBlank() })
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                } ?: throw IllegalStateException("Cannot open output stream")
                pass?.fill('\u0000')
                pass = null
                withContext(Dispatchers.Main) {
                    context.toast(R.string.export_success)
                    _state.update { it.copy(isBusy = false, password = "", confirmPassword = "") }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "arasc export failed", e)
                pass?.fill('\u0000')
                withContext(Dispatchers.Main) {
                    context.toast(R.string.toast_failure)
                    _state.update { it.copy(isBusy = false) }
                }
            }
        }
    }

    override fun onCleared() {
        // Wipe password material from state when leaving the screen.
        _state.update { it.copy(password = "", confirmPassword = "") }
        super.onCleared()
    }
}
