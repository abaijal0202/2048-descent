package com.digiboxx.descent2048

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.digiboxx.descent2048.ui.GameScreen
import com.digiboxx.descent2048.ui.theme.BgDeep
import com.digiboxx.descent2048.ui.theme.Descent2048Theme

class MainActivity : ComponentActivity() {

    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            Descent2048Theme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                    color = BgDeep
                ) {
                    GameRoot(viewModel)
                }
            }
        }
    }
}

@Composable
private fun GameRoot(viewModel: GameViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Without this the tile keeps falling while the player is in another app, and they
    // come back to a game they have already lost.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
                Lifecycle.Event.ON_RESUME -> viewModel.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GameScreen(
        snapshot = viewModel.snapshot,
        hud = viewModel.hud,
        highScore = viewModel.highScore,
        deleteArmed = viewModel.deleteArmed,
        hapticsEnabled = viewModel.hapticsEnabled,
        onStart = viewModel::startGame,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onMove = viewModel::move,
        onMoveTo = viewModel::moveTo,
        onHardDrop = viewModel::hardDrop,
        onSoftDrop = viewModel::setSoftDrop,
        onArmDeleteRow = viewModel::armDeleteRow,
        onDeleteRowAt = viewModel::deleteRowAt,
        canDeleteRow = viewModel::canDeleteRow,
        onSlow = viewModel::useSlow,
        onPlan = viewModel::usePlan,
        onSlide = viewModel::slide,
        onToggleHaptics = viewModel::toggleHaptics,
        currentColumn = viewModel::fallingColumn
    )
}
