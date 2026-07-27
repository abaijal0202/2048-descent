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
    initialSlowBank: PowerBank = PowerBank()
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

    private var passed512 = false
    private var passed1024 = false
    private var passed2048 = false

    var deleteBank: PowerBank = initialDeleteBank
        private set
    var slowBank: PowerBank = initialSlowBank
        private set

    private var slowExpiresAtMs: Long = 0L
    var softDrop: Boolean = false

    private var lastStepMs: Long = 0L
    private var celebrateUntilMs: Long = 0L

    private val pendingEvents = mutableListOf<GameEvent>()

    // ---------------------------------------------------------------- lifecycle

    fun start(nowMs: Long) {
        for (r in 0 until ROWS) for (c in 0 until COLS) grid[r][c] = null
        score = 0
        bestTile = 0
        speedMultiplier = 1.0
        passed512 = false; passed1024 = false; passed2048 = false
        slowExpiresAtMs = 0L
        softDrop = false
        pendingEvents.clear()
        queue.clear()
        repeat(3) { queue.addLast(randomSpawnValue()) }
        status = GameStatus.PLAYING
        lastStepMs = nowMs
        spawnNext()
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

    private fun spawnNext() {
        // If the spawn cell is already occupied the stack has reached the ceiling.
        if (grid[0][START_COL] != null) {
            status = GameStatus.GAME_OVER
            falling = null
            pendingEvents += GameEvent.GameOver
            return
        }
        val value = queue.removeFirst()
        queue.addLast(randomSpawnValue())
        falling = FallingTile(value = value, row = 0, col = START_COL)
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
        if (col != current.col) falling = current.copy(col = col)
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

        when (status) {
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

        resolveBoard(tile, nowMs)

        if (status == GameStatus.PLAYING) spawnNext()
    }

    // ---------------------------------------------------------------- gravity

    /**
     * Compacts every column downward, preserving tile identity so the UI can animate
     * a tile falling rather than seeing it disappear and reappear.
     *
     * The locked trophy acts as a floor: tiles rest on top of it and never pass through.
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
    private fun findAndApplyOneMerge(active: Tile?, nowMs: Long): Tile? {
        for (row in ROWS - 1 downTo 1) {
            for (col in 0 until COLS) {
                val lower = grid[row][col]
                val upper = grid[row - 1][col]
                if (mergeable(lower, upper)) {
                    lower!!.value *= 2
                    lower.poppedAtMs = nowMs
                    grid[row - 1][col] = null
                    score += lower.value
                    pendingEvents += GameEvent.Merged(lower.value, row, col)
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
                score += keep.value
                pendingEvents += GameEvent.Merged(keep.value, row, keepCol)
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
     * is "driving". The guard is a safety net: the board has 91 cells and every merge
     * removes one tile, so termination is guaranteed, but an infinite loop here would
     * freeze the UI thread and that is not a risk worth leaving open.
     */
    private fun resolveBoard(active: Tile?, nowMs: Long) {
        var current = active
        var guard = 0
        while (guard++ < ROWS * COLS * 4) {
            settleGravity()
            if (status == GameStatus.CELEBRATING) return
            val merged = findAndApplyOneMerge(current, nowMs) ?: break
            current = merged
            if (status == GameStatus.CELEBRATING) return
        }
        settleGravity()
    }

    // ---------------------------------------------------------------- milestones

    private fun checkMilestone(value: Int, nowMs: Long) {
        if (value >= 512 && !passed512) {
            passed512 = true
            speedMultiplier *= 1.2
            pendingEvents += GameEvent.SpeedIncreased(512, speedMultiplier)
        }
        if (value >= 1024 && !passed1024) {
            passed1024 = true
            speedMultiplier *= 1.2
            pendingEvents += GameEvent.SpeedIncreased(1024, speedMultiplier)
        }
        if (value >= TROPHY_VALUE && !passed2048) {
            passed2048 = true
            speedMultiplier *= 1.2
            pendingEvents += GameEvent.SpeedIncreased(TROPHY_VALUE, speedMultiplier)
            awardTrophy(nowMs)
        }
        if (value > bestTile) bestTile = value
    }

    /** Clear everything and lock a permanent 2048 into the bottom-left corner. */
    private fun awardTrophy(nowMs: Long) {
        for (r in 0 until ROWS) for (c in 0 until COLS) grid[r][c] = null
        grid[ROWS - 1][0] = Tile(id = nextTileId++, value = TROPHY_VALUE, locked = true)
            .apply { poppedAtMs = nowMs }
        falling = null
        status = GameStatus.CELEBRATING
        celebrateUntilMs = nowMs + 2200L
        pendingEvents += GameEvent.TrophyEarned
    }

    // ---------------------------------------------------------------- powers

    /** Clears the lowest row containing at least one non-trophy tile. */
    fun useDeleteRow(nowMs: Long): Boolean {
        if (status != GameStatus.PLAYING) return false
        deleteBank = deleteBank.refresh(nowMs)
        if (deleteBank.charges <= 0) return false
        deleteBank = deleteBank.spend(nowMs)

        var target = -1
        outer@ for (row in ROWS - 1 downTo 0) {
            for (col in 0 until COLS) {
                val cell = grid[row][col]
                if (cell != null && !cell.locked) { target = row; break@outer }
            }
        }
        if (target >= 0) {
            for (col in 0 until COLS) {
                val cell = grid[target][col]
                if (cell != null && !cell.locked) grid[target][col] = null
            }
            // Clearing a row can drop tiles into new adjacencies, so re-resolve.
            resolveBoard(null, nowMs)
        }
        return true
    }

    fun useSlow(nowMs: Long): Boolean {
        if (status != GameStatus.PLAYING) return false
        slowBank = slowBank.refresh(nowMs)
        if (slowBank.charges <= 0) return false
        slowBank = slowBank.spend(nowMs)
        // Re-activating while already slow extends from now rather than stacking.
        slowExpiresAtMs = nowMs + SLOW_DURATION_MS
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
            status = status,
            deleteCharges = deleteBank.charges,
            slowCharges = slowBank.charges,
            deleteRegenRemainingMs = deleteBank.regenRemainingMs(nowMs),
            slowRegenRemainingMs = slowBank.regenRemainingMs(nowMs),
            slowActiveRemainingMs = (slowExpiresAtMs - nowMs).coerceAtLeast(0L)
        )
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
