package com.digiboxx.descent2048.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.digiboxx.descent2048.game.BoardSnapshot
import com.digiboxx.descent2048.game.COLS
import com.digiboxx.descent2048.game.ROWS
import com.digiboxx.descent2048.ui.theme.AccentCyan
import com.digiboxx.descent2048.ui.theme.tileBackground
import com.digiboxx.descent2048.ui.theme.tileForeground

/**
 * Renders the playfield and owns the touch gestures for it.
 *
 * Dragging anywhere on the board slides the falling tile; a decisive downward drag
 * hard-drops it. Putting the gesture on the board rather than only on buttons is what
 * makes this playable one-handed, which was the main failing of the first web build.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun BoardCanvas(
    snapshot: BoardSnapshot,
    cellSize: Dp,
    onMoveTo: (Int) -> Unit,
    onHardDrop: () -> Unit,
    currentColumn: () -> Int,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellPx = remember(cellSize, density) { with(density) { cellSize.toPx() } }

    Box(
        modifier = modifier
            .size(width = cellSize * COLS, height = cellSize * ROWS)
            .pointerInput(cellPx) {
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
            drawGrid(cellPx)

            for (cell in snapshot.cells) {
                drawTile(
                    row = cell.row,
                    col = cell.col,
                    value = cell.value,
                    locked = cell.locked,
                    cellPx = cellPx,
                    textMeasurer = textMeasurer
                )
            }

            val falling = snapshot.falling
            if (falling != null) {
                drawLandingGuide(
                    row = snapshot.landingRow,
                    col = falling.col,
                    cellPx = cellPx,
                    willMerge = snapshot.willMergeOnLanding
                )
                drawTile(
                    row = falling.row,
                    col = falling.col,
                    value = falling.value,
                    locked = false,
                    cellPx = cellPx,
                    textMeasurer = textMeasurer
                )
            }
        }
    }
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

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawTile(
    row: Int,
    col: Int,
    value: Int,
    locked: Boolean,
    cellPx: Float,
    textMeasurer: TextMeasurer
) {
    val padding = cellPx * 0.07f
    val topLeft = Offset(col * cellPx + padding, row * cellPx + padding)
    val size = Size(cellPx - padding * 2, cellPx - padding * 2)
    val radius = CornerRadius(cellPx * 0.19f, cellPx * 0.19f)

    drawRoundRect(
        color = tileBackground(value),
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

    // Shrink the type as the number gets longer so 1024 still fits inside the tile.
    val digits = value.toString().length
    val scale = when {
        digits >= 4 -> 0.30f
        digits == 3 -> 0.36f
        else -> 0.42f
    }
    val fontSizePx = cellPx * scale

    val layout: TextLayoutResult = textMeasurer.measure(
        text = value.toString(),
        style = TextStyle(
            color = tileForeground(value),
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
 * The dashed outline showing where the tile will come to rest. It turns cyan when
 * landing there would trigger a merge, which is the single most useful piece of
 * feedback in the game.
 */
private fun DrawScope.drawLandingGuide(row: Int, col: Int, cellPx: Float, willMerge: Boolean) {
    val padding = cellPx * 0.07f
    drawRoundRect(
        color = if (willMerge) AccentCyan else Color.White.copy(alpha = 0.18f),
        topLeft = Offset(col * cellPx + padding, row * cellPx + padding),
        size = Size(cellPx - padding * 2, cellPx - padding * 2),
        cornerRadius = CornerRadius(cellPx * 0.19f, cellPx * 0.19f),
        style = Stroke(width = 2f)
    )
}
