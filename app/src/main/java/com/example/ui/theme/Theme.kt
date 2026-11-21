package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class TradingExtendedColors(
    val bullish: Color,
    val bullishContainer: Color,
    val bearish: Color,
    val bearishContainer: Color,
    val amber: Color,
    val amberContainer: Color,
    val violet: Color,
    val violetContainer: Color,
    val cryptoCyan: Color,
    val border: Color,
    val cardElevated: Color
)

private val DarkExtendedColors = TradingExtendedColors(
    bullish = BullishEmerald,
    bullishContainer = BullishContainer,
    bearish = BearishCrimson,
    bearishContainer = BearishContainer,
    amber = AmberSignal,
    amberContainer = AmberContainer,
    violet = VioletTrail,
    violetContainer = VioletContainer,
    cryptoCyan = CyanCrypto,
    border = HairlineBorder,
    cardElevated = SlateSurfaceElevated
)

private val LightExtendedColors = TradingExtendedColors(
    bullish = BullishEmeraldLight,
    bullishContainer = Color(0xFFD1FAE5),
    bearish = BearishCrimsonLight,
    bearishContainer = Color(0xFFFEE2E2),
    amber = Color(0xFFD97706),
    amberContainer = Color(0xFFFEF3C7),
    violet = Color(0xFF7C3AED),
    violetContainer = Color(0xFFEDE9FE),
    cryptoCyan = Color(0xFF0891B2),
    border = LightBorder,
    cardElevated = SoftSlateSurface
)

val LocalTradingColors = staticCompositionLocalOf { DarkExtendedColors }

private val DarkColorScheme = darkColorScheme(
    primary = CobaltPrimary,
    onPrimary = Color.White,
    primaryContainer = CobaltContainer,
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = BullishEmerald,
    onSecondary = Color(0xFF002419),
    secondaryContainer = BullishContainer,
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = VioletTrail,
    onTertiary = Color(0xFF1E103C),
    tertiaryContainer = VioletContainer,
    onTertiaryContainer = Color(0xFFEDE9FE),
    background = ObsidianBg,
    onBackground = Color(0xFFF1F5F9),
    surface = SlateSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = LightMutedText,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateSurfaceElevated,
    surfaceContainerHigh = SlateSurfaceHighlight,
    outline = HairlineBorder,
    outlineVariant = Color(0xFF1F2736),
    error = BearishCrimson,
    onError = Color.White,
    errorContainer = BearishContainer,
    onErrorContainer = Color(0xFFFECDD3)
)

private val LightColorScheme = lightColorScheme(
    primary = CobaltPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = BullishEmeraldLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF4C1D95),
    background = PaperWhiteBg,
    onBackground = DeepInkText,
    surface = CardWhiteSurface,
    onSurface = DeepInkText,
    surfaceVariant = SoftSlateSurface,
    onSurfaceVariant = MutedSlateText,
    surfaceContainerLowest = CardWhiteSurface,
    surfaceContainerLow = PaperWhiteBg,
    surfaceContainer = SoftSlateSurface,
    surfaceContainerHigh = Color(0xFFE2E8F0),
    outline = LightBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = BearishCrimsonLight,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

val PaperPulseShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun PaperPulseTheme(
    darkTheme: Boolean = true, // Precision trading terminal defaults to dark mode, supports light toggle
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalTradingColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PaperPulseTypography,
            shapes = PaperPulseShapes,
            content = content
        )
    }
}
