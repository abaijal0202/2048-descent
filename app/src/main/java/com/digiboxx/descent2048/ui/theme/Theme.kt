package com.digiboxx.descent2048.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The game is always dark. A light variant would wash out the tile palette, which
 * relies on saturated fills against a near-black board, so the system dark-mode
 * setting is deliberately not consulted.
 */
private val DescentColors = darkColorScheme(
    primary = AccentCyan,
    onPrimary = BgDeep,
    secondary = AccentPink,
    onSecondary = BgDeep,
    background = BgDeep,
    onBackground = TextLight,
    surface = BgPanel,
    onSurface = TextLight,
    surfaceVariant = BgPanel2,
    onSurfaceVariant = TextMuted
)

@Composable
fun Descent2048Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DescentColors,
        typography = DescentTypography,
        content = content
    )
}
