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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digiboxx.descent2048.merge.Bowl
import com.digiboxx.descent2048.merge.DEATH_LINE_Y
import com.digiboxx.descent2048.merge.DROP_Y
import com.digiboxx.descent2048.merge.MERGE_TARGET
import com.digiboxx.descent2048.merge.MergeSnapshot
import com.digiboxx.descent2048.merge.MergeStatus
import com.digiboxx.descent2048.merge.WORLD_HEIGHT
import com.digiboxx.descent2048.merge.WORLD_WIDTH
import com.digiboxx.descent2048.merge.radiusFor
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
import kotlin.math.sin

/** How long a freshly merged ball spends swelling back to its normal size. */
private const val POP_MS = 220f
private const val POP_AMOUNT = 0.16f

@Composable
fun MergeScreen(
    snapshot: MergeSnapshot,
    highScore: Int,
    hapticsEnabled: Boolean,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAim: (Float) -> Unit,
    onDrop: () -> Unit,
    onToggleHaptics: () -> Unit,
    canContinue: Boolean,
    adInFlight: Boolean,
    onContinue: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1F3A), Color(0xFF131625))))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // The bowl keeps a fixed aspect ratio, so pick whichever of width or height is
        // the binding constraint and size from that.
        val chromeHeight = 132.dp
        val availableHeight = maxHeight - chromeHeight
        val availableWidth = minOf(maxWidth, 420.dp)
        val bowlWidth = minOf(availableWidth, availableHeight / WORLD_HEIGHT)

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MergeHeader(
                snapshot = snapshot,
                highScore = highScore,
                onBack = onBack,
                onPause = onPause
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgPanel2)
                    .padding(5.dp)
            ) {
                MergeBowl(
                    snapshot = snapshot,
                    bowlWidth = bowlWidth,
                    onAim = onAim,
                    onDrop = onDrop
                )

                when (snapshot.status) {
                    MergeStatus.READY -> MergeOverlay(
                        modifier = Modifier.matchParentSize(),
                        title = "2048 MERGE",
                        body = "Drag to aim, release to drop. Equal balls merge when they touch, " +
                            "and the bowl rolls everything to the middle. Get to $MERGE_TARGET — " +
                            "but if the pile crests the line, the run is over.",
                        actionLabel = "Start Game",
                        onAction = onStart
                    )
                    MergeStatus.PAUSED -> MergeOverlay(
                        modifier = Modifier.matchParentSize(),
                        title = "Paused",
                        body = "Score ${snapshot.score} · Best ball ${snapshot.bestValue}",
                        actionLabel = "Resume",
                        onAction = onResume,
                        secondaryLabel = if (hapticsEnabled) "Haptics off" else "Haptics on",
                        onSecondary = onToggleHaptics,
                        tertiaryLabel = "New game",
                        onTertiary = onStart
                    )
                    MergeStatus.GAME_OVER -> MergeOverlay(
                        modifier = Modifier.matchParentSize(),
                        title = "Overflowed",
                        body = "Score ${snapshot.score} · Best ball ${snapshot.bestValue}",
                        actionLabel = when {
                            adInFlight -> "Loading..."
                            canContinue -> "Watch ad to continue"
                            else -> "Play Again"
                        },
                        onAction = if (canContinue && !adInFlight) onContinue else onStart,
                        secondaryLabel = if (canContinue) "Play Again" else null,
                        onSecondary = if (canContinue) onStart else null,
                        tertiaryLabel = "Back to games",
                        onTertiary = onBack
                    )
                    MergeStatus.PLAYING -> Unit
                }
            }

            NextUpRow(snapshot = snapshot)
        }
    }
}

@Composable
private fun MergeHeader(
    snapshot: MergeSnapshot,
    highScore: Int,
    onBack: () -> Unit,
    onPause: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgPanel)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BgPanel2)
                .pointerInput(Unit) { detectTapGestures { onBack() } }
                .padding(horizontal = 9.dp, vertical = 4.dp)
                .semantics { contentDescription = "Back to game selection" }
        ) {
            Text("<", color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        MergeStat("SCORE", snapshot.score.toString())
        MergeStat("BEST", highScore.toString())
        MergeStat("BALL", if (snapshot.bestValue > 0) snapshot.bestValue.toString() else "—")
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BgPanel2)
                .pointerInput(Unit) { detectTapGestures { onPause() } }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics { contentDescription = "Pause game" }
        ) {
            Text("II", color = TextLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MergeStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Text(value, color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NextUpRow(snapshot: MergeSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgPanel)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("NEXT", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        for (value in snapshot.nextValues) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tileBackground(value)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    value.toString(),
                    color = tileForeground(value),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (snapshot.lastComboDepth > 1) {
            Text(
                "COMBO x${snapshot.lastComboDepth}",
                color = TrophyGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
            )
        } else {
            Text(
                "GOAL $MERGE_TARGET",
                color = AccentCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * The bowl itself.
 *
 * Like [BoardCanvas], the frame clock is read inside the draw lambda so the pop animation
 * runs at 60fps by invalidating only the draw phase, never recomposing.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun MergeBowl(
    snapshot: MergeSnapshot,
    bowlWidth: androidx.compose.ui.unit.Dp,
    onAim: (Float) -> Unit,
    onDrop: () -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widthPx = remember(bowlWidth, density) { with(density) { bowlWidth.toPx() } }

    val frameTimeMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { frameTimeMs.longValue = System.currentTimeMillis() }
    }

    val description = remember(snapshot.score, snapshot.balls.size, snapshot.status) {
        when (snapshot.status) {
            MergeStatus.READY -> "Game not started."
            MergeStatus.PAUSED -> "Game paused."
            MergeStatus.GAME_OVER -> "Game over. Score ${snapshot.score}."
            MergeStatus.PLAYING ->
                "${snapshot.balls.size} balls in the bowl. Holding a ${snapshot.heldValue}. " +
                    "Score ${snapshot.score}."
        }
    }

    Box(
        modifier = Modifier
            .size(width = bowlWidth, height = bowlWidth * WORLD_HEIGHT)
            .clip(RoundedCornerShape(11.dp))
            .background(BoardBg)
            .semantics { contentDescription = description }
            .pointerInput(widthPx) {
                // Drag to aim and release to drop, which is the natural one-handed
                // gesture; a plain tap does both at once.
                detectDragGestures(
                    onDragStart = { offset -> onAim(offset.x / widthPx) },
                    onDragEnd = { onDrop() },
                    onDrag = { change, _ ->
                        change.consume()
                        onAim(change.position.x / widthPx)
                    }
                )
            }
            .pointerInput(widthPx) {
                detectTapGestures { offset ->
                    onAim(offset.x / widthPx)
                    onDrop()
                }
            }
    ) {
        Canvas(modifier = Modifier.size(width = bowlWidth, height = bowlWidth * WORLD_HEIGHT)) {
            val nowMs = frameTimeMs.longValue
            val scale = widthPx

            drawBowlInterior(scale)
            drawDeathLine(scale, snapshot.dangerFraction, nowMs)

            for (ball in snapshot.balls) {
                val pop = popScale(nowMs - ball.mergedAtMs)
                drawBall(
                    cx = ball.x * scale,
                    cy = ball.y * scale,
                    radius = ball.radius * scale * pop,
                    value = ball.value,
                    textMeasurer = textMeasurer
                )
            }

            if (snapshot.status == MergeStatus.PLAYING) {
                drawAimGuide(snapshot, scale)
            }
        }
    }
}

private fun popScale(elapsedMs: Long): Float {
    if (elapsedMs < 0 || elapsedMs > POP_MS) return 1f
    return 1f + POP_AMOUNT * sin(elapsedMs / POP_MS * PI).toFloat()
}

/** The curved floor, drawn as the arc the physics actually uses. */
private fun DrawScope.drawBowlInterior(scale: Float) {
    val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, Bowl.floorYAt(0f) * scale)
        // Sampled rather than arcTo, so the drawn curve is by construction the same
        // function the solver constrains against.
        var x = 0f
        while (x <= WORLD_WIDTH) {
            lineTo(x * scale, Bowl.floorYAt(x) * scale)
            x += 0.02f
        }
        lineTo(WORLD_WIDTH * scale, Bowl.floorYAt(WORLD_WIDTH) * scale)
        lineTo(WORLD_WIDTH * scale, 0f)
        close()
    }
    clipPath(path) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF11142A), Color(0xFF0A0C18))
            ),
            topLeft = Offset.Zero,
            size = Size(WORLD_WIDTH * scale, WORLD_HEIGHT * scale)
        )
    }
    drawPath(
        path = path,
        color = Color.White.copy(alpha = 0.10f),
        style = Stroke(width = 2.5f)
    )
}

/**
 * The line the pile must not crest.
 *
 * It reddens and pulses as the danger rises, so the player is warned before losing
 * rather than surprised by it.
 */
private fun DrawScope.drawDeathLine(scale: Float, danger: Float, nowMs: Long) {
    val y = DEATH_LINE_Y * scale
    val pulse = 0.5f + 0.5f * sin(nowMs / 260.0 * PI).toFloat()
    val hot = danger.coerceIn(0f, 1f)
    val color = androidx.compose.ui.graphics.lerp(AccentCyan, AccentPink, hot)
    val alpha = 0.28f + 0.55f * hot * pulse

    drawLine(
        color = color.copy(alpha = alpha),
        start = Offset(0f, y),
        end = Offset(WORLD_WIDTH * scale, y),
        strokeWidth = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(scale * 0.03f, scale * 0.02f))
    )
    if (hot > 0.5f) {
        drawRect(
            color = AccentPink.copy(alpha = 0.05f + 0.10f * (hot - 0.5f) * 2f * pulse),
            topLeft = Offset.Zero,
            size = Size(WORLD_WIDTH * scale, y)
        )
    }
}

/** The held ball plus the column it will fall down. */
private fun DrawScope.drawAimGuide(snapshot: MergeSnapshot, scale: Float) {
    val x = snapshot.aimX * scale
    val radius = radiusFor(snapshot.heldValue) * scale
    val alpha = if (snapshot.canDrop) 1f else 0.4f

    drawLine(
        color = Color.White.copy(alpha = 0.16f * alpha),
        start = Offset(x, DROP_Y * scale),
        end = Offset(x, Bowl.floorYAt(snapshot.aimX) * scale),
        strokeWidth = 1.5f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(scale * 0.02f, scale * 0.025f))
    )
    drawCircle(
        color = tileBackground(snapshot.heldValue).copy(alpha = alpha),
        radius = radius,
        center = Offset(x, DROP_Y * scale)
    )
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBall(
    cx: Float,
    cy: Float,
    radius: Float,
    value: Int,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val fill = tileBackground(value)
    drawCircle(color = fill, radius = radius, center = Offset(cx, cy))
    // A light rim reads as a sphere rather than a flat disc, and separates equal-coloured
    // balls resting against each other.
    drawCircle(
        color = Color.White.copy(alpha = 0.22f),
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = radius * 0.07f)
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.13f),
        radius = radius * 0.42f,
        center = Offset(cx - radius * 0.3f, cy - radius * 0.34f)
    )

    val digits = value.toString().length
    val ratio = when {
        digits >= 4 -> 0.52f
        digits == 3 -> 0.60f
        else -> 0.72f
    }
    val layout = textMeasurer.measure(
        text = value.toString(),
        style = TextStyle(
            color = tileForeground(value),
            fontSize = (radius * ratio / density).sp,
            fontWeight = FontWeight.ExtraBold
        )
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f)
    )
}

@Composable
private fun MergeOverlay(
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
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF0A0C18).copy(alpha = 0.92f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                title,
                color = TextLight,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(body, color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
            if (actionLabel != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = Color(0xFF101225)
                    ),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Text(actionLabel, fontWeight = FontWeight.Bold)
                }
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
