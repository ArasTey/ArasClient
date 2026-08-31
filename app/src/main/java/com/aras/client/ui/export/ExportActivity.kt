package com.aras.client.ui.export

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aras.client.R
import com.aras.client.ui.base.BaseComponentActivity
import com.aras.client.ui.compose.AppTopBar
import com.aras.client.ui.compose.NavigationBarsSpacer

class ExportActivity : BaseComponentActivity() {

    private val viewModel by lazy { ExportViewModel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadGroups()
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()

        val saveLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(ArascMime.MIME)
        ) { uri -> if (uri != null) viewModel.writeTo(this, uri) }

        ExportScreen(
            state = state,
            onBackClick = { finish() },
            onToggleGroup = viewModel::toggleGroupExpanded,
            onToggleConfig = viewModel::toggleConfigSelected,
            onToggleGroupSelected = viewModel::toggleGroupSelected,
            onExportTypeChange = viewModel::setExportType,
            onPasswordChange = viewModel::setPassword,
            onConfirmPasswordChange = viewModel::setConfirmPassword,
            onIncludeSubsChange = viewModel::setIncludeSubs,
            onNoteChange = viewModel::setNote,
            onExport = {
                if (viewModel.startExport()) {
                    saveLauncher.launch(viewModel.suggestedFileName())
                }
            }
        )
    }
}

/** Shared MIME/extension constants. */
object ArascMime {
    const val MIME = "application/x-arasc"
    const val EXT = "arasc"
}

@Composable
fun ExportScreen(
    state: ExportUiState,
    onBackClick: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onToggleConfig: (String, String) -> Unit,
    onToggleGroupSelected: (String, Boolean) -> Unit,
    onExportTypeChange: (ExportType) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onIncludeSubsChange: (Boolean) -> Unit,
    onNoteChange: (String) -> Unit,
    onExport: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_export),
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            ExportBottomBar(
                enabled = state.canExport && !state.isBusy,
                isBusy = state.isBusy,
                selectedCount = state.selectedCount,
                onExport = onExport
            )
        }
    ) { innerPadding ->
        if (state.groups.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.export_no_configs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SummaryHeader(state) }
            item { TypeSelector(state.exportType, onExportTypeChange) }
            item {
                ShareExtrasCard(
                    includeSubs = state.includeSubs,
                    note = state.note,
                    onIncludeSubsChange = onIncludeSubsChange,
                    onNoteChange = onNoteChange,
                )
            }
            if (state.exportType == ExportType.PROTECTED) {
                item {
                    PasswordFields(
                        password = state.password,
                        confirmPassword = state.confirmPassword,
                        onPasswordChange = onPasswordChange,
                        onConfirmPasswordChange = onConfirmPasswordChange,
                    )
                }
            }
            items(
                state.groups,
                key = { it.subscriptionId.ifEmpty { "_standalone" } }
            ) { group ->
                ExportGroupCard(
                    group = group,
                    expanded = state.expandedGroups.contains(group.subscriptionId),
                    onToggleGroup = { onToggleGroup(group.subscriptionId) },
                    onToggleConfig = { guid -> onToggleConfig(group.subscriptionId, guid) },
                    onToggleGroupSelected = { selected ->
                        onToggleGroupSelected(group.subscriptionId, selected)
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryHeader(state: ExportUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_share_24dp),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(
                    if (state.exportType == ExportType.PROTECTED)
                        R.string.export_summary_protected else R.string.export_summary_normal
                ),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            Text(
                text = stringResource(
                    R.string.export_summary_subtitle,
                    state.selectedCount,
                    state.groups.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun TypeSelector(
    selected: ExportType,
    onSelect: (ExportType) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ExportType.entries.forEach { type ->
            val isSelected = selected == type
            val container = if (isSelected)
                MaterialTheme.colorScheme.secondary
            else
                Color.Transparent
            val content = if (isSelected)
                MaterialTheme.colorScheme.onSecondary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(container)
                    .clickable { onSelect(type) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(type.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = content
                )
            }
        }
    }
}

@Composable
private fun PasswordFields(
    password: String,
    confirmPassword: String,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.export_password_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.export_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = onConfirmPasswordChange,
            label = { Text(stringResource(R.string.export_password_confirm)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = confirmPassword.isNotEmpty() && confirmPassword != password,
            supportingText = {
                if (confirmPassword.isNotEmpty() && confirmPassword != password) {
                    Text(
                        stringResource(R.string.export_password_mismatch),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ShareExtrasCard(
    includeSubs: Boolean,
    note: String,
    onIncludeSubsChange: (Boolean) -> Unit,
    onNoteChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.export_include_subs),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.export_include_subs_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = includeSubs,
                onCheckedChange = onIncludeSubsChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                    checkedTrackColor = MaterialTheme.colorScheme.secondary
                )
            )
        }
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text(stringResource(R.string.export_note)) },
            placeholder = { Text(stringResource(R.string.export_note_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ExportGroupCard(
    group: ExportGroup,
    expanded: Boolean,
    onToggleGroup: () -> Unit,
    onToggleConfig: (String) -> Unit,
    onToggleGroupSelected: (Boolean) -> Unit,
) {
    val allSelected = group.selectedGuids.size == group.configs.size && group.configs.isNotEmpty()
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "chevron"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .animateContentSize(animationSpec = tween(200))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleGroup() }
                .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = { checked -> onToggleGroupSelected(checked) },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.secondary,
                    checkmarkColor = MaterialTheme.colorScheme.onSecondary
                )
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.title.ifBlank { stringResource(R.string.export_ungrouped) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (group.selectedGuids.isEmpty())
                        stringResource(R.string.export_config_count, group.configs.size)
                    else
                        stringResource(
                            R.string.export_config_count_selected,
                            group.selectedGuids.size, group.configs.size
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleGroup) {
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more_24dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 52.dp)
                )
                group.configs.forEach { config ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleConfig(config.guid) }
                            .padding(start = 2.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = group.selectedGuids.contains(config.guid),
                            onCheckedChange = { onToggleConfig(config.guid) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.secondary,
                                checkmarkColor = MaterialTheme.colorScheme.onSecondary
                            )
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = config.remarks,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (config.isProtected) {
                                Text(
                                    text = stringResource(R.string.export_badge_protected),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ExportBottomBar(
    enabled: Boolean,
    isBusy: Boolean,
    selectedCount: Int,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Button(
            onClick = onExport,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondary
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_share_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_action, selectedCount))
            }
        }
        NavigationBarsSpacer()
    }
}
