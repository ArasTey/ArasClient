package dev.cluvex.zedsecure.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.animateColorScheme
import dev.cluvex.zedsecure.domain.model.AppDesign
import dev.cluvex.zedsecure.domain.model.ColorContrast
import dev.cluvex.zedsecure.domain.model.ColorStyle

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZedSecureTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,

    accentColor: Int = 0,
    amoledBlack: Boolean = false,

    colorStyle: ColorStyle = ColorStyle.ZedSecure,
    colorContrast: ColorContrast = ColorContrast.Standard,
    animateColors: Boolean = true,

    design: AppDesign = AppDesign.Liquid,

    languageTag: String = "en",

    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val system = if (dynamicColor) dynamicColorSchemeOrNull(darkTheme) else null
    val scheme = (system ?: rememberZedColorScheme(darkTheme, accentColor, colorStyle, colorContrast))
        .let { if (amoledBlack) it.amoled(darkTheme) else it }
    val colorScheme = if (animateColors) {
        animateColorScheme(scheme, animationSpec = { spring(stiffness = Spring.StiffnessMediumLow) })
    } else {
        scheme
    }

    val density = LocalDensity.current
    val scaled = remember(density, fontScale) {
        if (fontScale == 1f) density else Density(density.density, density.fontScale * fontScale)
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = if (design == AppDesign.Liquid) LiquidShapes else ZedShapes,
        typography = typographyFor(languageTag, design),
    ) {
        CompositionLocalProvider(LocalDensity provides scaled, LocalAppDesign provides design, content = content)
    }
}

@Composable
fun rememberZedColorScheme(
    dark: Boolean,
    accent: Int,
    style: ColorStyle,
    contrast: ColorContrast,
): ColorScheme = remember(dark, accent, style, contrast) { zedColorScheme(dark, accent, style, contrast) }

fun zedColorScheme(dark: Boolean, accent: Int, style: ColorStyle, contrast: ColorContrast): ColorScheme {
    val seed = accentSeed(accent)
    return if (style == ColorStyle.ZedSecure) {
        dynamicColorScheme(
            seedColor = seed,
            isDark = dark,
            primary = seed,
            secondary = BrandTeal,
            tertiary = BrandRose,
            style = PaletteStyle.TonalSpot,
            contrastLevel = contrast.level,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
    } else {
        dynamicColorScheme(
            seedColor = seed,
            isDark = dark,
            style = style.palette,
            contrastLevel = contrast.level,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
    }
}

fun accentSeed(index: Int): Color = AccentPresets.getOrNull(index)?.light ?: BrandIris

val ColorStyle.palette: PaletteStyle
    get() = when (this) {
        ColorStyle.ZedSecure, ColorStyle.TonalSpot -> PaletteStyle.TonalSpot
        ColorStyle.Vibrant -> PaletteStyle.Vibrant
        ColorStyle.Expressive -> PaletteStyle.Expressive
        ColorStyle.Rainbow -> PaletteStyle.Rainbow
        ColorStyle.FruitSalad -> PaletteStyle.FruitSalad
        ColorStyle.Fidelity -> PaletteStyle.Fidelity
        ColorStyle.Neutral -> PaletteStyle.Neutral
        ColorStyle.Monochrome -> PaletteStyle.Monochrome
    }

val LocalAppDesign = staticCompositionLocalOf { AppDesign.Liquid }

@Composable
expect fun dynamicColorSchemeOrNull(darkTheme: Boolean): ColorScheme?
