# 2048 Descent

A falling-block number merger: Tetris-style descent, 2048-style merging.

```
prototype/index.html   Playable web version. The design spec — open it in any browser.
app/                   Android app (Kotlin + Jetpack Compose).
```

## Build

Open the project root in Android Studio (Ladybug or newer) and let it sync, or:

```
gradlew.bat :app:assembleDebug        # Windows
./gradlew :app:assembleDebug          # macOS / Linux
gradlew.bat :app:testDebugUnitTest    # run the engine test suite
```

Requires JDK 17. Android Studio bundles one; `File > Settings > Build Tools > Gradle`
sets the JDK if the build complains about Java version.

## Architecture

The rules live in `app/src/main/java/com/digiboxx/descent2048/game/` and have **no
Android dependencies at all** — no `Context`, no coroutines, no `System.currentTimeMillis`.
Every method that needs the time takes it as a parameter.

That is deliberate. It means the entire rule set runs as fast JVM unit tests with no
emulator, and it is why `GameEngineTest` can fuzz twelve thousand pieces in under a
second. Keep it that way: if you find yourself importing `android.*` into the `game`
package, the logic probably belongs in the ViewModel instead.

```
game/Model.kt        Constants, Tile, snapshots, events
game/Powers.kt       Charge banks and regeneration
game/GameEngine.kt   Gravity, merging, milestones, powers
data/GameStorage.kt  SharedPreferences persistence
GameViewModel.kt     Game loop, bridges engine to Compose
ui/                  Compose rendering and input
```

## Rules

- One numbered tile (2–64) falls at a time. Slide it to choose a column.
- Equal numbers merge when they touch — **vertically or horizontally**, not diagonally.
  Merges chain until the board is stable.
- The next 3 tiles are previewed.
- Fall speed increases 20% at each of 512, 1024 and 2048. These compound, so the board
  runs at ~1.73x base speed once 2048 is reached.
- The first 2048 clears the board and locks into the bottom-left corner permanently.
- Two powers, 3 charges each, +1 charge every 30 minutes:
  - **Delete Row** — clears the lowest occupied row, then re-settles and re-resolves.
  - **Slow** — stretches the fall interval for 30 seconds.

### Merge tiebreak

When two equal tiles sit side by side, the survivor is chosen in this order: the tile the
player just placed or merged into, then whichever has support beneath it, then the
left-hand tile. The first rule is the one that matters — it means the result appears
where the player was aiming.

## Tunables

Everything balance-related is a constant in `game/Model.kt`:

| Constant | Meaning |
|---|---|
| `COLS`, `ROWS` | Board size (7 x 13) |
| `BASE_INTERVAL_MS` | Starting fall interval |
| `SPAWN_TABLE` | Value spawn weights |
| `MERGE_HORIZONTAL` | `false` reverts to vertical-only merging |
| `POWER_REGEN_MS` | Charge recharge interval |
| `SLOW_FACTOR`, `SLOW_DURATION_MS` | Slow power strength and duration |

## Testing

`GameEngineTest` covers merge rules (including the horizontal-after-vertical case),
gravity around the locked trophy, movement blocking, speed milestones, power charges and
regeneration, and a fuzz run asserting the board is never left with an unresolved pair or
a floating tile.

Balance signal from the fuzz run: with **random** column choice the best tile reached is
512 and 2048 never happens. The goal is skill-gated, not luck-gated.

## Known gaps

- The launcher icon is a placeholder vector. Replace it with Image Asset Studio before
  any store release.
- No sound or haptics yet. `GameEvent` already emits `Merged`, `Landed`, `TrophyEarned`
  and `SpeedIncreased`, so hooking them up is additive.
- Power charges are stored in `SharedPreferences` against wall-clock time. A player can
  edit the device clock or the prefs file to refill instantly. That is acceptable while
  charges are only earned; **if charges ever become purchasable, the balance must move
  server-side**, because at that point clock-editing becomes theft rather than
  self-cheating.
- Portrait only. The board aspect ratio does not suit landscape.
