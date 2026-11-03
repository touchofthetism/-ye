package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OculaDarkColorScheme = darkColorScheme(
    primary = OpticalCyan,
    onPrimary = Color(0xFF00262C),
    primaryContainer = OpticalCyanDim,
    onPrimaryContainer = TextPrimary,
    secondary = ValidationEmerald,
    onSecondary = Color(0xFF00291B),
    secondaryContainer = ValidationEmeraldDim,
    onSecondaryContainer = TextPrimary,
    tertiary = ThermalAmber,
    onTertiary = Color(0xFF2A1800),
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    error = AlertCoral,
    onError = Color.White
)

@Composable
fun OculaGazeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = OculaDarkColorScheme,
        typography = Typography,
        content = content
    )
}
