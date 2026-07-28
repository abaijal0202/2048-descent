package com.digiboxx.descent2048.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameMillis
import com.digiboxx.descent2048.game.BoardSnapshot
import com.digiboxx.descent2048.game.COLS
import com.digiboxx.descent2048.game.DANGER_CLEARANCE
import com.digiboxx.descent2048.game.GameStatus
import com.digiboxx.descent2048.game.PLAN_DURATION_MS
import com.digiboxx.descent2048.game.ROWS
import com.digiboxx.descent2048.game.SlideDirection
import com.digiboxx.descent2048.ui.theme.AccentCyan
import com.digiboxx.descent2048.ui.theme.AccentPink
import com.digiboxx.descent2048.ui.theme.TrophyGold
import com.digiboxx.descent2048.ui.theme.tileBackground
import com.digiboxx.descent2048.ui.theme.tileForeground
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin

/** How long a merged tile spends swelling back to its normal size. */
private const val POP_DURATION_MS = 180f

/** Peak overshoot of the merge pop, as a fraction of tile size. */
private const val POP_AMOUNT = 0.18f

/**
 * Renders the playfield and owns the touch gestures for it.
 *
 * Dragging anywhere on the board slides the falling tile; a decisive downward drag
 * hard-drops it. Putting the gesture on the board rather than only on buttons is what
 * makes this playable one-handed, which was the main failing of the first web build.
 *
 * Animation is driven by a frame clock read *inside* the draw lambda. That matters: a
 * State read from a DrawScope invalidates only the draw phase, so the board animates at
 * 60fps without recomposing anything. Reading it in the composable body instead would
 * recompose this whole subtree every frame.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun BoardCanvas(
    snapshot: BoardSnapshot,
    cellSize: Dp,
    deleteArmed: Boolean,
    planning: Boolean,
    onMoveTo: (Int) -> Unit,
    onHardDrop: () -> Unit,
    onDeleteRowAt: (Int) -> Unit,
    onSlide: (SlideDirection) -> Unit,
    canDeleteRow: (Int) -> Boolean,
    currentColumn: () -> Int,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellPx = remember(cellSize, density) { with(density) { cellSize.toPx() } }

    // Wall-clock time, sampled once per frame. Kept on the same clock as the engine so
    // stepStartMs / poppedAtMs can be compared against it directly.
    val frameTimeMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameTimeMs.longValue = System.currentTimeMillis() }
        }
    }

    val boardDescription = remember(snapshot.score, snapshot.falling, snapshot.status) {
        describeBoard(snapshot)
    }

    Box(
        modifier = modifier
            .size(width = cellSize * COLS, height = cellSize * ROWS)
            .semantics { contentDescription = boardDescription }
            .pointerInput(cellPx, deleteArmed) {
                if (deleteArmed) {
                    // While a row is being chosen, a tap picks it rather than moving.
                    detectTapGestures(
                        onTap = { offset ->
                            val row = (offset.y / cellPx).toInt().coerceIn(0, ROWS - 1)
                            onDeleteRowAt(row)
                        }
                    )
                }
            }
            .pointerInput(cellPx, planning) {
                if (!planning) return@pointerInput

                // One swipe per gesture, exactly like 2048. Firing continuously while the
                // finger moves would burn the whole 15 seconds in a single drag.
                var totalX = 0f
                var totalY = 0f
                var fired = false
                val threshold = cellPx * 0.55f

                detectDragGestures(
                    onDragStart = { totalX = 0f; totalY = 0f; fired = false },
                    onDragEnd = { fired = false },
                    onDragCancel = { fired = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (fired) return@detectDragGestures
                        totalX += dragAmount.x
                        totalY += dragAmount.y
                        if (abs(totalX) < threshold && abs(totalY) < threshold) {
                            return@detectDragGestures
                        }
                        fired = true
                        onSlide(
                            if (abs(totalX) > abs(totalY)) {
                                if (totalX > 0) SlideDirection.RIGHT else SlideDirection.LEFT
                            } else {
                                if (totalY > 0) SlideDirection.DOWN else SlideDirection.UP
                            }
                        )
                    }
                )
            }
            .pointerInput(cellPx, deleteArmed, planning) {
                if (deleteArmed || planning) return@pointerInput

                var startColumn = 0
                var accumulatedX = 0f
                var accumulatedY = 0f
                var alreadyDropped = false

                detectDragGestures(
                    onDragStart = {
                        startColumn = currentColumn()
                        accumulatedX = 0f
                        accumulatedY = 0f
                        alreadyDropped = false
                    },
                    onDragEnd = { alreadyDropped = false },
                    onDragCancel = { alreadyDropped = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedX += dragAmount.x
                        accumulatedY += dragAmount.y

                        // A committed downward flick means "drop now". Requiring it to
                        // dominate the horizontal movement stops ordinary sideways
                        // drags from firing it by accident.
                        if (!alreadyDropped &&
                            accumulatedY > cellPx * 1.2f &&
                            accumulatedY > kotlin.math.abs(accumulatedX) * 1.5f
                        ) {
                            alreadyDropped = true
                            onHardDrop()
                            return@detectDragGestures
                        }

                        if (!alreadyDropped) {
                            val columnDelta = (accumulatedX / cellPx).toInt()
                            onMoveTo(startColumn + columnDelta)
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.size(width = cellSize * COLS, height = cellSize * ROWS)) {
            // Read inside the draw scope: invalidates draw only, never recomposition.
            val nowMs = frameTimeMs.longValue

            drawGrid(cellPx)

            if (snapshot.spawnClearance <= DANGER_CLEARANCE) {
                drawDangerZone(cellPx, snapshot.spawnClearance, nowMs)
            }

            for (cell in snapshot.cells) {
                drawTile(
                    rowF = cell.row.toFloat(),
                    col = cell.col,
                    value = cell.value,
                    locked = cell.locked,
                    scale = popScale(nowMs - cell.poppedAtMs),
                    cellPx = cellPx,
                    textMeasurer = textMeasurer
                )
            }

            val falling = snapshot.falling
            if (falling != null) {
                // With gravity suspended there is nowhere for the tile to land, so a
                // landing guide would be pointing at a lie.
                if (!planning) {
                    drawLandingGuide(
                        row = snapshot.landingRow,
                        col = falling.col,
                        cellPx = cellPx,
                        willMerge = snapshot.willMergeOnLanding
                    )
                }
                drawTile(
                    rowF = fallingRow(snapshot, nowMs),
                    col = falling.col,
                    value = falling.value,
                    locked = false,
                    scale = 1f,
                    cellPx = cellPx,
                    textMeasurer = textMeasurer,
                    alpha = if (planning) 0.45f else 1f
                )
            }

            if (deleteArmed) drawDeleteTargets(cellPx, canDeleteRow, nowMs)
            if (planning) drawPlanTimer(cellPx, snapshot.planExpiresAtMs, nowMs)
        }
    }
}

/**
 * The falling tile's row as a fraction, interpolated across the current gravity step.
 *
 * Without this the tile jumps a whole cell every 750ms and the game reads as a slideshow.
 * It is clamped to the landing row so a tile never appears to sink into the stack it is
 * about to rest on.
 */
private fun fallingRow(snapshot: BoardSnapshot, nowMs: Long): Float {
    val falling = snapshot.falling ?: return 0f
    if (snapshot.status != GameStatus.PLAYING) return falling.row.toFloat()
    if (snapshot.stepDurationMs <= 0L) return falling.row.toFloat()

    val elapsed = (nowMs - snapshot.stepStartMs).toFloat()
    val progress = (elapsed / snapshot.stepDurationMs.toFloat()).coerceIn(0f, 1f)
    return (falling.row + progress).coerceAtMost(snapshot.landingRow.toFloat())
}

/** Swell-and-settle curve for a freshly merged tile: 1.0 up to ~1.18 and back. */
private fun popScale(elapsedMs: Long): Float {
    if (elapsedMs < 0 || elapsedMs > POP_DURATION_MS) return 1f
    val t = elapsedMs / POP_DURATION_MS
    return 1f + POP_AMOUNT * sin(t * PI).toFloat()
}

private fun describeBoard(snapshot: BoardSnapshot): String {
    val falling = snapshot.falling
    val head = when (snapshot.status) {
        GameStatus.READY -> "Game not started."
        GameStatus.PLANNING ->
            "Plan mode. Gravity is off, swipe to slide the whole board."
        GameStatus.PAUSED -> "Game paused."
        GameStatus.GAME_OVER -> "Game over."
        GameStatus.CELEBRATING -> "Trophy earned."
        GameStatus.PLAYING -> if (falling != null) {
            "Falling tile ${falling.value} in column ${falling.col + 1} of $COLS, " +
                "landing on row ${snapshot.landingRow + 1}" +
                if (snapshot.willMergeOnLanding) ", will merge." else "."
        } else {
            "Board settling."
        }
    }
    return "$head Score ${snapshot.score}. ${snapshot.cells.size} tiles on the board."
}

private fun DrawScope.drawGrid(cellPx: Float) {
    val lineColor = Color.White.copy(alpha = 0.045f)
    for (col in 1 until COLS) {
        drawLine(
            color = lineColor,
            start = Offset(col * cellPx, 0f),
            end = Offset(col * cellPx, ROWS * cellPx),
            strokeWidth = 1f
        )
    }
    for (row in 1 until ROWS) {
        drawLine(
            color = lineColor,
            start = Offset(0f, row * cellPx),
            end = Offset(COLS * cellPx, row * cellPx),
            strokeWidth = 1f
        )
    }
}

/**
 * A pulsing wash across the top of the board once the stack is close to the ceiling.
 *
 * The game can end abruptly, and previously nothing on screen said so in advance.
 */
private fun DrawScope.drawDangerZone(cellPx: Float, clearance: Int, nowMs: Long) {
    val urgency = 1f - (clearance.toFloat() / (DANGER_CLEARANCE + 1).toFloat())
    val pulse = 0.5f + 0.5f * sin(nowMs / 220.0 * PI).toFloat()
    val alpha = (0.06f + 0.14f * urgency * pulse).coerceIn(0f, 0.3f)
    drawRect(
        color = AccentPink.copy(alpha = alpha),
        topLeft = Offset(0f, 0f),
        size = Size(COLS * cellPx, cellPx * (DANGER_CLEARANCE + 1))
    )
}

/**
 * The Plan countdown, pinned across the top of the board.
 *
 * Drawn from the frame clock rather than the second-quantised HUD value so it drains
 * smoothly, and it turns from gold to pink over the last third as a second, non-numeric
 * signal that time is nearly up.
 */
private fun DrawScope.drawPlanTimer(cellPx: Float, expiresAtMs: Long, nowMs: Long) {
    val remaining = (expiresAtMs - nowMs).coerceAtLeast(0L).toFloat()
    val fraction = (remaining / PLAN_DURATION_MS.toFloat()).coerceIn(0f, 1f)
    val barHeight = cellPx * 0.16f
    val boardWidth = COLS * cellPx

    drawRect(
        color = Color.Black.copy(alpha = 0.45f),
        topLeft = Offset(0f, 0f),
        size = Size(boardWidth, barHeight)
    )
    drawRect(
        color = if (fraction < 0.33f) AccentPink else TrophyGold,
        topLeft = Offset(0f, 0f),
        size = Size(boardWidth * fraction, barHeight)
    )
}

/** Rows that Delete Row could clear, outlined while the player is choosing one. */
private fun DrawScope.drawDeleteTargets(
    cellPx: Float,
    canDeleteRow: (Int) -> Boolean,
    nowMs: Long
) {
    val pulse = 0.5f + 0.5f * sin(nowMs / 300.0 * PI).toFloat()
    val dash = PathEffect.dashPathEffect(floatArrayOf(cellPx * 0.18f, cellPx * 0.12f))
    for (row in 0 until ROWS) {
        if (!canDeleteRow(row)) continue
        drawRect(
            color = AccentPink.copy(alpha = 0.10f + 0.10f * pulse),
            topLeft = Offset(0f, row * cellPx),
            size = Size(COLS * cellPx, cellPx)
        )
        drawRect(
            color = AccentPink.copy(alpha = 0.75f),
            topLeft = Offset(1f, row * cellPx + 1f),
            size = Size(COLS * cellPx - 2f, cellPx - 2f),
            style = Stroke(width = 2f, pathEffect = dash)
        )
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawTile(
    rowF: Float,
    col: Int,
    value: Int,
    locked: Boolean,
    scale: Float,
    cellPx: Float,
    textMeasurer: TextMeasurer,
    alpha: Float = 1f
) {
    val padding = cellPx * 0.07f
    val baseSize = cellPx - padding * 2
    // Grow about the tile's centre so a pop does not shift it off its cell.
    val grown = baseSize * scale
    val inset = (baseSize - grown) / 2f

    val topLeft = Offset(col * cellPx + padding + inset, rowF * cellPx + padding + inset)
    val size = Size(grown, grown)
    val radius = CornerRadius(cellPx * 0.19f, cellPx * 0.19f)

    drawRoundRect(
        color = tileBackground(value).copy(alpha = alpha),
        topLeft = topLeft,
        size = size,
        cornerRadius = radius
    )

    if (locked) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.6f),
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
            style = Stroke(width = 2f)
        )
    }

    // Shrink the type as the number gets longer so 8192 still fits inside the tile.
    val digits = value.toString().length
    val textScale = when {
        digits >= 4 -> 0.30f
        digits == 3 -> 0.36f
        else -> 0.42f
    }
    val fontSizePx = cellPx * textScale

    val layout: TextLayoutResult = textMeasurer.measure(
        text = value.toString(),
        style = TextStyle(
            color = tileForeground(value).copy(alpha = alpha),
            fontSize = (fontSizePx / density).sp,
            fontWeight = FontWeight.ExtraBold
        )
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = topLeft.x + (size.width - layout.size.width) / 2f,
            y = topLeft.y + (size.height - layout.size.height) / 2f
        )
    )
}

/**
 * The outline showing where the tile will come to rest.
 *
 * A merge is signalled by *shape* as well as colour — solid and thick when landing here
 * combines, thin and dashed otherwise. Colour alone left the single most useful piece of
 * feedback in the game invisible to a colourblind player.
 */
private fun DrawScope.drawLandingGuide(row: Int, col: Int, cellPx: Float, willMerge: Boolean) {
    val padding = cellPx * 0.07f
    val topLeft = Offset(col * cellPx + padding, row * cellPx + padding)
    val size = Size(cellPx - padding * 2, cellPx - padding * 2)
    val radius = CornerRadius(cellPx * 0.19f, cellPx * 0.19f)

    if (willMerge) {
        drawRoundRect(
            color = AccentCyan.copy(alpha = 0.18f),
            topLeft = topLeft,
            size = size,
            cornerRadius = radius
        )
        drawRoundRect(
            color = AccentCyan,
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
            style = Stroke(width = 3.5f)
        )
    } else {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
            style = Stroke(
                width = 1.5f,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(cellPx * 0.16f, cellPx * 0.12f)
                )
            )
        )
    }
}
