package com.digiboxx.descent2048.game

import kotlin.random.Random

/**
 * All game rules live here. No Android imports, no coroutines, no clock of its own —
 * every method that needs the time takes it as a parameter. That keeps the whole rule
 * set unit-testable and deterministic when seeded.
 *
 * The engine mutates its grid in place for speed and exposes [snapshot] for rendering.
 */
class GameEngine(
    private val rng: Random = Random.Default,
    initialDeleteBank: PowerBank = PowerBank(),
    initialSlowBank: PowerBank = PowerBank(),
    initialPlanBank: PowerBank = PowerBank()
) {

    private val grid: Array<Array<Tile?>> = Array(ROWS) { arrayOfNulls<Tile>(COLS) }
    private var nextTileId = 1L

    var falling: FallingTile? = null
        private set

    private val queue = ArrayDeque<Int>()

    var score: Int = 0
        private set
    var bestTile: Int = 0
        private set
    var speedMultiplier: Double = 1.0
        private set
    var status: GameStatus = GameStatus.READY
        private set

    /**
     * Bumped on every change that alters what the board looks like.
     *
     * The render loop polls at 60fps but the board only changes a few times a second.
     * Comparing this is what lets the ViewModel skip rebuilding a snapshot — and with it
     * ~91 CellView allocations — on the ~95% of frames where nothing happened.
     */
    var revision: Int = 0
        private set

    private val passedMilestones = mutableSetOf<Int>()

    /** Trophy values won so far, in the order they were earned. */
    private val trophies = mutableListOf<Int>()

    val trophyCount: Int get() = trophies.size

    /** The next rung of [TROPHY_LADDER], or null once the ladder is complete. */
    val nextTrophyValue: Int? get() = TROPHY_LADDER.getOrNull(trophies.size)

    /** Every trophy earned permanently raises the value of every subsequent merge. */
    val scoreMultiplier: Double get() = 1.0 + trophies.size * TROPHY_SCORE_BONUS

    /** Length of the most recent merge chain, for the combo readout. */
    var lastComboDepth: Int = 0
        private set

    var deleteBank: PowerBank = initialDeleteBank
        private set
    var slowBank: PowerBank = initialSlowBank
        private set
    var planBank: PowerBank = initialPlanBank
        private set

    private var slowExpiresAtMs: Long = 0L
    private var planExpiresAtMs: Long = 0L
    var softDrop: Boolean = false

    private var lastStepMs: Long = 0L
    private var celebrateUntilMs: Long = 0L

    private val pendingEvents = mutableListOf<GameEvent>()

    private fun markDirty() { revision++ }

    // ---------------------------------------------------------------- lifecycle

    fun start(nowMs: Long) {
        for (r in 0 until ROWS) for (c in 0 until COLS) grid[r][c] = null
        score = 0
        bestTile = 0
        speedMultiplier = 1.0
        passedMilestones.clear()
        trophies.clear()
        lastComboDepth = 0
        slowExpiresAtMs = 0L
        planExpiresAtMs = 0L
        softDrop = false
        pendingEvents.clear()
        queue.clear()
        repeat(3) { queue.addLast(randomSpawnValue()) }
        status = GameStatus.PLAYING
        lastStepMs = nowMs
        spawnNext()
        markDirty()
    }

    /**
     * Halts gravity without discarding the run.
     *
     * Pausing out of a Plan window ends the Plan rather than freezing its timer —
     * otherwise pause is an unlimited-duration Plan, which is the whole power for free.
     */
    fun pause(nowMs: Long) {
        if (status == GameStatus.PLANNING) endPlan(nowMs)
        if (status != GameStatus.PLAYING) return
        status = GameStatus.PAUSED
        softDrop = false
        markDirty()
    }

    fun resume(nowMs: Long) {
        if (status != GameStatus.PAUSED) return
        status = GameStatus.PLAYING
        lastStepMs = nowMs
        markDirty()
    }

    private fun randomSpawnValue(): Int {
        val total = SPAWN_TABLE.sumOf { it.second }
        var roll = rng.nextInt(total)
        for ((value, weight) in SPAWN_TABLE) {
            if (roll < weight) return value
            roll -= weight
        }
        return SPAWN_TABLE.first().first
    }

    /**
     * Every tile enters at [START_COL].
     *
     * Tetris rule: if the spawn cell is blocked, the run is over. There is deliberately
     * no hunting for a free column elsewhere — letting the stack reach the top *is* how
     * you lose, and quietly relocating the spawn robs that of its meaning. The danger
     * wash over the top rows is the warning that it is coming.
     */
    private fun spawnNext() {
        if (grid[0][START_COL] != null) {
            endRun()
            return
        }
        val value = queue.removeFirst()
        queue.addLast(randomSpawnValue())
        falling = FallingTile(value = value, row = 0, col = START_COL)
        markDirty()
    }

    private fun endRun() {
        status = GameStatus.GAME_OVER
        falling = null
        pendingEvents += GameEvent.GameOver
        markDirty()
    }

    // ---------------------------------------------------------------- input

    /** Nudge one column left (-1) or right (+1). */
    fun move(direction: Int) {
        val current = falling ?: return
        moveTo(current.col + direction)
    }

    /**
     * Slide toward [targetCol], stopping at the first occupied cell on the way.
     * Walking one column at a time is what prevents a fast drag from tunnelling
     * straight through a standing block.
     */
    fun moveTo(targetCol: Int) {
        if (status != GameStatus.PLAYING) return
        val current = falling ?: return
        val clamped = targetCol.coerceIn(0, COLS - 1)
        if (clamped == current.col) return

        val step = if (clamped > current.col) 1 else -1
        var col = current.col
        while (col != clamped) {
            val next = col + step
            if (grid[current.row][next] != null) {
                pendingEvents += GameEvent.Blocked
                break
            }
            col = next
        }
        if (col != current.col) {
            falling = current.copy(col = col)
            markDirty()
        }
    }

    /** Drop straight to the landing row and settle immediately. */
    fun hardDrop(nowMs: Long) {
        if (status != GameStatus.PLAYING) return
        val current = falling ?: return
        var row = current.row
        while (row + 1 < ROWS && grid[row + 1][current.col] == null) row++
        falling = current.copy(row = row)
        land(nowMs)
        lastStepMs = nowMs
        markDirty()
    }

    // ---------------------------------------------------------------- clock

    /** Current gravity interval in ms, accounting for milestones, Slow and soft drop. */
    fun currentIntervalMs(nowMs: Long): Long {
        var interval = BASE_INTERVAL_MS / speedMultiplier
        if (isSlowActive(nowMs)) interval *= SLOW_FACTOR
        if (softDrop) interval /= SOFT_DROP_FACTOR
        return interval.toLong().coerceAtLeast(MIN_INTERVAL_MS)
    }

    fun isSlowActive(nowMs: Long): Boolean = nowMs < slowExpiresAtMs

    /**
     * Re-anchors the gravity clock to [nowMs].
     *
     * Call this when returning from the background. Without it, the elapsed time since
     * the last tick is treated as owed gravity and the tile teleports downward the
     * instant the player comes back.
     */
    fun resyncClock(nowMs: Long) {
        lastStepMs = nowMs
    }

    /**
     * Advances the game if enough time has passed. Call every frame; it is cheap and
     * self-throttling. Returns the events produced this tick.
     */
    fun tick(nowMs: Long): List<GameEvent> {
        deleteBank = deleteBank.detectClockRollback(nowMs).refresh(nowMs)
        slowBank = slowBank.detectClockRollback(nowMs).refresh(nowMs)
        planBank = planBank.detectClockRollback(nowMs).refresh(nowMs)

        when (status) {
            GameStatus.PLANNING -> {
                if (nowMs >= planExpiresAtMs) endPlan(nowMs)
                return drainEvents()
            }
            GameStatus.CELEBRATING -> {
                if (nowMs >= celebrateUntilMs) {
                    status = GameStatus.PLAYING
                    lastStepMs = nowMs
                    spawnNext()
                }
                return drainEvents()
            }
            GameStatus.PLAYING -> Unit
            else -> return drainEvents()
        }

        val current = falling ?: return drainEvents()
        if (nowMs - lastStepMs < currentIntervalMs(nowMs)) return drainEvents()
        lastStepMs = nowMs

        val below = current.row + 1
        if (below >= ROWS || grid[below][current.col] != null) {
            land(nowMs)
        } else {
            falling = current.copy(row = below)
        }
        markDirty()
        return drainEvents()
    }

    private fun drainEvents(): List<GameEvent> {
        if (pendingEvents.isEmpty()) return emptyList()
        val out = pendingEvents.toList()
        pendingEvents.clear()
        return out
    }

    // ---------------------------------------------------------------- landing

    private fun land(nowMs: Long) {
        val current = falling ?: return
        val tile = Tile(id = nextTileId++, value = current.value).apply { poppedAtMs = nowMs }
        grid[current.row][current.col] = tile
        falling = null
        pendingEvents += GameEvent.Landed
        // A tile that is merely placed counts toward the best-tile readout. Only
        // crediting merges meant a run full of spawned 64s still reported "—".
        if (tile.value > bestTile) bestTile = tile.value

        resolveBoard(tile, nowMs)

        if (status == GameStatus.PLAYING) spawnNext()
        markDirty()
    }

    // ---------------------------------------------------------------- gravity

    /**
     * Compacts every column downward, preserving tile identity so the UI can animate
     * a tile falling rather than seeing it disappear and reappear.
     *
     * A locked trophy acts as a floor: tiles rest on top of it and never pass through.
     */
    private fun settleGravity() {
        for (col in 0 until COLS) {
            var lockedRow: Int? = null
            for (row in 0 until ROWS) {
                if (grid[row][col]?.locked == true) { lockedRow = row; break }
            }
            val floor = if (lockedRow != null) lockedRow - 1 else ROWS - 1

            val stack = ArrayList<Tile>(ROWS)
            for (row in 0..floor) {
                grid[row][col]?.let { stack.add(it) }
                grid[row][col] = null
            }
            var writeRow = floor
            for (i in stack.indices.reversed()) {
                grid[writeRow][col] = stack[i]
                writeRow--
            }
        }
    }

    // ---------------------------------------------------------------- merging

    private fun mergeable(a: Tile?, b: Tile?): Boolean =
        a != null && b != null && !a.locked && !b.locked && a.value == b.value

    /** The nth merge of a chain is worth this much more than a lone merge. */
    private fun comboMultiplier(depth: Int): Double =
        (1.0 + (depth - 1).coerceAtLeast(0) * COMBO_STEP).coerceAtMost(MAX_COMBO_MULTIPLIER)

    private fun awardScore(value: Int, depth: Int) {
        score += (value * comboMultiplier(depth) * scoreMultiplier).toInt()
    }

    /**
     * Finds one merge and applies it, returning the surviving tile, or null when the
     * board is stable.
     *
     * Vertical pairs are handled first and scanned bottom-up so that a stack collapses
     * from the floor upward, which is what a player expects to see. Horizontal pairs
     * are resolved afterwards.
     *
     * For a horizontal merge the surviving cell is chosen in this order:
     *   1. the tile the player just placed or just merged into, so the result lands
     *      where they were aiming;
     *   2. whichever tile has support beneath it;
     *   3. the left-hand tile as a deterministic fallback.
     */
    private fun findAndApplyOneMerge(active: Tile?, depth: Int, nowMs: Long): Tile? {
        for (row in ROWS - 1 downTo 1) {
            for (col in 0 until COLS) {
                val lower = grid[row][col]
                val upper = grid[row - 1][col]
                if (mergeable(lower, upper)) {
                    lower!!.value *= 2
                    lower.poppedAtMs = nowMs
                    grid[row - 1][col] = null
                    awardScore(lower.value, depth)
                    pendingEvents += GameEvent.Merged(lower.value, row, col, depth)
                    checkMilestone(lower.value, nowMs)
                    return lower
                }
            }
        }

        if (!MERGE_HORIZONTAL) return null

        for (row in ROWS - 1 downTo 0) {
            for (col in 0 until COLS - 1) {
                val left = grid[row][col]
                val right = grid[row][col + 1]
                if (!mergeable(left, right)) continue

                val leftSupported = row == ROWS - 1 || grid[row + 1][col] != null
                val rightSupported = row == ROWS - 1 || grid[row + 1][col + 1] != null

                val keep: Tile
                val keepCol: Int
                val dropCol: Int
                when {
                    active != null && right === active -> { keep = right!!; keepCol = col + 1; dropCol = col }
                    active != null && left === active -> { keep = left!!; keepCol = col; dropCol = col + 1 }
                    leftSupported && !rightSupported -> { keep = left!!; keepCol = col; dropCol = col + 1 }
                    rightSupported && !leftSupported -> { keep = right!!; keepCol = col + 1; dropCol = col }
                    else -> { keep = left!!; keepCol = col; dropCol = col + 1 }
                }

                grid[row][dropCol] = null
                grid[row][keepCol] = keep
                keep.value *= 2
                keep.poppedAtMs = nowMs
                awardScore(keep.value, depth)
                pendingEvents += GameEvent.Merged(keep.value, row, keepCol, depth)
                checkMilestone(keep.value, nowMs)
                return keep
            }
        }
        return null
    }

    /**
     * Settle, merge, repeat until nothing else can combine.
     *
     * [active] follows the chain so each successive merge knows which tile the player
     * is "driving", and [depth] counts how deep the cascade has gone so the score can
     * reward it. The guard is a safety net: the board has 91 cells and every merge
     * removes one tile, so termination is guaranteed, but an infinite loop here would
     * freeze the UI thread and that is not a risk worth leaving open.
     */
    private fun resolveBoard(active: Tile?, nowMs: Long) {
        var current = active
        var depth = 0
        var guard = 0
        while (guard++ < ROWS * COLS * 4) {
            settleGravity()
            if (status == GameStatus.CELEBRATING) return
            val merged = findAndApplyOneMerge(current, depth + 1, nowMs) ?: break
            depth++
            current = merged
            if (status == GameStatus.CELEBRATING) return
        }
        lastComboDepth = depth
        settleGravity()
    }

    // ---------------------------------------------------------------- milestones

    private fun checkMilestone(value: Int, nowMs: Long) {
        for (milestone in SPEED_MILESTONES) {
            if (value >= milestone && passedMilestones.add(milestone)) {
                speedMultiplier *= SPEED_STEP
                pendingEvents += GameEvent.SpeedIncreased(milestone, speedMultiplier)
            }
        }
        if (value > bestTile) bestTile = value

        val target = nextTrophyValue
        if (target != null && value >= target) awardTrophy(target, nowMs)
    }

    /**
     * Clear the board and lock a permanent trophy into the next bottom-left slot.
     *
     * Previously earned trophies are re-placed alongside it, so the corner accumulates a
     * visible record of how far the run has gone.
     */
    private fun awardTrophy(value: Int, nowMs: Long) {
        trophies.add(value)
        for (r in 0 until ROWS) for (c in 0 until COLS) grid[r][c] = null
        trophies.forEachIndexed { index, trophyValue ->
            if (index < COLS) {
                grid[ROWS - 1][index] =
                    Tile(id = nextTileId++, value = trophyValue, locked = true)
                        .apply { poppedAtMs = nowMs }
            }
        }
        falling = null
        status = GameStatus.CELEBRATING
        celebrateUntilMs = nowMs + 2200L
        pendingEvents += GameEvent.TrophyEarned(value, trophies.size)
        markDirty()
    }

    // ---------------------------------------------------------------- powers

    /** Lowest row holding at least one non-trophy tile, or -1 when there is none. */
    fun lowestOccupiedRow(): Int {
        for (row in ROWS - 1 downTo 0) {
            for (col in 0 until COLS) {
                val cell = grid[row][col]
                if (cell != null && !cell.locked) return row
            }
        }
        return -1
    }

    /** True when Delete Row would actually remove something from [row]. */
    fun canDeleteRow(row: Int): Boolean {
        if (row !in 0 until ROWS) return false
        for (col in 0 until COLS) {
            val cell = grid[row][col]
            if (cell != null && !cell.locked) return true
        }
        return false
    }

    /**
     * Clears every non-trophy tile from [row].
     *
     * The player picks the row. The old behaviour always took the lowest occupied row,
     * which in a game where you deliberately build your largest tiles along the floor
     * meant the panic button destroyed exactly the work you were protecting.
     */
    fun useDeleteRowAt(row: Int, nowMs: Long): Boolean {
        if (status != GameStatus.PLAYING) return false
        deleteBank = deleteBank.refresh(nowMs)
        if (deleteBank.charges <= 0) return false
        // Validate before spending: clearing nothing used to still cost a charge.
        if (!canDeleteRow(row)) return false

        deleteBank = deleteBank.spend(nowMs)
        for (col in 0 until COLS) {
            val cell = grid[row][col]
            if (cell != null && !cell.locked) grid[row][col] = null
        }
        // Clearing a row can drop tiles into new adjacencies, so re-resolve.
        resolveBoard(null, nowMs)
        pendingEvents += GameEvent.PowerUsed
        markDirty()
        return true
    }

    /** Convenience overload targeting the lowest occupied row. */
    fun useDeleteRow(nowMs: Long): Boolean = useDeleteRowAt(lowestOccupiedRow(), nowMs)

    /**
     * Suspend gravity for [PLAN_DURATION_MS] and hand the player a 2048 board.
     *
     * The falling tile is frozen where it is and takes no part in the sliding — it is in
     * the air, not on the board.
     */
    fun usePlan(nowMs: Long): Boolean {
        if (status != GameStatus.PLAYING) return false
        planBank = planBank.refresh(nowMs)
        if (planBank.charges <= 0) return false
        planBank = planBank.spend(nowMs)
        planExpiresAtMs = nowMs + PLAN_DURATION_MS
        status = GameStatus.PLANNING
        softDrop = false
        pendingEvents += GameEvent.PowerUsed
        markDirty()
        return true
    }

    /** Milliseconds left in the Plan window, or 0 when it is not running. */
    fun planRemainingMs(nowMs: Long): Long =
        if (status == GameStatus.PLANNING) (planExpiresAtMs - nowMs).coerceAtLeast(0L) else 0L

    /**
     * Gravity returns. Everything settles and cascades in one go, which is the whole
     * point of having been allowed to stack tiles in mid-air for fifteen seconds.
     */
    private fun endPlan(nowMs: Long) {
        status = GameStatus.PLAYING
        planExpiresAtMs = 0L
        lastStepMs = nowMs
        resolveBoard(null, nowMs)
        if (status == GameStatus.PLAYING) restoreFallingAfterPlan()
        markDirty()
    }

    /**
     * Put the frozen tile somewhere legal again.
     *
     * Sliding upward can leave a tile sitting where the falling one was hanging. After
     * gravity resolves, a column's free cells are contiguous from the top, so the lowest
     * free cell in that column is the natural place to drop it back to.
     */
    private fun restoreFallingAfterPlan() {
        val current = falling ?: return
        if (grid[current.row][current.col] == null) return

        var landing = -1
        for (row in 0 until ROWS) {
            if (grid[row][current.col] == null) landing = row else break
        }
        if (landing >= 0) {
            // Stay in the same column — shunting the tile sideways would be a free move.
            falling = current.copy(row = landing)
        } else {
            // The player stacked that column to the ceiling with gravity off. Nowhere
            // left to put the tile, so the run is over on the same rule as a blocked spawn.
            endRun()
        }
    }

    /**
     * One 2048 swipe: every line compacts toward [direction], and equal neighbours merge
     * once each. Locked trophies are immovable and split a line into segments that
     * compact independently, so a trophy never gets shunted out of its corner.
     *
     * Returns true when anything actually moved — a swipe that changes nothing should
     * not cost the player their arrangement or fire feedback.
     */
    fun slide(direction: SlideDirection, nowMs: Long): Boolean {
        if (status != GameStatus.PLANNING) return false

        var moved = false
        val lines: List<List<Pair<Int, Int>>> = when (direction) {
            SlideDirection.LEFT ->
                (0 until ROWS).map { row -> (0 until COLS).map { col -> row to col } }
            SlideDirection.RIGHT ->
                (0 until ROWS).map { row -> (COLS - 1 downTo 0).map { col -> row to col } }
            SlideDirection.UP ->
                (0 until COLS).map { col -> (0 until ROWS).map { row -> row to col } }
            SlideDirection.DOWN ->
                (0 until COLS).map { col -> (ROWS - 1 downTo 0).map { row -> row to col } }
        }

        for (line in lines) {
            if (status != GameStatus.PLANNING) break   // a trophy can interrupt mid-slide
            if (slideLine(line, nowMs)) moved = true
        }
        if (moved) markDirty()
        return moved
    }

    /** Splits one line on locked tiles and compacts each free run toward the head. */
    private fun slideLine(line: List<Pair<Int, Int>>, nowMs: Long): Boolean {
        var changed = false
        var segment = ArrayList<Pair<Int, Int>>()
        for (coord in line) {
            val (row, col) = coord
            if (grid[row][col]?.locked == true) {
                if (segment.isNotEmpty() && slideSegment(segment, nowMs)) changed = true
                segment = ArrayList()
            } else {
                segment.add(coord)
            }
        }
        if (segment.isNotEmpty() && slideSegment(segment, nowMs)) changed = true
        return changed
    }

    /**
     * Compacts one run of cells toward index 0, merging equal neighbours.
     *
     * Each tile merges at most once per swipe — the classic 2048 rule. Without it a row
     * of four 2s would collapse to a single 8 in one move instead of two 4s.
     */
    private fun slideSegment(coords: List<Pair<Int, Int>>, nowMs: Long): Boolean {
        val tiles = ArrayList<Tile>(coords.size)
        for ((row, col) in coords) grid[row][col]?.let { tiles.add(it) }
        if (tiles.isEmpty()) return false

        val result = ArrayList<Tile>(tiles.size)
        var index = 0
        var merges = 0
        while (index < tiles.size) {
            val tile = tiles[index]
            val next = tiles.getOrNull(index + 1)
            if (next != null && tile.value == next.value) {
                val destination = coords[result.size]
                tile.value *= 2
                tile.poppedAtMs = nowMs
                merges++
                awardScore(tile.value, merges)
                pendingEvents += GameEvent.Merged(
                    tile.value, destination.first, destination.second, merges
                )
                result.add(tile)
                checkMilestone(tile.value, nowMs)
                if (status != GameStatus.PLANNING) return true   // trophy cleared the board
                index += 2
            } else {
                result.add(tile)
                index += 1
            }
        }

        var changed = result.size != tiles.size
        for ((slot, coord) in coords.withIndex()) {
            val (row, col) = coord
            val replacement = result.getOrNull(slot)
            if (grid[row][col] !== replacement) changed = true
            grid[row][col] = replacement
        }
        return changed
    }

    fun useSlow(nowMs: Long): Boolean {
        if (status != GameStatus.PLAYING) return false
        slowBank = slowBank.refresh(nowMs)
        if (slowBank.charges <= 0) return false
        slowBank = slowBank.spend(nowMs)
        // Re-activating while already slow extends from now rather than stacking.
        slowExpiresAtMs = nowMs + SLOW_DURATION_MS
        pendingEvents += GameEvent.PowerUsed
        markDirty()
        return true
    }

    // ---------------------------------------------------------------- rendering

    /** Row the falling tile would come to rest on, for the landing guide. */
    fun landingRow(): Int {
        val current = falling ?: return 0
        var row = current.row
        while (row + 1 < ROWS && grid[row + 1][current.col] == null) row++
        return row
    }

    /** True when landing here would trigger at least one merge — drives the guide colour. */
    fun willMergeOnLanding(): Boolean {
        val current = falling ?: return false
        val row = landingRow()
        val neighbours = listOf(
            if (row + 1 < ROWS) grid[row + 1][current.col] else null,
            if (current.col > 0) grid[row][current.col - 1] else null,
            if (current.col < COLS - 1) grid[row][current.col + 1] else null
        )
        return neighbours.any { it != null && !it.locked && it.value == current.value }
    }

    /** Empty rows above the spawn column. Drives the "you are about to lose" warning. */
    fun spawnClearance(): Int {
        var clear = 0
        for (row in 0 until ROWS) {
            if (grid[row][START_COL] != null) break
            clear++
        }
        return clear
    }

    fun snapshot(nowMs: Long): BoardSnapshot {
        val cells = ArrayList<CellView>(ROWS * COLS)
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val tile = grid[row][col] ?: continue
                cells.add(
                    CellView(
                        id = tile.id,
                        row = row,
                        col = col,
                        value = tile.value,
                        locked = tile.locked,
                        poppedAtMs = tile.poppedAtMs
                    )
                )
            }
        }
        return BoardSnapshot(
            cells = cells,
            falling = falling,
            landingRow = landingRow(),
            willMergeOnLanding = willMergeOnLanding(),
            nextValues = queue.toList(),
            score = score,
            bestTile = bestTile,
            speedMultiplier = speedMultiplier,
            scoreMultiplier = scoreMultiplier,
            status = status,
            trophies = trophies.toList(),
            nextTrophyValue = nextTrophyValue,
            spawnClearance = spawnClearance(),
            lastComboDepth = lastComboDepth,
            deleteCharges = deleteBank.charges,
            slowCharges = slowBank.charges,
            planCharges = planBank.charges,
            planExpiresAtMs = planExpiresAtMs,
            stepStartMs = lastStepMs,
            stepDurationMs = currentIntervalMs(nowMs)
        )
    }

    /** The volatile countdowns, quantised to seconds. Cheap enough to call every frame. */
    fun hudTimers(nowMs: Long): HudTimers {
        fun toSeconds(ms: Long): Long = (ms + 999) / 1000
        return HudTimers(
            deleteRegenRemainingSec = toSeconds(deleteBank.regenRemainingMs(nowMs)),
            slowRegenRemainingSec = toSeconds(slowBank.regenRemainingMs(nowMs)),
            slowActiveRemainingSec = toSeconds((slowExpiresAtMs - nowMs).coerceAtLeast(0L)),
            planRegenRemainingSec = toSeconds(planBank.regenRemainingMs(nowMs)),
            planActiveRemainingSec = toSeconds(planRemainingMs(nowMs))
        )
    }

    // ---------------------------------------------------------------- persistence

    /** Snapshot the run for disk, or null when there is nothing worth saving. */
    fun exportState(): SavedGame? {
        if (status != GameStatus.PLAYING && status != GameStatus.PAUSED) return null
        val current = falling ?: return null

        val cells = ArrayList<SavedCell>()
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val tile = grid[row][col] ?: continue
                cells.add(SavedCell(row, col, tile.value, tile.locked))
            }
        }
        return SavedGame(
            cells = cells,
            fallingValue = current.value,
            fallingRow = current.row,
            fallingCol = current.col,
            queue = queue.toList(),
            score = score,
            bestTile = bestTile,
            trophies = trophies.toList(),
            passedMilestones = passedMilestones.toList(),
            speedMultiplier = speedMultiplier
        )
    }

    /**
     * Rebuild a run from disk. Restores into [GameStatus.PAUSED] on purpose — dropping
     * the player straight back into a live falling tile they had no time to read is a
     * good way to lose a run you just rescued.
     */
    fun importState(state: SavedGame, nowMs: Long) {
        for (r in 0 until ROWS) for (c in 0 until COLS) grid[r][c] = null
        nextTileId = 1L
        for (cell in state.cells) {
            if (cell.row !in 0 until ROWS || cell.col !in 0 until COLS) continue
            grid[cell.row][cell.col] = Tile(
                id = nextTileId++,
                value = cell.value,
                locked = cell.locked
            )
        }
        queue.clear()
        state.queue.forEach { queue.addLast(it) }
        while (queue.size < 3) queue.addLast(randomSpawnValue())

        score = state.score
        bestTile = state.bestTile
        speedMultiplier = state.speedMultiplier
        passedMilestones.clear(); passedMilestones.addAll(state.passedMilestones)
        trophies.clear(); trophies.addAll(state.trophies)
        lastComboDepth = 0
        slowExpiresAtMs = 0L
        planExpiresAtMs = 0L
        softDrop = false
        pendingEvents.clear()

        falling = FallingTile(
            value = state.fallingValue,
            row = state.fallingRow.coerceIn(0, ROWS - 1),
            col = state.fallingCol.coerceIn(0, COLS - 1)
        )
        lastStepMs = nowMs
        status = GameStatus.PAUSED
        markDirty()
    }

    // ---------------------------------------------------------------- test hooks

    /** Visible for testing: place a tile directly. */
    internal fun debugPlace(row: Int, col: Int, value: Int, locked: Boolean = false): Tile {
        val tile = Tile(id = nextTileId++, value = value, locked = locked)
        grid[row][col] = tile
        return tile
    }

    internal fun debugAt(row: Int, col: Int): Tile? = grid[row][col]

    internal fun debugClear() {
        for (r in 0 until ROWS) for (c in 0 until COLS) grid[r][c] = null
    }

    internal fun debugSetFalling(value: Int, row: Int, col: Int) {
        falling = FallingTile(value, row, col)
    }

    internal fun debugForcePlaying() {
        status = GameStatus.PLAYING
    }

    internal fun debugTileCount(): Int =
        grid.sumOf { row -> row.count { it != null } }

    internal fun debugUnmergedPairs(): Int {
        var count = 0
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val cell = grid[row][col] ?: continue
                if (row < ROWS - 1 && mergeable(cell, grid[row + 1][col])) count++
                if (col < COLS - 1 && mergeable(cell, grid[row][col + 1])) count++
            }
        }
        return count
    }

    /** Counts tiles with empty space beneath them — should always be zero after settling. */
    internal fun debugFloatingTiles(): Int {
        var count = 0
        for (col in 0 until COLS) {
            var sawGap = false
            for (row in ROWS - 1 downTo 0) {
                if (grid[row][col] == null) sawGap = true else if (sawGap) count++
            }
        }
        return count
    }
}
