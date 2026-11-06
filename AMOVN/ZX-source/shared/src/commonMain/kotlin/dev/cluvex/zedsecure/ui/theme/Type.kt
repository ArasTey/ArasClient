package dev.cluvex.zedsecure.ui.theme

import dev.cluvex.zedsecure.domain.model.AppDesign

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.shared.resources.estedad_bold
import dev.cluvex.zedsecure.shared.resources.estedad_medium
import dev.cluvex.zedsecure.shared.resources.estedad_regular
import dev.cluvex.zedsecure.shared.resources.estedad_semibold
import dev.cluvex.zedsecure.shared.resources.inter_bold
import dev.cluvex.zedsecure.shared.resources.inter_medium
import dev.cluvex.zedsecure.shared.resources.inter_regular
import dev.cluvex.zedsecure.shared.resources.inter_semibold
import dev.cluvex.zedsecure.shared.resources.interdisplay_bold
import dev.cluvex.zedsecure.shared.resources.interdisplay_semibold
import org.jetbrains.compose.resources.Font

@Composable
fun interFamily() = FontFamily(
    Font(Res.font.inter_regular, FontWeight.Normal),
    Font(Res.font.inter_medium, FontWeight.Medium),
    Font(Res.font.inter_semibold, FontWeight.SemiBold),
    Font(Res.font.inter_bold, FontWeight.Bold),
)

@Composable
fun interDisplayFamily() = FontFamily(
    Font(Res.font.interdisplay_semibold, FontWeight.SemiBold),
    Font(Res.font.interdisplay_bold, FontWeight.Bold),
)

@Composable
fun googleSansFamily() = FontFamily(
    Font(Res.font.googlesans_regular, FontWeight.Normal),
    Font(Res.font.googlesans_medium, FontWeight.Medium),
    Font(Res.font.googlesans_semibold, FontWeight.SemiBold),
    Font(Res.font.googlesans_bold, FontWeight.Bold),
)

@Composable
fun estedadFamily() = FontFamily(
    Font(Res.font.estedad_regular, FontWeight.Normal),
    Font(Res.font.estedad_medium, FontWeight.Medium),
    Font(Res.font.estedad_semibold, FontWeight.SemiBold),
    Font(Res.font.estedad_bold, FontWeight.Bold),
)

private val lineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    family: FontFamily,
    weight: FontWeight,
    size: TextUnit,
    lineHeight: TextUnit,
    tracking: TextUnit = 0.sp,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = tracking,
    lineHeightStyle = lineHeightStyle,
)

private fun TextStyle.emphasized(): TextStyle {
    val heavier = when (fontWeight) {
        FontWeight.Normal -> FontWeight.Medium
        FontWeight.Medium -> FontWeight.SemiBold
        FontWeight.SemiBold -> FontWeight.Bold
        else -> FontWeight.Bold
    }
    val tighter = (letterSpacing.value - 0.2f).sp
    return copy(fontWeight = heavier, letterSpacing = tighter)
}

private fun expressiveTypography(
    displayLarge: TextStyle,
    displayMedium: TextStyle,
    displaySmall: TextStyle,
    headlineLarge: TextStyle,
    headlineMedium: TextStyle,
    headlineSmall: TextStyle,
    titleLarge: TextStyle,
    titleMedium: TextStyle,
    titleSmall: TextStyle,
    bodyLarge: TextStyle,
    bodyMedium: TextStyle,
    bodySmall: TextStyle,
    labelLarge: TextStyle,
    labelMedium: TextStyle,
    labelSmall: TextStyle,
) = Typography(
    displayLarge = displayLarge,
    displayMedium = displayMedium,
    displaySmall = displaySmall,
    headlineLarge = headlineLarge,
    headlineMedium = headlineMedium,
    headlineSmall = headlineSmall,
    titleLarge = titleLarge,
    titleMedium = titleMedium,
    titleSmall = titleSmall,
    bodyLarge = bodyLarge,
    bodyMedium = bodyMedium,
    bodySmall = bodySmall,
    labelLarge = labelLarge,
    labelMedium = labelMedium,
    labelSmall = labelSmall,

    displayLargeEmphasized = displayLarge.emphasized(),
    displayMediumEmphasized = displayMedium.emphasized(),
    displaySmallEmphasized = displaySmall.emphasized(),
    headlineLargeEmphasized = headlineLarge.emphasized(),
    headlineMediumEmphasized = headlineMedium.emphasized(),
    headlineSmallEmphasized = headlineSmall.emphasized(),
    titleLargeEmphasized = titleLarge.emphasized(),
    titleMediumEmphasized = titleMedium.emphasized(),
    titleSmallEmphasized = titleSmall.emphasized(),
    bodyLargeEmphasized = bodyLarge.emphasized(),
    bodyMediumEmphasized = bodyMedium.emphasized(),
    bodySmallEmphasized = bodySmall.emphasized(),
    labelLargeEmphasized = labelLarge.emphasized(),
    labelMediumEmphasized = labelMedium.emphasized(),
    labelSmallEmphasized = labelSmall.emphasized(),
)

@Composable
private fun latinTypography(d: FontFamily = interDisplayFamily(), b: FontFamily = interFamily()): Typography {
    return expressiveTypography(
        displayLarge = style(d, FontWeight.Bold, 57.sp, 64.sp, (-0.5).sp),
        displayMedium = style(d, FontWeight.Bold, 45.sp, 52.sp, (-0.25).sp),
        displaySmall = style(d, FontWeight.SemiBold, 36.sp, 44.sp),
        headlineLarge = style(d, FontWeight.SemiBold, 32.sp, 40.sp),
        headlineMedium = style(d, FontWeight.SemiBold, 28.sp, 36.sp),
        headlineSmall = style(d, FontWeight.SemiBold, 24.sp, 32.sp),
        titleLarge = style(b, FontWeight.SemiBold, 22.sp, 28.sp),
        titleMedium = style(b, FontWeight.SemiBold, 16.sp, 24.sp, 0.1.sp),
        titleSmall = style(b, FontWeight.Medium, 14.sp, 20.sp, 0.1.sp),
        bodyLarge = style(b, FontWeight.Normal, 16.sp, 24.sp, 0.5.sp),
        bodyMedium = style(b, FontWeight.Normal, 14.sp, 20.sp, 0.25.sp),
        bodySmall = style(b, FontWeight.Normal, 12.sp, 16.sp, 0.4.sp),
        labelLarge = style(b, FontWeight.SemiBold, 14.sp, 20.sp, 0.1.sp),
        labelMedium = style(b, FontWeight.Medium, 12.sp, 16.sp, 0.5.sp),
        labelSmall = style(b, FontWeight.Medium, 11.sp, 16.sp, 0.5.sp),
    )
}

@Composable
private fun persianTypography(): Typography {
    val f = estedadFamily()
    return expressiveTypography(
        displayLarge = style(f, FontWeight.Bold, 54.sp, 68.sp),
        displayMedium = style(f, FontWeight.Bold, 43.sp, 56.sp),
        displaySmall = style(f, FontWeight.SemiBold, 35.sp, 46.sp),
        headlineLarge = style(f, FontWeight.SemiBold, 31.sp, 42.sp),
        headlineMedium = style(f, FontWeight.SemiBold, 27.sp, 38.sp),
        headlineSmall = style(f, FontWeight.SemiBold, 23.sp, 34.sp),
        titleLarge = style(f, FontWeight.SemiBold, 21.sp, 30.sp),
        titleMedium = style(f, FontWeight.SemiBold, 16.sp, 26.sp),
        titleSmall = style(f, FontWeight.Medium, 14.sp, 22.sp),
        bodyLarge = style(f, FontWeight.Normal, 16.sp, 28.sp),
        bodyMedium = style(f, FontWeight.Normal, 14.sp, 24.sp),
        bodySmall = style(f, FontWeight.Normal, 12.sp, 20.sp),
        labelLarge = style(f, FontWeight.SemiBold, 14.sp, 22.sp),
        labelMedium = style(f, FontWeight.Medium, 12.sp, 18.sp),
        labelSmall = style(f, FontWeight.Medium, 11.sp, 18.sp),
    )
}

@Composable
fun typographyFor(language: String, design: AppDesign = AppDesign.Old): Typography = when {
    language == "fa" -> persianTypography()
    design == AppDesign.Liquid && language != "ru" -> googleSansFamily().let { latinTypography(it, it) }
    else -> latinTypography()
}
