# 2048

Two takes on 2048 in one app. The launch screen picks between them.

- **2048 Descent** — a falling-block number merger: Tetris-style descent, 2048-style
  merging on a 6 x 10 grid.
- **2048 Merge** — a physics variant: numbered balls tumble into a curved bowl, roll to
  the middle, and grow with every combination.

```
prototype/index.html   Playable web version of Descent. The original design spec.
app/                   Android app (Kotlin + Jetpack Compose).
store/                 Play Store listing copy and generated graphics.
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

Both engines follow this rule, which is why a bowl full of colliding circles can be
verified without ever launching an emulator.

```
game/Model.kt         Descent: constants, Tile, snapshots, saved-game shape, events
game/Powers.kt        Descent: charge banks and regeneration
game/GameEngine.kt    Descent: gravity, merging, milestones, trophies, powers
merge/MergeModel.kt   Merge: world constants, bowl geometry, Ball, snapshots
merge/MergeEngine.kt  Merge: the physics solver and the merge rules
data/GameStorage.kt   SharedPreferences persistence, including the in-progress run
feedback/Haptics.kt   Turns game events into vibration
GameViewModel.kt      Descent loop, bridges engine to Compose
MergeViewModel.kt     Merge loop
ui/HomeScreen.kt      The game picker
ui/                   Compose rendering and input
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

## 2048 Merge

Numbered balls drop into a bowl. Equal balls merge on contact, and every merge makes the
result **physically bigger** — so the bowl fills faster than the numbers climb. Let the
pile rest above the line and the run is over.

The floor is a **circular arc, concave up**, which is what makes the game work: a flat
floor lets balls stack in columns wherever they happen to land, while a bowl rolls
everything toward the middle so contact is constant and the pile self-organises.

### How the physics works

The solver is **position-based**: integrate, push overlapping bodies apart over eight
relaxation passes, then read velocity back off how far each ball actually moved. Deriving
velocity from displacement rather than accumulating impulses means a constraint can never
inject energy, so a deep pile settles instead of slowly boiling.

Four details are load-bearing, and each one is guarded by a test:

- **Fixed 120Hz timestep with an accumulator.** A variable timestep makes stacking both
  non-deterministic and unstable — one long frame drives balls far enough into each other
  that the solver cannot recover. It also means the tests exercise exactly the arithmetic
  the device will.
- **Damping is applied once per substep, never inside the relaxation loop.** Inside the
  loop it compounds by the iteration count; at eight iterations even a gentle-looking
  factor removes three quarters of the tangential velocity per substep, which pins every
  ball where it lands and kills the rolling the bowl exists to create.
- **The floor arc is only applied below the bowl's centre.** The bowl circle is closed, so
  constraining against it everywhere puts an invisible ceiling across the top of the play
  area — the pile would be squashed back down instead of overflowing, which is the exact
  situation the game is supposed to end on.
- **Merging is checked every substep, not once per frame.** The solver settles a contact
  at exactly `rA + rB` and turns that correction into outward velocity, so a pair checked
  only at frame boundaries can be pushed back out of reach before anyone looks. Merges
  would then fire or not depending on how many substeps a frame happened to contain.
  A small [MERGE_CONTACT_SLACK] on top makes resting contact reliable rather than a
  floating-point coin flip.

Ball radii are a hand-tuned table, not derived. The tempting rule — double the area each
merge, so radius scales by sqrt(2) — compounds to roughly 45x across the ladder, which no
phone screen can hold.

## 2048 Descent rules

- One numbered tile (2–64) falls at a time. Slide it to choose a column.
- Equal numbers merge when they touch — **vertically or horizontally**, not diagonally.
  Merges chain until the board is stable.
- The next 3 tiles are previewed.
- Every tile enters in the middle column. **Tetris rule: if that spawn cell is blocked,
  the run is over** — the spawn never relocates to a free column, because letting the
  stack reach the top is exactly how you lose. The top of the board pulses in warning
  as the stack closes in.
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

`MergeEngineTest` covers the bowl geometry, containment under load, tunnelling, rolling
to the centre, settling, the merge rules and contact slack, combo scoring, the
overflow loss condition and its grace period, determinism for a given seed, frame-rate
independence, and the catch-up cap after a stall.

`GameEngineTest` covers merge rules (including the horizontal-after-vertical case),
combo scoring, the trophy ladder, gravity around locked trophies, movement blocking,
speed milestones, power charges and regeneration, targeted row deletion, the blocked-spawn
loss condition, pause, save/restore round trips, the render-revision contract, the Plan window and its
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
