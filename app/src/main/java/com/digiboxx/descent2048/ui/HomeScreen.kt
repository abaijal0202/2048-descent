package com.digiboxx.descent2048.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.digiboxx.descent2048.ui.theme.AccentCyan
import com.digiboxx.descent2048.ui.theme.AccentPink
import com.digiboxx.descent2048.ui.theme.BgPanel
import com.digiboxx.descent2048.ui.theme.BgPanel2
import com.digiboxx.descent2048.ui.theme.TextLight
import com.digiboxx.descent2048.ui.theme.TextMuted
import com.digiboxx.descent2048.ui.theme.TrophyGold
import com.digiboxx.descent2048.ui.theme.tileBackground
import com.digiboxx.descent2048.ui.theme.tileForeground

/** Which game the app is showing. */
enum class GameChoice { HOME, DESCENT, MERGE }

/**
 * The launch screen: pick a version of 2048.
 *
 * Each card previews its game with the actual shapes it uses — squares stacked in a grid
 * for Descent, circles nested in a bowl for Merge — so the difference reads before any
 * text is involved.
 */
@Composable
fun HomeScreen(
    descentHighScore: Int,
    mergeHighScore: Int,
    onChoose: (GameChoice) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1F3A), Color(0xFF131625))))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "2048",
                color = TextLight,
                fontSize = 46.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "PICK YOUR VERSION",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(2.dp))

            GameCard(
                title = "2048 DESCENT",
                tagline = "Tetris falls. 2048 merges.",
                blurb = "Numbered tiles drop one at a time. Merge sideways and down, " +
                    "chain combos, and stop time with Plan.",
                accent = AccentCyan,
                highScore = descentHighScore,
                preview = { DescentPreview() },
                onClick = { onChoose(GameChoice.DESCENT) }
            )

            GameCard(
                title = "2048 MERGE",
                tagline = "Drop, roll, combine.",
                blurb = "Numbered balls tumble into a curved bowl and roll to the middle. " +
                    "Every merge makes them bigger. Don't overflow.",
                accent = AccentPink,
                highScore = mergeHighScore,
                preview = { MergePreview() },
                onClick = { onChoose(GameChoice.MERGE) }
            )
        }
    }
}

@Composable
private fun GameCard(
    title: String,
    tagline: String,
    blurb: String,
    accent: Color,
    highScore: Int,
    preview: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgPanel)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(14.dp)
            .semantics { contentDescription = "$title. $tagline. Best score $highScore." },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0xFF0A0C18)),
            contentAlignment = Alignment.Center
        ) { preview() }

        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextLight, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text(tagline, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(blurb, color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (highScore > 0) "BEST $highScore" else "NOT PLAYED YET",
                color = if (highScore > 0) TrophyGold else TextMuted.copy(alpha = 0.6f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Stacked squares, mirroring the Descent board. */
@Composable
private fun DescentPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PreviewTile(2, 15.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            PreviewTile(8, 15.dp)
            PreviewTile(8, 15.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            PreviewTile(64, 15.dp)
            PreviewTile(32, 15.dp)
            PreviewTile(16, 15.dp)
        }
    }
}

@Composable
private fun PreviewTile(value: Int, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(3.dp))
            .background(tileBackground(value)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            value.toString(),
            color = tileForeground(value),
            fontSize = 6.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/** Circles nested in a bowl, mirroring the Merge playfield. */
@Composable
private fun MergePreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        PreviewBall(4, 13.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            PreviewBall(8, 16.dp)
            PreviewBall(16, 20.dp)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            PreviewBall(32, 22.dp)
            PreviewBall(64, 26.dp)
        }
        // The curve the balls roll into.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                .background(BgPanel2)
        )
    }
}

@Composable
private fun PreviewBall(value: Int, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(percent = 50))
            .background(tileBackground(value)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            value.toString(),
            color = tileForeground(value),
            fontSize = 6.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
