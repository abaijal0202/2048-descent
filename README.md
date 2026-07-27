# 2048 Descent

A falling-block number merger: Tetris-style descent, 2048-style merging.

## Status

`index.html` is a complete, playable **web prototype** used as the design spec.
The Android app (Kotlin) is being built from it.

## Rules

- One numbered tile (2–64) falls at a time. Slide it left/right to pick a column.
- Equal numbers merge when they touch — **vertically or horizontally**. Merges chain
  until the board is stable.
- Next 3 tiles are previewed.
- Fall speed increases +20% at each of 512, 1024 and 2048 (compounding, ~1.73x at 2048).
- The first 2048 clears the board; the 2048 tile locks into the bottom-left corner permanently.
- Two powers, 3 charges each, +1 charge every 30 minutes:
  - **Delete Row** — clears the lowest occupied row, then settles the board.
  - **Slow** — reduces fall speed for 30 seconds.

## Tunables

All in the `<script>` block at the top of `index.html`:

| Constant | Meaning |
|---|---|
| `COLS`, `ROWS` | Board size (7 x 13) |
| `BASE_INTERVAL` | Starting fall interval, ms |
| `SPAWN_TABLE` | Value spawn weights |
| `MERGE_HORIZONTAL` | `false` reverts to vertical-only merging |
| `REGEN_MS` | Power recharge interval |
| `SLOW_FACTOR` | How much Slow stretches the fall interval |

## Running

Open `index.html` in any browser. No build step, no dependencies.

## Balance note

In 300 simulated games with random column choice, the best tile reached was 512 and
none reached 2048 — the goal is skill-gated rather than luck-gated.
