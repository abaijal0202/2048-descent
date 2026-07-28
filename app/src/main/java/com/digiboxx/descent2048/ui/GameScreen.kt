package com.digiboxx.descent2048.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digiboxx.descent2048.game.BoardSnapshot
import com.digiboxx.descent2048.game.COLS
import com.digiboxx.descent2048.game.DANGER_CLEARANCE
import com.digiboxx.descent2048.game.GameStatus
import com.digiboxx.descent2048.game.HudTimers
import com.digiboxx.descent2048.game.POWER_MAX_CHARGES
import com.digiboxx.descent2048.game.ROWS
import com.digiboxx.descent2048.game.SlideDirection
import com.digiboxx.descent2048.ui.theme.AccentCyan
import com.digiboxx.descent2048.ui.theme.AccentPink
import com.digiboxx.descent2048.ui.theme.BgPanel
import com.digiboxx.descent2048.ui.theme.BgPanel2
import com.digiboxx.descent2048.ui.theme.BoardBg
import com.digiboxx.descent2048.ui.theme.TextLight
import com.digiboxx.descent2048.ui.theme.TextMuted
import com.digiboxx.descent2048.ui.theme.TrophyGold
import com.digiboxx.descent2048.ui.theme.tileBackground
import com.digiboxx.descent2048.ui.theme.tileForeground

@Composable
fun GameScreen(
    snapshot: BoardSnapshot,
    hud: HudTimers,
    highScore: Int,
    deleteArmed: Boolean,
    hapticsEnabled: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMove: (Int) -> Unit,
    onMoveTo: (Int) -> Unit,
    onHardDrop: () -> Unit,
    onSoftDrop: (Boolean) -> Unit,
    onArmDeleteRow: () -> Unit,
    onDeleteRowAt: (Int) -> Unit,
    canDeleteRow: (Int) -> Boolean,
    onSlow: () -> Unit,
    onPlan: () -> Unit,
    onSlide: (SlideDirection) -> Unit,
    onToggleHaptics: () -> Unit,
    currentColumn: () -> Int
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A1F3A), Color(0xFF131625))
                )
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Everything above and below the board needs roughly this much room. Sizing the
        // cell from what is left is what keeps the controls on screen on small phones —
        // a fixed cell size pushed them below the fold on the first build.
        val reservedHeight = 250.dp
        val availableHeight = maxHeight - reservedHeight
        val availableWidth = minOf(maxWidth, 400.dp) - 28.dp

        val cellFromWidth = availableWidth / COLS
        val cellFromHeight = availableHeight / ROWS
        // The upper bound has to clear what a 6 x 10 board can actually use, otherwise
        // the cap re-binds and the whole point of the smaller grid is lost.
        val cellSize = minOf(cellFromWidth, cellFromHeight).coerceIn(18.dp, 64.dp)

        val planning = snapshot.status == GameStatus.PLANNING

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HeaderRow(
                snapshot = snapshot,
                highScore = highScore,
                onPause = onPause
            )
            NextAndSpeedRow(snapshot = snapshot)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgPanel2)
                    .padding(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(BoardBg)
                ) {
                    BoardCanvas(
                        snapshot = snapshot,
                        cellSize = cellSize,
                        deleteArmed = deleteArmed,
                        planning = planning,
                        onMoveTo = onMoveTo,
                        onHardDrop = onHardDrop,
                        onDeleteRowAt = onDeleteRowAt,
                        onSlide = onSlide,
                        canDeleteRow = canDeleteRow,
                        currentColumn = currentColumn
                    )
                }

                if (deleteArmed && snapshot.status == GameStatus.PLAYING) {
                    Text(
                        text = "TAP A ROW TO CLEAR IT",
                        color = AccentPink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0A0C18).copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (planning) {
                    Text(
                        text = "PLAN · ${hud.planActiveRemainingSec}s · SWIPE TO SLIDE",
                        color = TrophyGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0A0C18).copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (!deleteArmed && !planning &&
                    snapshot.status == GameStatus.PLAYING &&
                    snapshot.spawnClearance <= DANGER_CLEARANCE
                ) {
                    Text(
                        text = "STACK TOO HIGH",
                        color = AccentPink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0A0C18).copy(alpha = 0.8f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                when (snapshot.status) {
                    GameStatus.READY -> Overlay(
                        modifier = Modifier.matchParentSize(),
                        title = "2048 DESCENT",
                        body = "Drag the falling number left or right. Matching numbers merge when they touch — sideways as well as down. Swipe down to drop fast.",
                        actionLabel = "Start Game",
                        onAction = onStart
                    )
                    GameStatus.PAUSED -> Overlay(
                        modifier = Modifier.matchParentSize(),
                        title = "Paused",
                        body = "Score ${snapshot.score} · Top tile ${snapshot.bestTile}",
                        actionLabel = "Resume",
                        onAction = onResume,
                        secondaryLabel = if (hapticsEnabled) "Haptics off" else "Haptics on",
                        onSecondary = onToggleHaptics,
                        tertiaryLabel = "New game",
                        onTertiary = onStart
                    )
                    GameStatus.CELEBRATING -> Overlay(
                        modifier = Modifier.matchParentSize(),
                        title = "${snapshot.trophies.lastOrNull() ?: 2048}!",
                        body = trophyBody(snapshot),
                        actionLabel = null,
                        onAction = {}
                    )
                    GameStatus.GAME_OVER -> Overlay(
                        modifier = Modifier.matchParentSize(),
                        title = "Game Over",
                        body = "Score ${snapshot.score} · Best tile ${snapshot.bestTile}" +
                            if (snapshot.trophies.isNotEmpty()) {
                                "\nTrophies ${snapshot.trophies.joinToString(", ")}"
                            } else "",
                        actionLabel = "Play Again",
                        onAction = onStart
                    )
                    GameStatus.PLAYING, GameStatus.PLANNING -> Unit
                }
            }

            PowersRow(
                snapshot = snapshot,
                hud = hud,
                deleteArmed = deleteArmed,
                planning = planning,
                onArmDeleteRow = onArmDeleteRow,
                onSlow = onSlow,
                onPlan = onPlan
            )

            if (planning) {
                SlideControlsRow(onSlide = onSlide)
            } else {
                ControlsRow(
                    onMove = onMove,
                    onHardDrop = onHardDrop,
                    onSoftDrop = onSoftDrop
                )
            }
        }
    }
}

private fun trophyBody(snapshot: BoardSnapshot): String {
    val next = snapshot.nextTrophyValue
    val multiplier = "%.1fx".format(snapshot.scoreMultiplier)
    return if (next != null) {
        "Board cleared and the trophy is locked in. Every merge now scores $multiplier. Next goal: $next."
    } else {
        "The ladder is complete. Every merge scores $multiplier — see how long you can hold on."
    }
}

@Composable
private fun HeaderRow(snapshot: BoardSnapshot, highScore: Int, onPause: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgPanel)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LabelledValue(label = "SCORE", value = snapshot.score.toString())
        LabelledValue(label = "BEST", value = highScore.toString())
        LabelledValue(
            label = "TOP",
            value = if (snapshot.bestTile > 0) snapshot.bestTile.toString() else "—"
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BgPanel2)
                .pointerInput(Unit) { detectTapGestures { onPause() } }
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .semantics { contentDescription = "Pause game" }
        ) {
            Text(text = "‖", color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(5.dp))
        Text(text = value, color = TextLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NextAndSpeedRow(snapshot: BoardSnapshot) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(BgPanel)
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(text = "NEXT", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            for (value in snapshot.nextValues.take(3)) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tileBackground(value)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = value.toString(),
                        color = tileForeground(value),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(BgPanel)
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(
                text = "GOAL ${snapshot.nextTrophyValue ?: "—"}",
                color = TrophyGold,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "x%.2f · %.1f×".format(
                    snapshot.speedMultiplier,
                    snapshot.scoreMultiplier
                ),
                color = AccentCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PowersRow(
    snapshot: BoardSnapshot,
    hud: HudTimers,
    deleteArmed: Boolean,
    planning: Boolean,
    onArmDeleteRow: () -> Unit,
    onSlow: () -> Unit,
    onPlan: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PowerButton(
            modifier = Modifier.weight(1f),
            label = if (deleteArmed) "CANCEL" else "DELETE ROW",
            charges = snapshot.deleteCharges,
            subtitle = if (deleteArmed) {
                "pick a row"
            } else {
                regenLabel(snapshot.deleteCharges, hud.deleteRegenRemainingSec)
            },
            enabled = snapshot.deleteCharges > 0 && snapshot.status == GameStatus.PLAYING,
            highlighted = deleteArmed,
            onClick = onArmDeleteRow
        )
        PowerButton(
            modifier = Modifier.weight(1f),
            label = "SLOW 30s",
            charges = snapshot.slowCharges,
            subtitle = if (hud.slowActiveRemainingSec > 0) {
                "active ${formatCountdown(hud.slowActiveRemainingSec)}"
            } else {
                regenLabel(snapshot.slowCharges, hud.slowRegenRemainingSec)
            },
            enabled = snapshot.slowCharges > 0 && snapshot.status == GameStatus.PLAYING,
            highlighted = hud.slowActiveRemainingSec > 0,
            onClick = onSlow
        )
        PowerButton(
            modifier = Modifier.weight(1f),
            label = "PLAN 15s",
            charges = snapshot.planCharges,
            subtitle = if (planning) {
                "${hud.planActiveRemainingSec}s left"
            } else {
                regenLabel(snapshot.planCharges, hud.planRegenRemainingSec)
            },
            enabled = snapshot.planCharges > 0 && snapshot.status == GameStatus.PLAYING,
            highlighted = planning,
            onClick = onPlan
        )
    }
}

/**
 * Replaces the movement controls during a Plan window.
 *
 * The board takes swipes, but four explicit arrows make the mode's rules obvious the
 * first time a player triggers it — and a tap is more precise than a swipe when the
 * clock is running.
 */
@Composable
private fun SlideControlsRow(onSlide: (SlideDirection) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ControlButton(
            text = "◀",
            description = "Slide board left",
            modifier = Modifier.weight(1f)
        ) { onSlide(SlideDirection.LEFT) }
        ControlButton(
            text = "▲",
            description = "Slide board up",
            modifier = Modifier.weight(1f)
        ) { onSlide(SlideDirection.UP) }
        ControlButton(
            text = "▼",
            description = "Slide board down",
            modifier = Modifier.weight(1f)
        ) { onSlide(SlideDirection.DOWN) }
        ControlButton(
            text = "▶",
            description = "Slide board right",
            modifier = Modifier.weight(1f)
        ) { onSlide(SlideDirection.RIGHT) }
    }
}

private fun regenLabel(charges: Int, remainingSec: Long): String =
    if (charges < POWER_MAX_CHARGES && remainingSec > 0) {
        "+1 in ${formatCountdown(remainingSec)}"
    } else " "

private fun formatCountdown(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}

@Composable
private fun PowerButton(
    modifier: Modifier,
    label: String,
    charges: Int,
    subtitle: String,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) BgPanel2 else BgPanel)
            .pointerInput(enabled) {
                detectTapGestures(onTap = { if (enabled) onClick() })
            }
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "$label, $charges of $POWER_MAX_CHARGES charges" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = if (enabled) TextMuted else TextMuted.copy(alpha = 0.4f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "$charges/$POWER_MAX_CHARGES",
            color = if (enabled) AccentCyan else AccentCyan.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(text = subtitle, color = TextMuted, fontSize = 8.sp)
    }
}

@Composable
private fun ControlsRow(
    onMove: (Int) -> Unit,
    onHardDrop: () -> Unit,
    onSoftDrop: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ControlButton(
            text = "◀",
            description = "Move left",
            modifier = Modifier.weight(1f)
        ) { onMove(-1) }
        HoldButton(
            text = "▼",
            description = "Soft drop, hold to fall faster",
            modifier = Modifier.weight(1f),
            onHoldChange = onSoftDrop
        )
        ControlButton(
            text = "▶",
            description = "Move right",
            modifier = Modifier.weight(1f)
        ) { onMove(1) }
        ControlButton(
            text = "DROP",
            description = "Hard drop",
            modifier = Modifier.weight(1.4f),
            onClick = onHardDrop
        )
    }
}

@Composable
private fun ControlButton(
    text: String,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(BgPanel)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = TextLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Soft drop needs press-and-hold, so it reports both edges of the gesture. */
@Composable
private fun HoldButton(
    text: String,
    description: String,
    modifier: Modifier,
    onHoldChange: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (pressed) BgPanel2 else BgPanel)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onHoldChange(true)
                        // Suspends until the finger lifts or the gesture is cancelled,
                        // so soft drop can never get stuck on.
                        tryAwaitRelease()
                        pressed = false
                        onHoldChange(false)
                    }
                )
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = TextLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Overlay(
    modifier: Modifier,
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    tertiaryLabel: String? = null,
    onTertiary: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF0A0C18).copy(alpha = 0.9f))
            // Swallow taps so drags cannot reach the board underneath the overlay.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = title,
                color = TextLight,
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = body,
                color = TextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = Color(0xFF101225)
                    ),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Text(text = actionLabel, fontWeight = FontWeight.Bold)
                }
            }
            if (secondaryLabel != null && onSecondary != null) {
                Text(
                    text = secondaryLabel,
                    color = AccentCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .pointerInput(Unit) { detectTapGestures { onSecondary() } }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
            if (tertiaryLabel != null && onTertiary != null) {
                Text(
                    text = tertiaryLabel,
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .pointerInput(Unit) { detectTapGestures { onTertiary() } }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }
}
