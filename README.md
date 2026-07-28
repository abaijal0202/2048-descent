# 2048 Descent

A falling-block number merger: Tetris-style descent, 2048-style merging.

```
prototype/index.html   Playable web version. The original design spec — open it in any browser.
app/                   Android app (Kotlin + Jetpack Compose).
```

## Build

Open the project root in Android Studio (Ladybug or newer) and let it sync, or:

```
gradlew.bat :app:assembleDebug        # Windows
./gradlew :app:assembleDebug          # macOS / Linux
gradlew.bat :app:testDebugUnitTest    # run the engine test suite
```

Requires JDK 17 — AGP 8.5 will not run on anything older, and Gradle 8.7 will not run on
anything newer than 21. Android Studio bundles a suitable one;
`File > Settings > Build Tools > Gradle` sets the JDK if the build complains.

CI runs the unit tests and a debug assemble on every push.

## Architecture

The rules live in `app/src/main/java/com/digiboxx/descent2048/game/` and have **no
Android dependencies at all** — no `Context`, no coroutines, no `System.currentTimeMillis`.
Every method that needs the time takes it as a parameter.

That is deliberate. It means the entire rule set runs as fast JVM unit tests with no
emulator, and it is why `GameEngineTest` can fuzz ten thousand pieces in well under a
second. Keep it that way: if you find yourself importing `android.*` into the `game`
package, the logic probably belongs in the ViewModel instead.

```
game/Model.kt        Constants, Tile, snapshots, saved-game shape, events
game/Powers.kt       Charge banks and regeneration
game/GameEngine.kt   Gravity, merging, milestones, trophies, powers
data/GameStorage.kt  SharedPreferences persistence, including the in-progress run
feedback/Haptics.kt  Turns GameEvents into vibration
GameViewModel.kt     Game loop, bridges engine to Compose
ui/                  Compose rendering and input
```

### Rendering contract

Two details in here are load-bearing and easy to undo by accident:

- **`GameEngine.revision`** is bumped on every change that alters the board. The loop
  polls at 60fps but the board changes a few times a second, so the ViewModel only
  rebuilds a `BoardSnapshot` when the revision moves. Rebuilding unconditionally
  allocated a `CellView` per tile on every frame.
- **`HudTimers` is separate from `BoardSnapshot`** and quantised to whole seconds. While
  the millisecond countdowns lived in the board snapshot, the snapshot was never equal to
  the previous frame's and the entire board recomposed at 60fps just to animate a
  "+1 in 12:34" label.

`BoardCanvas` reads its frame clock **inside** the draw lambda. A state read from a
`DrawScope` invalidates only the draw phase, so the board animates at 60fps without
recomposing. Move that read into the composable body and you recompose the subtree every
frame.

## Rules

- One numbered tile (2–64) falls at a time. Slide it to choose a column.
- Equal numbers merge when they touch — **vertically or horizontally**, not diagonally.
  Merges chain until the board is stable.
- The next 3 tiles are previewed.
- Tiles enter in the middle column, or the nearest free column when the middle is full.
  The game only ends once the entire top row is blocked.
- Fall speed increases 20% at each of 512, 1024, 2048, 4096 and 8192. These compound.
- Three powers, 3 charges each, +1 charge every 30 minutes:
  - **Delete Row** — tap the power, then **tap the row you want cleared**. The board
    re-settles and re-resolves afterwards.
  - **Slow** — stretches the fall interval for 30 seconds.
  - **Plan** — see below.

### Plan

For 15 seconds, **gravity switches off entirely and the board becomes a plain 2048 grid**.
Swipe (or use the four arrows) to slide every tile in that direction; equal tiles merge on
contact, each tile merging at most once per swipe, exactly like classic 2048. Nothing
falls, so you can deliberately park tiles in mid-air.

When the timer expires gravity returns and the whole board settles and cascades at once —
which is where the payoff is, because a structure assembled in mid-air collapses into one
long combo chain.

Locked trophies do not move and split each line into segments that compact independently,
so a trophy can never be shunted out of its corner. Pausing during Plan **ends** the Plan
rather than freezing its timer; otherwise pause would be an unlimited-duration Plan for
free.

### Trophies

Reaching a rung of the ladder — **2048, then 4096, then 8192** — clears the board and
locks a permanent trophy into the next slot along the bottom-left. Each trophy also adds
`0.5` to a permanent score multiplier.

The ladder exists because a single 2048 left the game with nowhere to go: the most
exciting moment in a run was immediately followed by its least interesting phase, a
permanently faster board with a corner handicap and no new goal.

### Combos

A cascade pays progressively more. The nth merge resolved in one chain scores at
`1 + (n - 1) × 0.5`, capped at 4x, on top of the trophy multiplier. Before this a
five-deep cascade was worth exactly the same as five unrelated merges, so the most
skilful thing in the game was mechanically invisible.

### Merge tiebreak

When two equal tiles sit side by side, the survivor is chosen in this order: the tile the
player just placed or merged into, then whichever has support beneath it, then the
left-hand tile. The first rule is the one that matters — it means the result appears
where the player was aiming.

## Feel

- The board is **6 x 10** rather than the original 7 x 13. Cell size is what makes a
  four-digit tile legible, and height is the binding constraint, so fewer rows is what
  buys the size — dropping to 6 columns is what stops the width budget capping it
  straight back. A cell goes from ~44dp to ~52–62dp depending on the phone.
- The falling tile is **interpolated** between gravity steps rather than jumping a whole
  cell at a time, and merged tiles **pop** using the `poppedAtMs` the engine already
  records.
- `GameEvent` drives haptics — merges scale with combo depth, milestones double-tap,
  a trophy plays a rising flourish. Toggle it from the pause screen.
- The top of the board pulses once the stack nears the ceiling, so a loss is never a
  surprise.

## Tunables

Everything balance-related is a constant in `game/Model.kt`:

| Constant | Meaning |
|---|---|
| `COLS`, `ROWS` | Board size (6 x 10) |
| `BASE_INTERVAL_MS` | Starting fall interval |
| `SPAWN_TABLE` | Value spawn weights |
| `MERGE_HORIZONTAL` | `false` reverts to vertical-only merging |
| `TROPHY_LADDER` | The sequence of goals |
| `SPEED_MILESTONES`, `SPEED_STEP` | Where and how much the board speeds up |
| `COMBO_STEP`, `MAX_COMBO_MULTIPLIER` | Cascade scoring |
| `TROPHY_SCORE_BONUS` | Score multiplier gained per trophy |
| `POWER_REGEN_MS` | Charge recharge interval |
| `SLOW_FACTOR`, `SLOW_DURATION_MS` | Slow power strength and duration |
| `PLAN_DURATION_MS` | Length of the Plan window |
| `DANGER_CLEARANCE` | How close to the ceiling the warning appears |

## Testing

`GameEngineTest` covers merge rules (including the horizontal-after-vertical case),
combo scoring, the trophy ladder, gravity around locked trophies, movement blocking,
speed milestones, power charges and regeneration, targeted row deletion, spawn fallback,
pause, save/restore round trips, the render-revision contract, the Plan window and its
2048 slide rules, and a fuzz run asserting the board is never left with an unresolved
pair or a floating tile.

Balance signal from the fuzz run: with **random** column choice the best tile reached is
256 and 2048 never happens. The goal is skill-gated, not luck-gated.

That number was 512 on the old 7 x 13 board. Going to 6 x 10 for readability cost about
34% of the play area and the fuzz felt it immediately — random runs are now roughly a
third shorter. The fuzz never spends a Plan charge, so it understates real play, but if
the board ever starts feeling punishing rather than demanding, this is the number to
watch and `ROWS` is the dial.

## Known gaps

- The launcher icon is a placeholder vector. Replace it with Image Asset Studio before
  any store release.
- No sound. Haptics carry the feedback for now; `GameEvent` is already the right seam to
  hook audio onto, it just needs assets.
- Power charges are stored in `SharedPreferences` against wall-clock time. A player can
  edit the device clock or the prefs file to refill instantly. That is acceptable while
  charges are only earned; **if charges ever become purchasable, the balance must move
  server-side**, because at that point clock-editing becomes theft rather than
  self-cheating.
- The saved run is likewise unsigned local state, so it is editable on a rooted device.
  Same reasoning: fine while nothing is bought.
- Portrait only. The board aspect ratio does not suit landscape.
- TalkBack gets a description of the board and labels on every control, but the board is
  a single canvas — there is no per-tile navigation.
