package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = EmeraldOnPrimary,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldOnContainer,
    secondary = SaffronGold,
    onSecondary = EmeraldOnPrimary,
    secondaryContainer = SaffronContainer,
    onSecondaryContainer = SaffronOnContainer,
    error = HalKhataCrimson,
    onError = HalKhataOnCrimson,
    errorContainer = HalKhataContainer,
    onErrorContainer = HalKhataOnContainer,
    background = WarmIvoryBackground,
    onBackground = InkText,
    surface = WarmPaperSurface,
    onSurface = InkText,
    surfaceVariant = WarmSurfaceVariant,
    onSurfaceVariant = MutedInk,
    outline = SubtleOutline
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkEmeraldPrimary,
    onPrimary = DarkEmeraldOnPrimary,
    primaryContainer = DarkEmeraldContainer,
    onPrimaryContainer = DarkEmeraldOnContainer,
    secondary = DarkSaffron,
    onSecondary = DarkEmeraldOnPrimary,
    secondaryContainer = DarkSaffronContainer,
    onSecondaryContainer = SaffronContainer,
    error = DarkHalKhataCrimson,
    onError = DarkHalKhataContainer,
    errorContainer = DarkHalKhataContainer,
    onErrorContainer = DarkHalKhataOnContainer,
    background = DarkSlateBackground,
    onBackground = DarkInkText,
    surface = DarkCardSurface,
    onSurface = DarkInkText,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkMutedInk,
    outline = DarkOutline
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun BakirKhataTheme(
    themeMode: String = "SYSTEM",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
