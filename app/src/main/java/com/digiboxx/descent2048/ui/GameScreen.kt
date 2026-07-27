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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digiboxx.descent2048.game.BoardSnapshot
import com.digiboxx.descent2048.game.COLS
import com.digiboxx.descent2048.game.GameStatus
import com.digiboxx.descent2048.game.POWER_MAX_CHARGES
import com.digiboxx.descent2048.game.ROWS
import com.digiboxx.descent2048.ui.theme.AccentCyan
import com.digiboxx.descent2048.ui.theme.BgPanel
import com.digiboxx.descent2048.ui.theme.BgPanel2
import com.digiboxx.descent2048.ui.theme.BoardBg
import com.digiboxx.descent2048.ui.theme.TextLight
import com.digiboxx.descent2048.ui.theme.TextMuted
import com.digiboxx.descent2048.ui.theme.tileBackground
import com.digiboxx.descent2048.ui.theme.tileForeground

@Composable
fun GameScreen(
    snapshot: BoardSnapshot,
    highScore: Int,
    onStart: () -> Unit,
    onMove: (Int) -> Unit,
    onMoveTo: (Int) -> Unit,
    onHardDrop: () -> Unit,
    onSoftDrop: (Boolean) -> Unit,
    onDeleteRow: () -> Unit,
    onSlow: () -> Unit,
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
        val cellSize = minOf(cellFromWidth, cellFromHeight).coerceIn(18.dp, 44.dp)

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HeaderRow(snapshot = snapshot, highScore = highScore)
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
                        onMoveTo = onMoveTo,
                        onHardDrop = onHardDrop,
                        currentColumn = currentColumn
                    )
                }

                when (snapshot.status) {
                    GameStatus.READY -> Overlay(
                        modifier = Modifier.matchParentSize(),
                        title = "2048 DESCENT",
                        body = "Drag the falling number left or right. Matching numbers merge when they touch \u2014 sideways as well as down. Swipe down to drop fast.",
                        actionLabel = "Start Game",
                        onAction = onStart
                    )
                    GameStatus.CELEBRATING -> Overlay(
                        modifier = Modifier.matchParentSize(),
                        title = "2048!",
                        body = "The board clears and your 2048 locks into the corner. Speed ramps up \u2014 keep going!",
                        actionLabel = null,
                        onAction = {}
                    )
                    GameStatus.GAME_OVER -> Overlay(
                        modifier = Modifier.matchParentSize(),
                        title = "Game Over",
                        body = "Score ${snapshot.score} \u00B7 Best tile ${snapshot.bestTile}",
                        actionLabel = "Play Again",
                        onAction = onStart
                    )
                    GameStatus.PLAYING -> Unit
                }
            }

            PowersRow(
                snapshot = snapshot,
                onDeleteRow = onDeleteRow,
                onSlow = onSlow
            )

            ControlsRow(
                onMove = onMove,
                onHardDrop = onHardDrop,
                onSoftDrop = onSoftDrop
            )
        }
    }
}

@Composable
private fun HeaderRow(snapshot: BoardSnapshot, highScore: Int) {
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
            label = "TOP TILE",
            value = if (snapshot.bestTile > 0) snapshot.bestTile.toString() else "\u2014"
        )
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

        Text(
            text = "SPEED x%.2f".format(snapshot.speedMultiplier),
            color = AccentCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(BgPanel)
                .padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun PowersRow(
    snapshot: BoardSnapshot,
    onDeleteRow: () -> Unit,
    onSlow: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PowerButton(
            modifier = Modifier.weight(1f),
            label = "DELETE ROW",
            charges = snapshot.deleteCharges,
            subtitle = regenLabel(snapshot.deleteCharges, snapshot.deleteRegenRemainingMs),
            enabled = snapshot.deleteCharges > 0 && snapshot.status == GameStatus.PLAYING,
            highlighted = false,
            onClick = onDeleteRow
        )
        PowerButton(
            modifier = Modifier.weight(1f),
            label = "SLOW 30s",
            charges = snapshot.slowCharges,
            subtitle = if (snapshot.slowActiveRemainingMs > 0) {
                "active ${formatCountdown(snapshot.slowActiveRemainingMs)}"
            } else {
                regenLabel(snapshot.slowCharges, snapshot.slowRegenRemainingMs)
            },
            enabled = snapshot.slowCharges > 0 && snapshot.status == GameStatus.PLAYING,
            highlighted = snapshot.slowActiveRemainingMs > 0,
            onClick = onSlow
        )
    }
}

private fun regenLabel(charges: Int, remainingMs: Long): String =
    if (charges < POWER_MAX_CHARGES && remainingMs > 0) "+1 in ${formatCountdown(remainingMs)}" else " "

private fun formatCountdown(remainingMs: Long): String {
    val totalSeconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
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
            .padding(vertical = 6.dp),
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
        ControlButton(text = "\u25C0", modifier = Modifier.weight(1f)) { onMove(-1) }
        HoldButton(text = "\u25BC", modifier = Modifier.weight(1f), onHoldChange = onSoftDrop)
        ControlButton(text = "\u25B6", modifier = Modifier.weight(1f)) { onMove(1) }
        ControlButton(text = "DROP", modifier = Modifier.weight(1.4f), onClick = onHardDrop)
    }
}

@Composable
private fun ControlButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(BgPanel)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = TextLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Soft drop needs press-and-hold, so it reports both edges of the gesture. */
@Composable
private fun HoldButton(text: String, modifier: Modifier, onHoldChange: (Boolean) -> Unit) {
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
            },
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
    onAction: () -> Unit
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
        }
    }
}
