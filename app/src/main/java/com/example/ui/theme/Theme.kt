package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val OculaDarkColorScheme = darkColorScheme(
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
    // AlertDialog uses surfaceContainerHigh and the unchecked Switch track uses surfaceContainerHighest;
    // left unset they fall back to the M3 baseline purple-grey (#2B2930 / #36343B).
    surfaceDim = ObsidianBackground,
    surfaceBright = SlateBorder,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = SlateContainerLow,
    surfaceContainer = SlateSurface,
    surfaceContainerHigh = SlateContainerHigh,
    surfaceContainerHighest = SlateSurfaceVariant,
    // The unchecked Switch draws its handle and border in outline: it must stay visible on the surfaces
    // and on the track. Decorative hairlines can use outlineVariant.
    outline = SlateOutline,
    outlineVariant = SlateBorder,
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
