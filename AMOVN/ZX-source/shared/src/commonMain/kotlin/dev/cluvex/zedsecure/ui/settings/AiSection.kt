package dev.cluvex.zedsecure.ui.settings

import dev.cluvex.zedsecure.domain.ai.AiAppBridge
import dev.cluvex.zedsecure.domain.ai.AiSettings

data class AiSection(
    val settings: AiSettings,
    val onUpdate: (AiSettings) -> Unit,
    val bridge: AiAppBridge,
    val voiceController: dev.cluvex.zedsecure.domain.ai.AiVoiceController? = null,
    val onOpenUrl: (String) -> Unit,

    val languageName: String,
    val languageNative: String,

    val platform: String,
    val appVersion: String,
)
