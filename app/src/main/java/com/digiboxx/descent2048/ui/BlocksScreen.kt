package com.digiboxx.descent2048.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digiboxx.descent2048.blocks.BLOCK_COLS
import com.digiboxx.descent2048.blocks.BLOCK_DANGER_ROWS
import com.digiboxx.descent2048.blocks.BLOCK_ROWS
import com.digiboxx.descent2048.blocks.BLOCK_TARGET
import com.digiboxx.descent2048.blocks.BlocksSnapshot
import com.digiboxx.descent2048.blocks.BlocksStatus
import com.digiboxx.descent2048.blocks.PIECE_SHAPES
import com.digiboxx.descent2048.blocks.PiecePreview
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private const val POP_MS = 180f
private const val POP_AMOUNT = 0.18f

@Composable
fun BlocksScreen(
    snapshot: BlocksSnapshot,
    highScore: Int,
    hapticsEnabled: Boolean,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMove: (Int) -> Unit,
    onRotate: () -> Unit,
    onHardDrop: () -> Unit,
    onSoftDrop: (Boolean) -> Unit,
    onToggleHaptics: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1F3A), Color(0xFF131625))))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        val reserved = 180.dp
        val availableHeight = maxHeight - reserved
        val availableWidth = minOf(maxWidth, 420.dp) - 24.dp
        val cellSize = minOf(availableWidth / BLOCK_COLS, availableHeight / BLOCK_ROWS)
            .coerceIn(16.dp, 56.dp)

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BlocksHeader(snapshot, highScore, onBack, onPause)
            BlocksInfoRow(snapshot)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgPanel2)
                    .padding(5.dp)
            ) {
                BlocksBoard(
                    snapshot = snapshot,
                    cellSize = cellSize,
                    onMove = onMove,
                    onRotate = onRotate,
                    onHardDrop = onHardDrop
                )

                if (snapshot.status == BlocksStatus.PLAYING &&
                    snapshot.clearance <= BLOCK_DANGER_ROWS
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
                            .background(Color(0xFF0A0C18).copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                when (snapshot.status) {
                    BlocksStatus.READY -> BlocksOverlay(
                        modifier = Modifier.matchParentSize(),
                        title = "2048 BLOCKS",
                        body = "Four-cell pieces, every cell its own number. " +
                            "Two equal numbers can never share a boundary — when they touch " +
                            "they combine. Fill a row to clear it. Reach $BLOCK_TARGET.\n\n" +
                            "Tap to rotate, drag to move, swipe down to drop.",
                        actionLabel = "Start Game",
                        onAction = onStart
                    )
                    BlocksStatus.PAUSED -> BlocksOverlay(
                        modifier = Modifier.matchParentSize(),
                        title = "Paused",
                        body = "Score ${snapshot.score} · Lines ${snapshot.lines}",
                        actionLabel = "Resume",
                        onAction = onResume,
                        secondaryLabel = if (hapticsEnabled) "Haptics off" else "Haptics on",
                        onSecondary = onToggleHaptics,
                        tertiaryLabel = "New game",
                        onTertiary = onStart
                    )
                    BlocksStatus.GAME_OVER -> BlocksOverlay(
                        modifier = Modifier.matchParentSize(),
                        title = "Game Over",
                        body = "Score ${snapshot.score} · Lines ${snapshot.lines} · " +
                            "Best block ${snapshot.bestValue}",
                        actionLabel = "Play Again",
                        onAction = onStart,
                        tertiaryLabel = "Back to games",
                        onTertiary = onBack
                    )
                    BlocksStatus.PLAYING -> Unit
                }
            }

            BlocksControls(
                onMove = onMove,
                onRotate = onRotate,
                onHardDrop = onHardDrop,
                onSoftDrop = onSoftDrop
            )
        }
    }
}

@Composable
private fun BlocksHeader(
    snapshot: BlocksSnapshot,
    highScore: Int,
    onBack: () -> Unit,
    onPause: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgPanel)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BgPanel2)
                .pointerInput(Unit) { detectTapGestures { onBack() } }
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .semantics { contentDescription = "Back to game selection" }
        ) { Text("<", color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.Bold) }

        StatPair("SCORE", snapshot.score.toString())
        StatPair("BEST", highScore.toString())

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BgPanel2)
                .pointerInput(Unit) { detectTapGestures { onPause() } }
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .semantics { contentDescription = "Pause game" }
        ) { Text("II", color = TextLight, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun StatPair(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Text(value, color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BlocksInfoRow(snapshot: BlocksSnapshot) {
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("NEXT", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            for (preview in snapshot.nextPieces) {
                PiecePreviewCell(preview)
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
                "LV ${snapshot.level} · ${snapshot.lines} LINES",
                color = AccentCyan,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (snapshot.lastComboDepth > 1) "COMBO x${snapshot.lastComboDepth}"
                else "GOAL $BLOCK_TARGET",
                color = TrophyGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** A miniature of an upcoming piece, showing the number on each of its four cells. */
@Composable
private fun PiecePreviewCell(preview: PiecePreview) {
    val shape = PIECE_SHAPES.getValue(preview.type)
    val unit = 8.dp
    Box(modifier = Modifier.size(unit * shape.boxSize, unit * 2.2f)) {
        preview.cells.forEachIndexed { index, cell ->
            Box(
                modifier = Modifier
                    .padding(start = unit * cell.col, top = unit * cell.row)
                    .size(unit)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tileBackground(preview.values[index])),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    preview.values[index].toString(),
                    color = tileForeground(preview.values[index]),
                    fontSize = 4.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

/**
 * The playfield.
 *
 * Tap rotates, drag moves a column at a time, and a committed downward flick hard-drops —
 * the same gesture vocabulary as Descent, so switching between the two does not require
 * relearning anything.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun BlocksBoard(
    snapshot: BlocksSnapshot,
    cellSize: Dp,
    onMove: (Int) -> Unit,
    onRotate: () -> Unit,
    onHardDrop: () -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellPx = remember(cellSize, density) { with(density) { cellSize.toPx() } }

    val frameTimeMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { frameTimeMs.longValue = System.currentTimeMillis() }
    }

    val description = remember(snapshot.score, snapshot.status, snapshot.falling) {
        when (snapshot.status) {
            BlocksStatus.READY -> "Game not started."
            BlocksStatus.PAUSED -> "Game paused."
            BlocksStatus.GAME_OVER -> "Game over. Score ${snapshot.score}."
            BlocksStatus.PLAYING ->
                "Falling piece of ${snapshot.falling.joinToString(", ") { it.value.toString() }}. " +
                    "Score ${snapshot.score}, level ${snapshot.level}."
        }
    }

    Box(
        modifier = Modifier
            .size(width = cellSize * BLOCK_COLS, height = cellSize * BLOCK_ROWS)
            .clip(RoundedCornerShape(9.dp))
            .background(BoardBg)
            .semantics { contentDescription = description }
            .pointerInput(cellPx) {
                detectTapGestures { onRotate() }
            }
            .pointerInput(cellPx) {
                var accumulatedX = 0f
                var accumulatedY = 0f
                var dropped = false

                detectDragGestures(
                    onDragStart = { accumulatedX = 0f; accumulatedY = 0f; dropped = false },
                    onDragEnd = { dropped = false },
                    onDragCancel = { dropped = false },
                    onDrag = { change, amount ->
                        change.consume()
                        accumulatedX += amount.x
                        accumulatedY += amount.y

                        if (!dropped &&
                            accumulatedY > cellPx * 1.4f &&
                            accumulatedY > abs(accumulatedX) * 1.5f
                        ) {
                            dropped = true
                            onHardDrop()
                            return@detectDragGestures
                        }
                        if (dropped) return@detectDragGestures

                        // Consume a whole cell's worth of travel per column moved, so a
                        // slow drag steps rather than skidding across the board.
                        while (accumulatedX >= cellPx) {
                            onMove(1)
                            accumulatedX -= cellPx
                        }
                        while (accumulatedX <= -cellPx) {
                            onMove(-1)
                            accumulatedX += cellPx
                        }
                    }
                )
            }
    ) {
        Canvas(
            modifier = Modifier.size(
                width = cellSize * BLOCK_COLS,
                height = cellSize * BLOCK_ROWS
            )
        ) {
            val nowMs = frameTimeMs.longValue

            drawBlocksGrid(cellPx)

            if (snapshot.clearance <= BLOCK_DANGER_ROWS) {
                val pulse = 0.5f + 0.5f * sin(nowMs / 220.0 * PI).toFloat()
                drawRect(
                    color = AccentPink.copy(alpha = 0.05f + 0.10f * pulse),
                    topLeft = Offset.Zero,
                    size = Size(BLOCK_COLS * cellPx, cellPx * (BLOCK_DANGER_ROWS + 1))
                )
            }

            for (cell in snapshot.cells) {
                drawBlock(
                    rowF = cell.row.toFloat(),
                    col = cell.col,
                    value = cell.value,
                    scale = popScale(nowMs - cell.poppedAtMs),
                    cellPx = cellPx,
                    textMeasurer = textMeasurer
                )
            }

            if (snapshot.status == BlocksStatus.PLAYING) {
                for (cell in snapshot.ghost) {
                    drawGhost(cell.row, cell.col, cellPx)
                }
                val offset = fallOffset(snapshot, nowMs)
                for (cell in snapshot.falling) {
                    if (cell.row < 0) continue
                    drawBlock(
                        rowF = cell.row + offset,
                        col = cell.col,
                        value = cell.value,
                        scale = 1f,
                        cellPx = cellPx,
                        textMeasurer = textMeasurer
                    )
                }
            }
        }
    }
}

/**
 * Fractional progress through the current gravity step.
 *
 * Clamped to just under a whole cell so a piece never visually overlaps the row it is
 * about to land on before the engine has actually locked it.
 */
private fun fallOffset(snapshot: BlocksSnapshot, nowMs: Long): Float {
    if (snapshot.status != BlocksStatus.PLAYING) return 0f
    if (snapshot.stepDurationMs <= 0L) return 0f
    val elapsed = (nowMs - snapshot.stepStartMs).toFloat()
    return (elapsed / snapshot.stepDurationMs.toFloat()).coerceIn(0f, 0.96f)
}

private fun popScale(elapsedMs: Long): Float {
    if (elapsedMs < 0 || elapsedMs > POP_MS) return 1f
    return 1f + POP_AMOUNT * sin(elapsedMs / POP_MS * PI).toFloat()
}

private fun DrawScope.drawBlocksGrid(cellPx: Float) {
    val line = Color.White.copy(alpha = 0.045f)
    for (c in 1 until BLOCK_COLS) {
        drawLine(line, Offset(c * cellPx, 0f), Offset(c * cellPx, BLOCK_ROWS * cellPx), 1f)
    }
    for (r in 1 until BLOCK_ROWS) {
        drawLine(line, Offset(0f, r * cellPx), Offset(BLOCK_COLS * cellPx, r * cellPx), 1f)
    }
}

private fun DrawScope.drawGhost(row: Int, col: Int, cellPx: Float) {
    val pad = cellPx * 0.07f
    drawRoundRect(
        color = AccentCyan.copy(alpha = 0.30f),
        topLeft = Offset(col * cellPx + pad, row * cellPx + pad),
        size = Size(cellPx - pad * 2, cellPx - pad * 2),
        cornerRadius = CornerRadius(cellPx * 0.18f, cellPx * 0.18f),
        style = Stroke(width = 2f)
    )
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBlock(
    rowF: Float,
    col: Int,
    value: Int,
    scale: Float,
    cellPx: Float,
    textMeasurer: TextMeasurer
) {
    val pad = cellPx * 0.07f
    val base = cellPx - pad * 2
    val grown = base * scale
    val inset = (base - grown) / 2f
    val topLeft = Offset(col * cellPx + pad + inset, rowF * cellPx + pad + inset)
    val size = Size(grown, grown)
    val radius = CornerRadius(cellPx * 0.18f, cellPx * 0.18f)

    drawRoundRect(tileBackground(value), topLeft, size, radius)

    val digits = value.toString().length
    val ratio = when {
        digits >= 4 -> 0.29f
        digits == 3 -> 0.35f
        else -> 0.42f
    }
    val layout = textMeasurer.measure(
        text = value.toString(),
        style = TextStyle(
            color = tileForeground(value),
            fontSize = (cellPx * ratio / density).sp,
            fontWeight = FontWeight.ExtraBold
        )
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            topLeft.x + (size.width - layout.size.width) / 2f,
            topLeft.y + (size.height - layout.size.height) / 2f
        )
    )
}

@Composable
private fun BlocksControls(
    onMove: (Int) -> Unit,
    onRotate: () -> Unit,
    onHardDrop: () -> Unit,
    onSoftDrop: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BlockButton("<", "Move left", Modifier.weight(1f)) { onMove(-1) }
        BlockButton("ROTATE", "Rotate piece", Modifier.weight(1.5f)) { onRotate() }
        BlockButton(">", "Move right", Modifier.weight(1f)) { onMove(1) }
        BlockHoldButton("v", "Soft drop", Modifier.weight(1f), onSoftDrop)
        BlockButton("DROP", "Hard drop", Modifier.weight(1.3f)) { onHardDrop() }
    }
}

@Composable
private fun BlockButton(
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
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BlockHoldButton(
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
                        tryAwaitRelease()
                        pressed = false
                        onHoldChange(false)
                    }
                )
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BlocksOverlay(
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
            .background(Color(0xFF0A0C18).copy(alpha = 0.92f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                title,
                color = TextLight,
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(body, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
            if (actionLabel != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = Color(0xFF101225)
                    ),
                    shape = RoundedCornerShape(9.dp)
                ) { Text(actionLabel, fontWeight = FontWeight.Bold) }
            }
            if (secondaryLabel != null && onSecondary != null) {
                Text(
                    secondaryLabel,
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
                    tertiaryLabel,
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
