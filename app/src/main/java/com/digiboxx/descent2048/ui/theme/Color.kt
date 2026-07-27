package com.digiboxx.descent2048.ui.theme

import androidx.compose.ui.graphics.Color

val BgDeep = Color(0xFF131625)
val BgGradientTop = Color(0xFF1A1F3A)
val BgPanel = Color(0xFF1C2138)
val BgPanel2 = Color(0xFF242A47)
val BoardBg = Color(0xFF0D1020)
val AccentCyan = Color(0xFF5EEAD4)
val AccentPink = Color(0xFFF472B6)
val TextLight = Color(0xFFF4F6FB)
val TextMuted = Color(0xFF8B93B0)
val TrophyGold = Color(0xFFFACC15)

/**
 * Tile colours run cool to warm as values climb, so a glance at the board reads as a
 * heat map: the hot corner is where the big numbers are.
 */
private val tileFills: Map<Int, Pair<Color, Color>> = mapOf(
    2 to (Color(0xFF6EE7B7) to Color(0xFF0B3B2E)),
    4 to (Color(0xFF5EEAD4) to Color(0xFF0B3B36)),
    8 to (Color(0xFF38BDF8) to Color(0xFF082A3D)),
    16 to (Color(0xFF818CF8) to Color(0xFF1A1A45)),
    32 to (Color(0xFFA78BFA) to Color(0xFF241A45)),
    64 to (Color(0xFFE879F9) to Color(0xFF3D0A3D)),
    128 to (Color(0xFFF472B6) to Color(0xFF3D0A22)),
    256 to (Color(0xFFFB7185) to Color(0xFF3D0A10)),
    512 to (Color(0xFFFB923C) to Color(0xFF3D1A00)),
    1024 to (Color(0xFFFBBF24) to Color(0xFF3D2A00)),
    2048 to (Color(0xFFFACC15) to Color(0xFF3D3000))
)

private val fallbackFill = Color(0xFFF4F6FB) to Color(0xFF131625)

fun tileBackground(value: Int): Color = (tileFills[value] ?: fallbackFill).first

fun tileForeground(value: Int): Color = (tileFills[value] ?: fallbackFill).second
