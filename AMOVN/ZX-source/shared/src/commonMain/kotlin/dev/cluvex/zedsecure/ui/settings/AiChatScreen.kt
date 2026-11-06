package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.domain.ai.AiAgent
import dev.cluvex.zedsecure.domain.ai.AiAppBridge
import dev.cluvex.zedsecure.domain.ai.AiClient
import dev.cluvex.zedsecure.domain.ai.AiRole
import dev.cluvex.zedsecure.domain.ai.AiSettings
import dev.cluvex.zedsecure.domain.ai.AiSystemPrompt
import dev.cluvex.zedsecure.domain.ai.AiTurn
import dev.cluvex.zedsecure.domain.ai.AiVoiceController
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.ai_chat_hint
import dev.cluvex.zedsecure.shared.resources.ai_example_fastest
import dev.cluvex.zedsecure.shared.resources.ai_example_hot
import dev.cluvex.zedsecure.shared.resources.ai_example_mtu
import dev.cluvex.zedsecure.shared.resources.ai_example_nodata
import dev.cluvex.zedsecure.shared.resources.ai_intro_changes
import dev.cluvex.zedsecure.shared.resources.ai_intro_readonly
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun AiChatScreen(
    settings: AiSettings,
    bridge: AiAppBridge,
    voiceController: AiVoiceController? = null,
    languageName: String,
    languageNative: String,
    platform: String,
    appVersion: String,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf<AiTurn>() }
    val steps = remember { mutableStateListOf<String>() }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val agent = remember(settings.provider, settings.model(), settings.allowChanges, languageName) {
        AiAgent(
            client = AiClient.of(settings.provider, settings.key()),
            bridge = bridge,
            model = settings.model(),
            systemPrompt = AiSystemPrompt.build(
                languageName = languageName,
                languageNative = languageNative,
                platform = platform,
                appVersion = appVersion,
                allowChanges = settings.allowChanges,
                extra = settings.extraPrompt,
            ),
            allowChanges = settings.allowChanges,
        )
    }

    LaunchedEffect(history.size, steps.size) {
        val last = history.size + steps.size - 1
        if (last >= 0) runCatching { listState.animateScrollToItem(last) }
    }

    fun submitMessage(rawMessage: String) {
        val message = rawMessage.trim()
        if (message.isBlank() || busy) return
        draft = ""
        busy = true
        steps.clear()
        voiceStatus = null
        scope.launch {
            val produced = runCatching {
                agent.ask(history.toList(), message) { step ->
                    when (step) {
                        is AiAgent.Step.Calling -> steps += "→ ${step.name}"
                        is AiAgent.Step.Called -> steps += if (step.result.failed) {
                            "✕ ${step.result.name}: ${step.result.content.take(200)}"
                        } else {
                            "✓ ${step.result.name}"
                        }
                        is AiAgent.Step.Failed -> steps += "✕ ${step.message}"
                        else -> Unit
                    }
                }
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                listOf(
                    AiTurn(AiRole.USER, message),
                    AiTurn(AiRole.ASSISTANT, "", error = e.message ?: e::class.simpleName),
                )
            }
            history.addAll(produced)
            if (voiceMode) {
                produced.lastOrNull { it.role == AiRole.ASSISTANT && it.text.isNotBlank() }?.text
                    ?.let { voiceController?.speak(it) }
            }
            steps.clear()
            busy = false
        }
    }

    Column(modifier.fillMaxSize().padding(contentPadding)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(settings.provider.label, style = MaterialTheme.typography.titleLarge)
            Text(
                settings.model(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (history.isEmpty()) {
                item { Intro(settings) }
            }
            items(history) { turn -> TurnBubble(turn) }
            if (steps.isNotEmpty()) {
                item { StepTrail(steps) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(Res.string.ai_chat_hint)) },
                enabled = !busy && !listening,
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    enabled = voiceController != null && !busy,
                    onClick = {
                        voiceMode = true
                        if (listening) {
                            voiceController?.stopListening()
                            listening = false
                        } else {
                            voiceStatus = "Listening…"
                            listening = true
                            voiceController?.startListening(
                                onResult = { spoken ->
                                    listening = false
                                    voiceStatus = "Recognized speech"
                                    submitMessage(spoken)
                                },
                                onError = { error ->
                                    listening = false
                                    voiceStatus = error
                                },
                            )
                        }
                    },
                ) { Text(if (listening) "■" else "🎙") }
                TextButton(
                    onClick = {
                        val enableVoice = !voiceMode
                        voiceMode = enableVoice
                        if (!enableVoice) {
                            voiceController?.stopSpeaking()
                            if (listening) {
                                voiceController?.stopListening()
                                listening = false
                            }
                        }
                    },
                    enabled = voiceController != null,
                ) { Text(if (voiceMode) "Voice on" else "Voice") }
            }
            FilledIconButton(
                enabled = draft.isNotBlank() && !busy && !listening,
                onClick = { submitMessage(draft) },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("\u27A4")
            }
        }
        voiceStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun Intro(settings: AiSettings) {
    Column(Modifier.padding(vertical = 24.dp)) {
        Text("${settings.provider.label} · ${settings.model()}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                if (settings.allowChanges) Res.string.ai_intro_changes else Res.string.ai_intro_readonly,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        listOf(
            Res.string.ai_example_hot,
            Res.string.ai_example_nodata,
            Res.string.ai_example_fastest,
            Res.string.ai_example_mtu,
        ).map { stringResource(it) }.forEach {
            Text("· $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun TurnBubble(turn: AiTurn) {
    if (turn.role == AiRole.TOOL) {
        Text(
            turn.results.joinToString("  ") { (if (it.failed) "✕ " else "✓ ") + it.name },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        return
    }
    if (turn.text.isBlank() && turn.error == null) return

    val user = turn.role == AiRole.USER
    val error = turn.error
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        error != null -> MaterialTheme.colorScheme.errorContainer
                        user -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .padding(12.dp),
        ) {
            Text(
                error ?: turn.text,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    error != null -> MaterialTheme.colorScheme.onErrorContainer
                    user -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun StepTrail(steps: List<String>) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
        steps.takeLast(8).forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
