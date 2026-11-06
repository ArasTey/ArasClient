package dev.cluvex.zedsecure.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.cluvex.zedsecure.domain.model.AppSettings
import dev.cluvex.zedsecure.domain.model.CardCornerStyle
import dev.cluvex.zedsecure.domain.model.ListDensity
import dev.cluvex.zedsecure.domain.model.TrafficCardStyle
import dev.cluvex.zedsecure.domain.model.TrafficTileSize

data class AccentPreset(val name: String, val light: Color, val dark: Color)

val AccentPresets: List<AccentPreset> = listOf(
    AccentPreset("Iris", Color(0xFF5646D6), Color(0xFFC7BFFF)),
    AccentPreset("Ocean", Color(0xFF1E63D0), Color(0xFFAAC7FF)),
    AccentPreset("Teal", Color(0xFF00897E), Color(0xFF52DDCF)),
    AccentPreset("Forest", Color(0xFF2E7D32), Color(0xFF9BD89B)),
    AccentPreset("Amber", Color(0xFFB4690E), Color(0xFFFFB870)),
    AccentPreset("Rose", Color(0xFFC4326B), Color(0xFFFFB0C8)),
    AccentPreset("Crimson", Color(0xFFC0362C), Color(0xFFFFB4A9)),
    AccentPreset("Cyan", Color(0xFF00838F), Color(0xFF4DD0E1)),
    AccentPreset("Grape", Color(0xFF8E24AA), Color(0xFFE9B3FF)),
    AccentPreset("Slate", Color(0xFF4A5B7A), Color(0xFFB6C6EA)),
)

fun Color.readableOn(): Color =
    if (luminance() > 0.5f) Color(0xFF10131A) else Color.White

fun ColorScheme.amoled(dark: Boolean): ColorScheme {
    if (!dark) return this
    val black = Color(0xFF000000)
    val bright = Color(0xFF1A1A1A)
    val low = Color(0xFF0B0B0B)
    val container = Color(0xFF0F0F0F)
    val high = Color(0xFF161616)
    val highest = Color(0xFF1E1E1E)
    return copy(
        background = black,
        surface = black,
        surfaceBright = bright,
        surfaceDim = black,
        surfaceContainer = container,
        surfaceContainerHigh = high,
        surfaceContainerHighest = highest,
        surfaceContainerLow = low,
        surfaceContainerLowest = black,
    )
}

data class Personalization(
    val downloadColor: Color? = null,
    val uploadColor: Color? = null,

    val downloadTileColor: Color? = null,
    val uploadTileColor: Color? = null,
    val connectColor: Color? = null,
    val connectedColor: Color? = null,
    val tapHintColor: Color? = null,

    val configCardColor: Color? = null,
    val configCardTextColor: Color? = null,
    val serverCardColor: Color? = null,
    val serverActiveColor: Color? = null,
    val cornerStyle: CardCornerStyle = CardCornerStyle.Rounded,
    val density: ListDensity = ListDensity.Comfortable,
    val showServerPing: Boolean = true,
    val showServerUsage: Boolean = true,
    val monospaceAddress: Boolean = false,

    val showTrafficTiles: Boolean = true,
    val trafficTilesAboveHero: Boolean = false,
    val trafficTileSize: TrafficTileSize = TrafficTileSize.Normal,
    val trafficCardStyle: TrafficCardStyle = TrafficCardStyle.Cards,
) {
    companion object {
        val Default = Personalization()
    }
}

fun AppSettings.toPersonalization(): Personalization = Personalization(
    downloadColor = customDownloadColor?.argbLongToColor(),
    uploadColor = customUploadColor?.argbLongToColor(),
    downloadTileColor = customDownloadTileColor?.argbLongToColor(),
    uploadTileColor = customUploadTileColor?.argbLongToColor(),
    connectColor = customConnectColor?.argbLongToColor(),
    connectedColor = customConnectedColor?.argbLongToColor(),
    tapHintColor = customTapHintColor?.argbLongToColor(),
    configCardColor = customConfigCardColor?.argbLongToColor(),
    configCardTextColor = customConfigCardTextColor?.argbLongToColor(),
    serverCardColor = customServerCardColor?.argbLongToColor(),
    serverActiveColor = customServerActiveColor?.argbLongToColor(),
    cornerStyle = cardCornerStyle,
    density = listDensity,
    showServerPing = showServerPing,
    showServerUsage = showServerUsage,
    monospaceAddress = monospaceAddress,
    showTrafficTiles = showTrafficTiles,
    trafficTilesAboveHero = trafficTilesAboveHero,
    trafficTileSize = trafficTileSize,
    trafficCardStyle = trafficCardStyle,
)

fun AppSettings.resetPersonalization(): AppSettings = copy(
    customDownloadColor = null,
    customUploadColor = null,
    customDownloadTileColor = null,
    customUploadTileColor = null,
    customConnectColor = null,
    customConnectedColor = null,
    customTapHintColor = null,
    customConfigCardColor = null,
    customConfigCardTextColor = null,
    customServerCardColor = null,
    customServerActiveColor = null,

    themeProfileId = null,
    cardCornerStyle = CardCornerStyle.Rounded,
    listDensity = ListDensity.Comfortable,
    showServerPing = true,
    showServerUsage = true,
    monospaceAddress = false,
)

val AppSettings.hasPersonalization: Boolean
    get() = customDownloadColor != null || customUploadColor != null ||
        customDownloadTileColor != null || customUploadTileColor != null || customConnectColor != null ||
        customConnectedColor != null || customTapHintColor != null ||
        customConfigCardColor != null || customConfigCardTextColor != null ||
        customServerCardColor != null || customServerActiveColor != null ||
        cardCornerStyle != CardCornerStyle.Rounded || listDensity != ListDensity.Comfortable ||
        !showServerPing || !showServerUsage || monospaceAddress

fun goodPingColor(container: Color): Color =
    if (container.luminance() > 0.5f) Color(0xFF1B7A3C) else ZedLime

fun fairPingColor(container: Color): Color =
    if (container.luminance() > 0.5f) Color(0xFF8A5A00) else Color(0xFFFFC46B)

fun pingColor(ping: Int, container: Color, error: Color = Color(0xFFD1453B)): Color = when {
    ping < 0 -> error
    ping < 200 -> goodPingColor(container)
    ping < 500 -> fairPingColor(container)
    else -> error
}
