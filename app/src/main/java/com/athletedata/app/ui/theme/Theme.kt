package com.athletedata.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary              = LightAccent,
    onPrimary            = LightSurface,
    primaryContainer     = LightSurface,
    onPrimaryContainer   = LightTextPrimary,
    secondary            = LightTextSecondary,
    onSecondary          = LightSurface,
    secondaryContainer   = LightSurface,
    onSecondaryContainer = LightTextPrimary,
    tertiary             = LightTextTertiary,
    onTertiary           = LightSurface,
    background           = LightBackground,
    onBackground         = LightTextPrimary,
    surface              = LightSurface,
    onSurface            = LightTextPrimary,
    surfaceVariant       = LightSurfaceHigh,
    onSurfaceVariant     = LightTextSecondary,
    outline              = LightTextTertiary,
    outlineVariant       = LightTextTertiary,
    surfaceTint          = Color.Transparent,
    scrim                = Color(0x52000000),
)

private val DarkColorScheme = darkColorScheme(
    primary              = DarkAccent,
    onPrimary            = DarkBackground,
    primaryContainer     = DarkSurface,
    onPrimaryContainer   = DarkTextPrimary,
    secondary            = DarkTextSecondary,
    onSecondary          = DarkBackground,
    secondaryContainer   = DarkSurface,
    onSecondaryContainer = DarkTextPrimary,
    tertiary             = DarkTextTertiary,
    onTertiary           = DarkBackground,
    background           = DarkBackground,
    onBackground         = DarkTextPrimary,
    surface              = DarkSurface,
    onSurface            = DarkTextPrimary,
    surfaceVariant       = DarkSurfaceHigh,
    onSurfaceVariant     = DarkTextSecondary,
    outline              = DarkTextTertiary,
    outlineVariant       = DarkTextTertiary,
    surfaceTint          = Color.Transparent,
    scrim                = Color(0x52000000),
)

@Composable
fun AthleteDataAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography  = Typography,
        content     = content
    )
}
