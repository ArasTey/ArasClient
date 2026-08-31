package com.aras.client.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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

private val DarkColor = darkColorScheme(
    primary = Color(0xFF4FB3F0), // Light Aras Blue
    onPrimary = Color(0xFF04253D), // Deep Navy
    primaryContainer = Color(0xFF0B5AA6), // Deep Blue
    onPrimaryContainer = Color(0xFFD3E9FB), // Pale Blue
    secondary = Color(0xFF7FC8F2), // Light Sky
    onSecondary = Color(0xFF04253D), // Deep Navy
    secondaryContainer = Color(0xFF0B4A68), // Deep Sky
    onSecondaryContainer = Color(0xFFD8EFFC), // Pale Sky
    tertiary = Color(0xFFA9C4F9), // Light Chip Blue
    onTertiary = Color(0xFF0F1D36), // Deep Navy
    tertiaryContainer = Color(0xFF2C4370), // Navy
    onTertiaryContainer = Color(0xFFDCE6FB), // Pale Blue
    error = Color(0xFFFFB4AB), // Light Red
    errorContainer = Color(0xFF93000A), // Dark Red
    onError = Color(0xFF690005), // Deep Red
    onErrorContainer = Color(0xFFFFDAD6), // Light Red
    background = Color(0xFF222226), // Charcoal
    onBackground = Color(0xFFE8E2DA), // Warm Light
    surface = Color(0xFF26262B), // Charcoal Surface
    onSurface = Color(0xFFE8E2DA), // Warm Light
    surfaceVariant = Color(0xFF3A3A40), // Dark Gray
    onSurfaceVariant = Color(0xFFD3C7BA), // Warm Light Gray
    outline = Color(0xFF9C8F82), // Warm Gray
    outlineVariant = Color(0xFF3A3A40), // Dark Gray
    inverseSurface = Color(0xFFE8E2DA), // Warm Light
    inverseOnSurface = Color(0xFF222226), // Charcoal
    inversePrimary = Color(0xFF0284C7), // Aras Blue
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF4FB3F0), // Light Blue
    surfaceContainerLowest = Color(0xFF1D1D21), // Near Charcoal
    surfaceContainerLow = Color(0xFF2A2A2F), // Charcoal
    surfaceContainer = Color(0xFF2E2E34), // Charcoal
    surfaceContainerHigh = Color(0xFF39393F), // Gray Charcoal
    surfaceContainerHighest = Color(0xFF45454B), // Gray
)

// Semantic Colors
val colorPing = Color(0xFF34A853) // Green
val colorPingRed = Color(0xFFE1554F) // Soft Red
val colorConfigType = Color(0xFF3D7FF0) // Chip Blue (protocol badges)
val colorFabActive = Color(0xFF0284C7) // Aras Blue
val colorFabInactiveLight = Color(0xFFB9AC9C) // Warm Gray
val colorFabInactiveDark = Color(0xFF4A4A52) // Dark Gray
val dividerColorLight = Color(0xFFE2D7C9) // Light Sand
val dividerColorDark = Color(0xFF3A3A40) // Dark Gray
val colorSelectedGlow = Color(0xFF7C5CFF) // Violet glow for selected card

// Toast Colors 70%
val toastNormalBgLight = Color(0xB3353A3E) // Dark Gray
val toastNormalBgDark = Color(0xB34A4F54) // Darker Gray
val toastSuccessBg = Color(0xB3388E3C) // Green
val toastErrorBg = Color(0xB3D50000) // Red
val toastInfoBg = Color(0xB33F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

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
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
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
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
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
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
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
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
