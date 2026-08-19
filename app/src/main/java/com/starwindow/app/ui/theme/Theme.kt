package com.starwindow.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Deliberately dark and low on blue: the app is used at night, next to eyes that are trying to stay
 * dark adapted. Red is reserved for the overlay so it never competes with the UI chrome.
 */
object StarWindowColors {
    val Night = Color(0xFF07080F)
    val NightSurface = Color(0xFF12141F)
    val NightSurfaceHigh = Color(0xFF1C1F2E)
    val Starlight = Color(0xFFD8DCF0)
    val Muted = Color(0xFF8C93AE)

    /** Overlay accents, chosen to stay readable on top of a nearly black camera image. */
    val WindowStroke = Color(0xFF62E8B4)
    val WindowFill = Color(0x2262E8B4)
    val AnchorPoint = Color(0xFFFFB74D)
    val Crosshair = Color(0xFFFF6B6B)
    val Graticule = Color(0x55B0BEC5)
    val CatalogMarker = Color(0xFF9FD8FF)
}

private val DarkScheme = darkColorScheme(
    primary = StarWindowColors.WindowStroke,
    onPrimary = StarWindowColors.Night,
    secondary = StarWindowColors.AnchorPoint,
    onSecondary = StarWindowColors.Night,
    background = StarWindowColors.Night,
    onBackground = StarWindowColors.Starlight,
    surface = StarWindowColors.NightSurface,
    onSurface = StarWindowColors.Starlight,
    surfaceVariant = StarWindowColors.NightSurfaceHigh,
    onSurfaceVariant = StarWindowColors.Muted,
    error = StarWindowColors.Crosshair,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00695C),
    secondary = Color(0xFFB26500),
)

@Composable
fun StarWindowTheme(
    // The app defaults to its dark scheme even in a light system theme; see the note above.
    forceDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val useDark = forceDark || isSystemInDarkTheme()
    val colorScheme = if (useDark) DarkScheme else LightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
