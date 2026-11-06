package com.aras.client.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import com.aras.client.R
import androidx.compose.material3.Typography
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.aras.client.AppConfig
import com.aras.client.handler.MmkvManager
import com.aras.client.ui.rdr.RdrBackground
import com.aras.client.ui.rdr.RdrTheme
import com.aras.client.ui.rdr.rememberRdrThemeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val LightColor = lightColorScheme(
    primary = Color(0xFF0284C7), // Aras Blue (darker sky)
    onPrimary = Color(0xFFFFFFFF), // White
    primaryContainer = Color(0xFFD3E9FB), // Pale Blue
    onPrimaryContainer = Color(0xFF04253D), // Deep Navy
    secondary = Color(0xFF0EA5E9), // Sky Blue
    onSecondary = Color(0xFFFFFFFF), // White
    secondaryContainer = Color(0xFFD8EFFC), // Pale Sky
    onSecondaryContainer = Color(0xFF082B3A), // Deep Sky
    tertiary = Color(0xFF5B8DEF), // Chip Blue (protocol badges)
    onTertiary = Color(0xFFFFFFFF), // White
    tertiaryContainer = Color(0xFFDCE6FB), // Pale Blue
    onTertiaryContainer = Color(0xFF101C33), // Deep Navy
    error = Color(0xFFBA1A1A), // Red
    errorContainer = Color(0xFFFFDAD6), // Light Red
    onError = Color(0xFFFFFFFF), // White
    onErrorContainer = Color(0xFF410002), // Dark Red
    background = Color(0xFFFAF6F0), // Warm Cream
    onBackground = Color(0xFF222226), // Charcoal
    surface = Color(0xFFFFFDFA), // Warm White
    onSurface = Color(0xFF222226), // Charcoal
    surfaceVariant = Color(0xFFEFE6DA), // Warm Sand
    onSurfaceVariant = Color(0xFF52443A), // Warm Gray Brown
    outline = Color(0xFF857468), // Warm Gray
    outlineVariant = Color(0xFFE2D7C9), // Light Sand
    inverseSurface = Color(0xFF37302B), // Warm Dark
    inverseOnSurface = Color(0xFFFAF6F0), // Cream
    inversePrimary = Color(0xFF7FC8F2), // Light Blue
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF0284C7), // Aras Blue
    surfaceContainerLowest = Color(0xFFFFFFFF), // White
    surfaceContainerLow = Color(0xFFF7F1E8), // Light Cream
    surfaceContainer = Color(0xFFF1EAE0), // Cream Sand
    surfaceContainerHigh = Color(0xFFEBE3D8), // Sand
    surfaceContainerHighest = Color(0xFFE5DCD0), // Deep Sand
)

private val AuroraDarkColor = darkColorScheme(
    primary = Color(0xFF54FFC0), // Aurora Mint
    onPrimary = Color(0xFF06140E), // Obsidian
    primaryContainer = Color(0xFF174A37), // Aurora Forest
    onPrimaryContainer = Color(0xFFBFFFE0), // Aurora Ice
    secondary = Color(0xFFB8FF70), // Aurora Lime
    onSecondary = Color(0xFF17220B), // Lime Ink
    secondaryContainer = Color(0xFF344C23), // Lime Forest
    onSecondaryContainer = Color(0xFFE4FFC8), // Lime Ice
    tertiary = Color(0xFF85E9D0), // Aurora Teal
    onTertiary = Color(0xFF08221B), // Teal Ink
    tertiaryContainer = Color(0xFF16443B), // Teal Forest
    onTertiaryContainer = Color(0xFFC8FFF1), // Teal Ice
    error = Color(0xFFFF5369), // Signal Red
    errorContainer = Color(0xFF5A1523), // Danger Surface
    onError = Color(0xFFFFFFFF), // White
    onErrorContainer = Color(0xFFFFDCE1), // Danger Ice
    background = Color(0xFF070A0B), // Obsidian
    onBackground = Color(0xFFF1F8F4), // Aurora White
    surface = Color(0xFF0C1212), // Deep Surface
    onSurface = Color(0xFFF1F8F4), // Aurora White
    surfaceVariant = Color(0xFF172320), // Glass Surface
    onSurfaceVariant = Color(0xFF91A39A), // Muted Sage
    outline = Color(0xFF47645A), // Glass Outline
    outlineVariant = Color(0xFF293B36), // Subtle Outline
    inverseSurface = Color(0xFFE4F4EB), // Light Surface
    inverseOnSurface = Color(0xFF101715), // Dark Ink
    inversePrimary = Color(0xFF087C58), // Aras Blue
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF54FFC0), // Aurora Mint
    surfaceContainerLowest = Color(0xFF050707), // Near Black
    surfaceContainerLow = Color(0xFF0A100F), // Low Glass
    surfaceContainer = Color(0xFF101817), // Glass
    surfaceContainerHigh = Color(0xFF16211E), // Elevated Glass
    surfaceContainerHighest = Color(0xFF1D2C26), // High Glass
)

private val ZxLiquidColor = darkColorScheme(
    primary = Color(0xFF9B8CFF),
    onPrimary = Color(0xFF17112F),
    primaryContainer = Color(0xFF352B68),
    onPrimaryContainer = Color(0xFFE8E2FF),
    secondary = Color(0xFF65DDF2),
    onSecondary = Color(0xFF06252D),
    secondaryContainer = Color(0xFF164B59),
    onSecondaryContainer = Color(0xFFC7F7FF),
    tertiary = Color(0xFFFF78C8),
    onTertiary = Color(0xFF35102A),
    error = Color(0xFFFF6B83),
    errorContainer = Color(0xFF5B1D32),
    onError = Color(0xFF39000C),
    onErrorContainer = Color(0xFFFFDCE2),
    background = Color(0xFF090817),
    onBackground = Color(0xFFF4F1FF),
    surface = Color(0xE61B1730),
    onSurface = Color(0xFFF4F1FF),
    surfaceVariant = Color(0xCC292344),
    onSurfaceVariant = Color(0xFFC2BCE0),
    outline = Color(0x668B7CFF),
    outlineVariant = Color(0x443F3766),
    inverseSurface = Color(0xFFECE8FF),
    inverseOnSurface = Color(0xFF1B1730),
    inversePrimary = Color(0xFF5D4BC7),
    scrim = Color(0x99000000),
    surfaceTint = Color(0xFF9B8CFF),
    surfaceContainerLowest = Color(0xE6090817),
    surfaceContainerLow = Color(0xE61B1730),
    surfaceContainer = Color(0xE6221D3B),
    surfaceContainerHigh = Color(0xE62A2448),
    surfaceContainerHighest = Color(0xE6342D55),
)
private val DarkColor = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF102A43),
    primaryContainer = Color(0xFF244A68),
    onPrimaryContainer = Color(0xFFD6EAFB),
    secondary = Color(0xFFB0BEC5),
    onSecondary = Color(0xFF263238),
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = Color(0xFFE0E7EB),
    tertiary = Color(0xFFA5B4FC),
    onTertiary = Color(0xFF20264A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF690005),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF171A1F),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF30343B),
    onSurfaceVariant = Color(0xFFC2C7D0),
    outline = Color(0xFF8C919A),
    outlineVariant = Color(0xFF444951),
    inverseSurface = Color(0xFFE1E2E8),
    inverseOnSurface = Color(0xFF292C31),
    inversePrimary = Color(0xFF42617A),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF90CAF9),
    surfaceContainerLowest = Color(0xFF0C0F12),
    surfaceContainerLow = Color(0xFF191C21),
    surfaceContainer = Color(0xFF1D2025),
    surfaceContainerHigh = Color(0xFF282B31),
    surfaceContainerHighest = Color(0xFF33363D),
)

private val CulturalNightColor = darkColorScheme(
    primary = Color(0xFF4FB3B0),
    onPrimary = Color(0xFF082126),
    primaryContainer = Color(0xFF244A4D),
    onPrimaryContainer = Color(0xFFD1F0E8),
    secondary = Color(0xFFC6A45D),
    onSecondary = Color(0xFF211A0D),
    secondaryContainer = Color(0xFF453820),
    onSecondaryContainer = Color(0xFFF3E4BE),
    tertiary = Color(0xFF3FBF7F),
    onTertiary = Color(0xFF082116),
    error = Color(0xFFFF8A76),
    errorContainer = Color(0xFF6E2920),
    onError = Color(0xFF310B06),
    background = Color(0xFF0B1420),
    onBackground = Color(0xFFF2E8D5),
    surface = Color(0xFF16222F),
    onSurface = Color(0xFFF2E8D5),
    surfaceVariant = Color(0xFF202D3A),
    onSurfaceVariant = Color(0xFFA9A08A),
    outline = Color(0x40C6A45D),
    outlineVariant = Color(0x332D414F),
    inverseSurface = Color(0xFFF2E8D5),
    inverseOnSurface = Color(0xFF0B1420),
    inversePrimary = Color(0xFF276F6D),
    surfaceTint = Color(0xFF4FB3B0),
    surfaceContainerLowest = Color(0xFF09111B),
    surfaceContainerLow = Color(0xFF111D29),
    surfaceContainer = Color(0xFF16222F),
    surfaceContainerHigh = Color(0xFF1B2937),
    surfaceContainerHighest = Color(0xFF243342),
)

private val AncientPaperColor = lightColorScheme(
    primary = Color(0xFF2A7F7A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD0E5DC),
    onPrimaryContainer = Color(0xFF123F3C),
    secondary = Color(0xFF9B7738),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9DAB8),
    onSecondaryContainer = Color(0xFF39290F),
    tertiary = Color(0xFF3F765C),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFB84735),
    errorContainer = Color(0xFFF8DAD3),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF4ECD8),
    onBackground = Color(0xFF1E3650),
    surface = Color(0xFFFFF8E9),
    onSurface = Color(0xFF1E3650),
    surfaceVariant = Color(0xFFEAE0C9),
    onSurfaceVariant = Color(0xFF5D625C),
    outline = Color(0x669B7738),
    outlineVariant = Color(0x33A58C5E),
    inverseSurface = Color(0xFF1E3650),
    inverseOnSurface = Color(0xFFF4ECD8),
    inversePrimary = Color(0xFF9CCFC8),
    surfaceTint = Color(0xFF2A7F7A),
    surfaceContainerLowest = Color(0xFFFFFCF4),
    surfaceContainerLow = Color(0xFFFBF3E2),
    surfaceContainer = Color(0xFFF4ECD8),
    surfaceContainerHigh = Color(0xFFEDE3CD),
    surfaceContainerHighest = Color(0xFFE5D9C0),
)

// Semantic Colors
val colorPing = Color(0xFF55FFC0) // Aurora Mint
val colorPingRed = Color(0xFFFF5369) // Signal Red
val colorConfigType = Color(0xFF85E9D0) // Aurora Teal
val colorFabActive = Color(0xFF54FFC0) // Aurora Mint
val colorFabInactiveLight = Color(0xFFB6C2BB) // Cool Gray
val colorFabInactiveDark = Color(0xFF35443D) // Dark Sage
val dividerColorLight = Color(0xFFE2D7C9) // Light Sand
val dividerColorDark = Color(0xFF293B36) // Aurora Divider
val colorSelectedGlow = Color(0xFF54FFC0) // Aurora selected-card glow

// Toast Colors 70%
val toastNormalBgLight = Color(0xB3353A3E) // Dark Gray
val toastNormalBgDark = Color(0xB34A4F54) // Darker Gray
val toastSuccessBg = Color(0xB3388E3C) // Green
val toastErrorBg = Color(0xB3D50000) // Red
val toastInfoBg = Color(0xB33F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    private const val PREF_UI_THEME = "pref_ui_theme"
    private const val PREF_CULTURAL_PATTERN_INTENSITY = "cultural_pattern_intensity"
    private const val PREF_CULTURAL_REDUCED_MOTION = "cultural_reduced_motion"
    private const val PREF_CULTURAL_PERSIAN_DIGITS = "cultural_persian_digits"
    private const val PREF_SCREEN_TRANSITION = "pref_screen_transition"

    private val _persianDigitsEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(PREF_CULTURAL_PERSIAN_DIGITS, true)
    )
    val persianDigitsEnabled: StateFlow<Boolean> = _persianDigitsEnabled.asStateFlow()

    fun setPersianDigitsEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(PREF_CULTURAL_PERSIAN_DIGITS, enabled)
        _persianDigitsEnabled.value = enabled
    }

    private val _patternIntensity = MutableStateFlow(
        (MmkvManager.decodeSettingsString(PREF_CULTURAL_PATTERN_INTENSITY, "5") ?: "5")
            .toIntOrNull()?.coerceIn(0, 100) ?: 5
    )
    val patternIntensity: StateFlow<Int> = _patternIntensity.asStateFlow()

    private val _reducedMotionEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(PREF_CULTURAL_REDUCED_MOTION, false)
    )
    val reducedMotionEnabled: StateFlow<Boolean> = _reducedMotionEnabled.asStateFlow()

    fun setPatternIntensity(value: Int) {
        val safeValue = value.coerceIn(0, 100)
        MmkvManager.encodeSettings(PREF_CULTURAL_PATTERN_INTENSITY, safeValue.toString())
        _patternIntensity.value = safeValue
    }

    fun setReducedMotionEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(PREF_CULTURAL_REDUCED_MOTION, enabled)
        _reducedMotionEnabled.value = enabled
    }

    private fun readThemeMode(): String {
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        // Migrate the former fixed light/dark choices to the new system-default option.
        if (stored == "1" || stored == "2") {
            MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, "0")
            return "0"
        }
        return stored
    }

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _uiTheme = MutableStateFlow(
        MmkvManager.decodeSettingsString(PREF_UI_THEME, "default")?.takeIf {
            it in setOf("default", "radar", "survival", "aurora", "rdrx", "rdrn", "zx")
        } ?: "default"
    )
    val uiTheme: StateFlow<String> = _uiTheme.asStateFlow()

    fun setUiTheme(value: String) {
        val safeValue = value.takeIf { it in setOf("default", "radar", "survival", "aurora", "rdrx", "rdrn", "zx") } ?: "default"
        MmkvManager.encodeSettings(PREF_UI_THEME, safeValue)
        _uiTheme.value = safeValue
    }

    private val _screenTransition = MutableStateFlow(
        MmkvManager.decodeSettingsString(PREF_SCREEN_TRANSITION, "fade")
            ?.takeIf { it in setOf("fade", "slide", "scale") } ?: "fade"
    )
    val screenTransition: StateFlow<String> = _screenTransition.asStateFlow()

    fun setScreenTransition(value: String) {
        val safeValue = value.takeIf { it in setOf("fade", "slide", "scale") } ?: "fade"
        MmkvManager.encodeSettings(PREF_SCREEN_TRANSITION, safeValue)
        _screenTransition.value = safeValue
    }

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value = readThemeMode()
        _uiTheme.value = MmkvManager.decodeSettingsString(PREF_UI_THEME, "default")?.takeIf {
            it in setOf("default", "radar", "survival", "aurora", "rdrx", "rdrn", "zx")
        } ?: "default"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
        _screenTransition.value = MmkvManager.decodeSettingsString(PREF_SCREEN_TRANSITION, "fade")
            ?.takeIf { it in setOf("fade", "slide", "scale") } ?: "fade"
    }
}

private val AppFontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private val defaultTypography = Typography()

val AppTypography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = AppFontFamily),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = AppFontFamily),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = AppFontFamily),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = AppFontFamily),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = AppFontFamily),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = AppFontFamily),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = AppFontFamily),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = AppFontFamily),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = AppFontFamily),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = AppFontFamily),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = AppFontFamily),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = AppFontFamily),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = AppFontFamily),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = AppFontFamily),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = AppFontFamily),
)

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    val uiTheme by ThemeManager.uiTheme.collectAsState()
    return when {
        uiTheme == "radar" || uiTheme == "survival" || uiTheme == "aurora" || uiTheme == "rdrn" || uiTheme == "zx" -> true
        mode == "1" || mode == "4" -> false
        mode in setOf("2", "3") -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
private fun ZxLiquidBackdrop(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF090817), Color(0xFF17102D), Color(0xFF0A1B2A))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val violetCenter = Offset(size.width * 0.16f, size.height * 0.18f)
            val cyanCenter = Offset(size.width * 0.88f, size.height * 0.62f)
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0x665F4BCE), Color.Transparent), center = violetCenter, radius = size.minDimension * 0.72f),
                radius = size.minDimension * 0.72f,
                center = violetCenter
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0x5544D5E8), Color.Transparent), center = cyanCenter, radius = size.minDimension * 0.68f),
                radius = size.minDimension * 0.68f,
                center = cyanCenter
            )
        }
        content()
    }
}
@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val themeMode by ThemeManager.themeMode.collectAsState()
    val uiTheme by ThemeManager.uiTheme.collectAsState()
    val context = LocalContext.current
    val isRadarTheme = uiTheme == "radar"
    val isSurvivalTheme = uiTheme == "survival"
    val isAuroraTheme = uiTheme == "aurora"
    val isRdrnTheme = uiTheme == "rdrn"
    val isZxLiquidTheme = uiTheme == "zx"
    val isPersepolisTheme = themeMode == "4"
    // Persepolis uses a light parchment palette; keep system-bar icons dark there.
    val effectiveDarkTheme = when {
        isPersepolisTheme -> false
        isRadarTheme || isSurvivalTheme || isAuroraTheme || isRdrnTheme || isZxLiquidTheme || themeMode == "3" -> true
        else -> darkTheme
    }
    // Ordinary system appearance is the default; special themes are opt-in.
    val colorScheme = when {
        isRdrnTheme -> com.aras.client.ui.rdrn.RadarNeonColorScheme
        isZxLiquidTheme -> ZxLiquidColor
        isRadarTheme -> RadarColorScheme
        isSurvivalTheme -> SurvivalColorScheme
        isPersepolisTheme -> PersepolisColorScheme
        themeMode == "3" -> CulturalNightColor
        isAuroraTheme && darkTheme -> AuroraDarkColor
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColor
        else -> LightColor
    }
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !effectiveDarkTheme
                isAppearanceLightNavigationBars = !effectiveDarkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography
        ) {
            val body: @Composable () -> Unit = {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppSnackbarBridge(controller = snackbarController)
                    content()
                    if (isPersepolisTheme) PersepolisOverlay()
                    if (isSurvivalTheme) SurvivalOverlay()
                    AppSnackbarHost(hostState = snackbarController.hostState)
                }
            }
            when {
                uiTheme == "rdrx" -> {
                    val rdrState = rememberRdrThemeState()
                    RdrTheme(rdrState, darkOverride = darkTheme) { RdrBackground { body() } }
                }
                isZxLiquidTheme -> ZxLiquidBackdrop { body() }
                else -> body()
            }
        }
    }
}
