package com.digiboxx.descent2048

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.digiboxx.descent2048.data.GameStorage
import com.digiboxx.descent2048.monetize.MonetizeHost
import com.digiboxx.descent2048.ui.GameChoice
import com.digiboxx.descent2048.ui.BlocksScreen
import com.digiboxx.descent2048.ui.GameScreen
import com.digiboxx.descent2048.ui.HomeScreen
import com.digiboxx.descent2048.ui.MergeScreen
import com.digiboxx.descent2048.ui.theme.BgDeep
import com.digiboxx.descent2048.ui.theme.Descent2048Theme

class MainActivity : ComponentActivity() {

    // Both are `by viewModels()` and therefore lazy: the one you never open is never
    // constructed, so its game loop never starts.
    private val descentViewModel: GameViewModel by viewModels()
    private val mergeViewModel: MergeViewModel by viewModels()
    private val blocksViewModel: BlocksViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // With no SDK configured this leaves the app unmonetised, which is a valid
        // shipping state. Release builds never get the simulated gateway.
        MonetizeHost.install(applicationContext, BuildConfig.DEBUG)
        setContent {
            Descent2048Theme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                    color = BgDeep
                ) {
                    AppRoot(descentViewModel, mergeViewModel, blocksViewModel)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(
    descent: GameViewModel,
    merge: MergeViewModel,
    blocks: BlocksViewModel
) {
    // Stored as an ordinal because rememberSaveable persists through a Bundle, and an
    // Int is unambiguously safe there where an arbitrary enum is not.
    var choiceOrdinal by rememberSaveable { mutableIntStateOf(GameChoice.HOME.ordinal) }
    val choice = GameChoice.entries[choiceOrdinal]
    val lifecycleOwner = LocalLifecycleOwner.current

    // Without this a tile keeps descending, or a pile keeps settling, while the player is
    // in another app — and they come back to a game they have already lost.
    DisposableEffect(lifecycleOwner, choice) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> when (choice) {
                    GameChoice.DESCENT -> descent.onPause()
                    GameChoice.MERGE -> merge.onPause()
                    GameChoice.BLOCKS -> blocks.onPause()
                    GameChoice.HOME -> Unit
                }
                Lifecycle.Event.ON_RESUME -> when (choice) {
                    GameChoice.DESCENT -> descent.onResume()
                    GameChoice.MERGE -> merge.onResume()
                    GameChoice.BLOCKS -> blocks.onResume()
                    GameChoice.HOME -> Unit
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back out to the picker rather than closing the app mid-run.
    BackHandler(enabled = choice != GameChoice.HOME) {
        when (choice) {
            GameChoice.DESCENT -> descent.pause()
            GameChoice.MERGE -> merge.pause()
            GameChoice.BLOCKS -> blocks.pause()
            GameChoice.HOME -> Unit
        }
        choiceOrdinal = GameChoice.HOME.ordinal
    }

    when (choice) {
        GameChoice.HOME -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            // Read straight from storage: the picker needs both games' bests, and asking
            // a ViewModel would construct it and start its loop for nothing.
            val storage = remember(context) { GameStorage(context.applicationContext) }
            HomeScreen(
                descentHighScore = storage.highScore,
                mergeHighScore = storage.mergeHighScore,
                blocksHighScore = storage.blocksHighScore,
                removeAdsPrice = MonetizeHost.billing.priceLabel(
                    com.digiboxx.descent2048.monetize.Product.REMOVE_ADS
                ),
                adsRemoved = descent.adsRemoved || MonetizeHost.billing.entitlements.adsRemoved,
                onRemoveAds = descent::buyRemoveAds,
                onChoose = { choiceOrdinal = it.ordinal }
            )
        }

        GameChoice.DESCENT -> GameScreen(
            snapshot = descent.snapshot,
            hud = descent.hud,
            highScore = descent.highScore,
            deleteArmed = descent.deleteArmed,
            hapticsEnabled = descent.hapticsEnabled,
            onStart = descent::startGame,
            onPause = descent::pause,
            onResume = descent::resume,
            onMove = descent::move,
            onMoveTo = descent::moveTo,
            onHardDrop = descent::hardDrop,
            onSoftDrop = descent::setSoftDrop,
            onArmDeleteRow = descent::armDeleteRow,
            onDeleteRowAt = descent::deleteRowAt,
            canDeleteRow = descent::canDeleteRow,
            onSlow = descent::useSlow,
            onPlan = descent::usePlan,
            onSlide = descent::slide,
            onToggleHaptics = descent::toggleHaptics,
            currentColumn = descent::fallingColumn,
            canContinue = descent.canContinue,
            adInFlight = descent.adInFlight,
            onContinue = descent::continueWithAd,
            onBack = {
                descent.pause()
                choiceOrdinal = GameChoice.HOME.ordinal
            }
        )

        GameChoice.MERGE -> MergeScreen(
            snapshot = merge.snapshot,
            highScore = merge.highScore,
            hapticsEnabled = merge.hapticsEnabled,
            onStart = merge::startGame,
            onPause = merge::pause,
            onResume = merge::resume,
            onAim = merge::aimAt,
            onDrop = merge::drop,
            onToggleHaptics = merge::toggleHaptics,
            canContinue = merge.canContinue,
            adInFlight = merge.adInFlight,
            onContinue = merge::continueWithAd,
            onBack = {
                merge.pause()
                choiceOrdinal = GameChoice.HOME.ordinal
            }
        )

        GameChoice.BLOCKS -> BlocksScreen(
            snapshot = blocks.snapshot,
            highScore = blocks.highScore,
            hapticsEnabled = blocks.hapticsEnabled,
            onStart = blocks::startGame,
            onPause = blocks::pause,
            onResume = blocks::resume,
            onMove = blocks::move,
            onRotate = blocks::rotate,
            onHardDrop = blocks::hardDrop,
            onSoftDrop = blocks::setSoftDrop,
            onToggleHaptics = blocks::toggleHaptics,
            canContinue = blocks.canContinue,
            adInFlight = blocks.adInFlight,
            onContinue = blocks::continueWithAd,
            onBack = {
                blocks.pause()
                choiceOrdinal = GameChoice.HOME.ordinal
            }
        )
    }
}
