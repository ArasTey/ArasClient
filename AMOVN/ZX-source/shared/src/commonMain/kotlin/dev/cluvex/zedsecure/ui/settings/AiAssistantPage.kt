package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.domain.ai.AiClient
import dev.cluvex.zedsecure.domain.ai.AiModel
import dev.cluvex.zedsecure.domain.ai.AiProvider
import dev.cluvex.zedsecure.domain.ai.AiSettings
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.ai_allow_sub
import dev.cluvex.zedsecure.shared.resources.ai_allow_title
import dev.cluvex.zedsecure.shared.resources.ai_checking
import dev.cluvex.zedsecure.shared.resources.ai_connected
import dev.cluvex.zedsecure.shared.resources.ai_context
import dev.cluvex.zedsecure.shared.resources.ai_enable_sub
import dev.cluvex.zedsecure.shared.resources.ai_enable_title
import dev.cluvex.zedsecure.shared.resources.ai_get_key
import dev.cluvex.zedsecure.shared.resources.ai_key
import dev.cluvex.zedsecure.shared.resources.ai_key_saved
import dev.cluvex.zedsecure.shared.resources.ai_model
import dev.cluvex.zedsecure.shared.resources.ai_model_count
import dev.cluvex.zedsecure.shared.resources.ai_model_none
import dev.cluvex.zedsecure.shared.resources.ai_model_note
import dev.cluvex.zedsecure.shared.resources.ai_model_pick
import dev.cluvex.zedsecure.shared.resources.ai_need_setup
import dev.cluvex.zedsecure.shared.resources.ai_open
import dev.cluvex.zedsecure.shared.resources.ai_provider
import dev.cluvex.zedsecure.shared.resources.ai_standing
import dev.cluvex.zedsecure.shared.resources.ai_standing_hint
import dev.cluvex.zedsecure.shared.resources.ai_verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun AiAssistantPage(
    settings: AiSettings,
    onUpdate: (AiSettings) -> Unit,
    onOpenChat: () -> Unit,
    onOpenKeyPage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val connectedLabel = stringResource(Res.string.ai_connected, 0).replace("0", "%d")

    val savedKey = remember(settings.provider, settings.sealedKeys[settings.provider.name]) { settings.key() }
    var keyDraft by remember(settings.provider) { mutableStateOf(savedKey) }
    var models by remember(settings.provider) { mutableStateOf<List<AiModel>>(emptyList()) }

    var modelsKey by remember(settings.provider) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember(settings.provider) { mutableStateOf<String?>(null) }
    var failed by remember(settings.provider) { mutableStateOf(false) }
    var keyFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val currentSettings by rememberUpdatedState(settings)
    val currentOnUpdate by rememberUpdatedState(onUpdate)

    LaunchedEffect(settings.provider, keyDraft) {
        if (keyDraft == savedKey) return@LaunchedEffect
        delay(800)
        currentOnUpdate(currentSettings.withKey(currentSettings.provider, keyDraft))
    }

    LaunchedEffect(settings.provider, savedKey, keyFocused) {
        if (savedKey.isBlank() || keyFocused || busy || modelsKey == savedKey) return@LaunchedEffect
        busy = true
        try {
            AiClient.of(settings.provider, savedKey).models()
                .onSuccess { models = it; modelsKey = savedKey; failed = false; status = null }
                .onFailure { status = it.message; failed = true }
        } finally {
            busy = false
        }
    }

    Column(modifier.padding(horizontal = 16.dp)) {
        SwitchRow(
            title = stringResource(Res.string.ai_enable_title),
            subtitle = stringResource(Res.string.ai_enable_sub),
            checked = settings.enabled,
            onChange = { onUpdate(settings.copy(enabled = it)) },
        )

        if (!settings.enabled) return@Column

        Spacer(Modifier.height(16.dp))
        Text(stringResource(Res.string.ai_provider), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { p ->
                FilterChip(
                    selected = settings.provider == p,
                    onClick = { onUpdate(settings.copy(provider = p)) },
                    label = { Text(p.label) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it.trim() },
            label = { Text(stringResource(Res.string.ai_key)) },
            singleLine = true,

            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            supportingText = if (savedKey.isNotBlank() && keyDraft == savedKey) {
                { Text(stringResource(Res.string.ai_key_saved)) }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    if (!focus.isFocused && keyDraft != savedKey) {
                        onUpdate(settings.withKey(settings.provider, keyDraft))
                    }
                    keyFocused = focus.isFocused
                },
        )
        TextButton(onClick = { onOpenKeyPage(settings.provider.keyUrl) }) {
            Text(stringResource(Res.string.ai_get_key, settings.provider.label))
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                enabled = keyDraft.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    status = null

                    val saved = settings.withKey(settings.provider, keyDraft)
                    onUpdate(saved)
                    scope.launch {
                        AiClient.of(settings.provider, keyDraft).models()
                            .onSuccess { list ->
                                models = list
                                modelsKey = keyDraft
                                failed = false
                                status = connectedLabel.format(list.size)
                                var next = saved

                                if (list.none { it.id == next.model() }) {
                                    next = next.withModel(settings.provider, "")
                                }
                                onUpdate(next)
                            }
                            .onFailure {
                                failed = true

                                status = it.message
                            }
                        busy = false
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.ai_checking))
                } else {
                    Text(stringResource(Res.string.ai_verify))
                }
            }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        if (models.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))

            ModelPicker(
                models = models,
                selected = settings.model(),
                onSelect = { onUpdate(settings.withModel(settings.provider, it)) },
            )
        }

        Spacer(Modifier.height(16.dp))
        SwitchRow(
            title = stringResource(Res.string.ai_allow_title),
            subtitle = stringResource(Res.string.ai_allow_sub),
            checked = settings.allowChanges,
            onChange = { onUpdate(settings.copy(allowChanges = it)) },
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = settings.extraPrompt,
            onValueChange = { onUpdate(settings.copy(extraPrompt = it)) },
            label = { Text(stringResource(Res.string.ai_standing)) },
            placeholder = { Text(stringResource(Res.string.ai_standing_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            enabled = settings.isReady(),
            onClick = onOpenChat,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (settings.isReady()) Res.string.ai_open else Res.string.ai_need_setup,
                ),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(models: List<AiModel>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val current = models.firstOrNull { it.id == selected }
    val shown = remember(models, filter) {
        val q = filter.trim()
        if (q.isEmpty()) models else models.filter { it.id.contains(q, true) || it.label.contains(q, true) }
    }

    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = if (open) filter else (current?.label ?: selected),
            onValueChange = { filter = it; open = true },
            label = { Text(stringResource(Res.string.ai_model)) },
            placeholder = { Text(stringResource(Res.string.ai_model_pick)) },
            supportingText = {
                Text(
                    if (current != null && current.label != current.id) current.id
                    else stringResource(Res.string.ai_model_count, models.size),
                )
            },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false; filter = "" },
        ) {
            if (shown.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.ai_model_none)) },
                    onClick = {},
                    enabled = false,
                )
            }
            shown.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.label, style = MaterialTheme.typography.bodyMedium)
                            if (m.label != m.id) {
                                Text(
                                    m.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(m.id)
                        open = false
                        filter = ""
                    },
                )
            }
        }
    }
}
