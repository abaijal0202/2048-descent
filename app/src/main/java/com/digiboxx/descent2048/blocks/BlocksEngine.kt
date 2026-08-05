package com.digiboxx.descent2048.blocks

import kotlin.math.min
import kotlin.random.Random

/**
 * Rules for 2048 Blocks.
 *
 * Same discipline as the other two engines: no Android imports and no clock of its own,
 * so the whole rule set runs as plain JVM tests.
 *
 * The governing invariant is that **no two equal values ever share an edge** once the
 * board has settled. [resolveBoard] is what maintains it, and the fuzz test asserts it
 * after every single placement.
 */
class BlocksEngine(private val rng: Random = Random.Default) {

    private val grid: Array<Array<BlockTile?>> =
        Array(BLOCK_ROWS) { arrayOfNulls<BlockTile>(BLOCK_COLS) }
    private var nextTileId = 1L

    var falling: FallingPiece? = null
        private set

    private val queue = ArrayDeque<FallingPiece>()

    var score: Int = 0
        private set
    var bestValue: Int = 0
        private set
    var lines: Int = 0
        private set
    var status: BlocksStatus = BlocksStatus.READY
        private set
    var revision: Int = 0
        private set
    var lastComboDepth: Int = 0
        private set

    var softDrop: Boolean = false

    private var reachedTarget = false
    private var lastStepMs = 0L
    private val pendingEvents = mutableListOf<BlocksEvent>()

    val level: Int get() = 1 + lines / LINES_PER_LEVEL

    private fun markDirty() { revision++ }

    // ---------------------------------------------------------------- lifecycle

    fun start(nowMs: Long) {
        for (r in 0 until BLOCK_ROWS) for (c in 0 until BLOCK_COLS) grid[r][c] = null
        score = 0
        bestValue = 0
        lines = 0
        lastComboDepth = 0
        reachedTarget = false
        softDrop = false
        pendingEvents.clear()
        queue.clear()
        repeat(3) { queue.addLast(randomPiece()) }
        status = BlocksStatus.PLAYING
        lastStepMs = nowMs
        spawnNext()
        markDirty()
    }

    fun pause() {
        if (status != BlocksStatus.PLAYING) return
        status = BlocksStatus.PAUSED
        softDrop = false
        markDirty()
    }

    fun resume(nowMs: Long) {
        if (status != BlocksStatus.PAUSED) return
        status = BlocksStatus.PLAYING
        lastStepMs = nowMs
        markDirty()
    }

    fun resyncClock(nowMs: Long) {
        lastStepMs = nowMs
    }

    private fun randomValue(): Int {
        val total = BLOCK_SPAWN_TABLE.sumOf { it.second }
        var roll = rng.nextInt(total)
        for ((value, weight) in BLOCK_SPAWN_TABLE) {
            if (roll < weight) return value
            roll -= weight
        }
        return BLOCK_SPAWN_TABLE.first().first
    }

    private fun randomPiece(): FallingPiece {
        val type = PieceType.entries[rng.nextInt(PieceType.entries.size)]
        val shape = PIECE_SHAPES.getValue(type)
        return FallingPiece(
            type = type,
            rotation = 0,
            row = 0,
            col = (BLOCK_COLS - shape.boxSize) / 2,
            values = List(4) { randomValue() }
        )
    }

    /**
     * Bring in the next piece, or end the run if it will not fit.
     *
     * Same rule the other games use: if the piece cannot occupy its spawn cells, the
     * stack has reached the top and that is the loss.
     */
    private fun spawnNext() {
        val piece = queue.removeFirst()
        queue.addLast(randomPiece())
        if (collides(piece)) {
            status = BlocksStatus.GAME_OVER
            falling = null
            pendingEvents += BlocksEvent.GameOver
            markDirty()
            return
        }
        falling = piece
        markDirty()
    }

    /** True when any of [piece]'s cells is off the board or on an occupied cell. */
    private fun collides(piece: FallingPiece): Boolean = piece.boardCells().any { cell ->
        cell.col < 0 || cell.col >= BLOCK_COLS ||
            cell.row >= BLOCK_ROWS ||
            (cell.row >= 0 && grid[cell.row][cell.col] != null)
    }

    // ---------------------------------------------------------------- input

    fun move(direction: Int): Boolean {
        if (status != BlocksStatus.PLAYING) return false
        val current = falling ?: return false
        val moved = current.copy(col = current.col + direction)
        if (collides(moved)) {
            pendingEvents += BlocksEvent.Blocked
            return false
        }
        falling = moved
        markDirty()
        return true
    }

    /**
     * Rotate clockwise, shuffling sideways if the turn would collide.
     *
     * Without the kick offsets a piece simply refuses to turn when it is flush against a
     * wall, which reads as the game ignoring the input.
     */
    fun rotate(): Boolean {
        if (status != BlocksStatus.PLAYING) return false
        val current = falling ?: return false
        for (kick in ROTATION_KICKS) {
            val candidate = current.copy(
                rotation = (current.rotation + 1) % 4,
                col = current.col + kick
            )
            if (!collides(candidate)) {
                falling = candidate
                pendingEvents += BlocksEvent.Rotated
                markDirty()
                return true
            }
        }
        pendingEvents += BlocksEvent.Blocked
        return false
    }

    fun hardDrop(nowMs: Long) {
        if (status != BlocksStatus.PLAYING) return
        var current = falling ?: return
        while (true) {
            val next = current.copy(row = current.row + 1)
            if (collides(next)) break
            current = next
        }
        falling = current
        lock(nowMs)
        lastStepMs = nowMs
        markDirty()
    }

    /** Where the piece would come to rest, for the ghost outline. */
    fun ghostCells(): List<Cell> {
        var current = falling ?: return emptyList()
        while (true) {
            val next = current.copy(row = current.row + 1)
            if (collides(next)) break
            current = next
        }
        return current.boardCells()
    }

    // ---------------------------------------------------------------- clock

    fun currentIntervalMs(): Long {
        var interval = BLOCK_BASE_INTERVAL_MS / (1.0 + LEVEL_SPEEDUP * (level - 1))
        if (softDrop) interval /= BLOCK_SOFT_DROP_FACTOR
        return interval.toLong().coerceAtLeast(BLOCK_MIN_INTERVAL_MS)
    }

    fun tick(nowMs: Long): List<BlocksEvent> {
        if (status != BlocksStatus.PLAYING) return drainEvents()
        val current = falling ?: return drainEvents()
        if (nowMs - lastStepMs < currentIntervalMs()) return drainEvents()
        lastStepMs = nowMs

        val next = current.copy(row = current.row + 1)
        if (collides(next)) lock(nowMs) else falling = next
        markDirty()
        return drainEvents()
    }

    private fun drainEvents(): List<BlocksEvent> {
        if (pendingEvents.isEmpty()) return emptyList()
        val out = pendingEvents.toList()
        pendingEvents.clear()
        return out
    }

    // ---------------------------------------------------------------- locking

    private fun lock(nowMs: Long) {
        val current = falling ?: return
        val cells = current.boardCells()
        cells.forEachIndexed { index, cell ->
            if (cell.row in 0 until BLOCK_ROWS && cell.col in 0 until BLOCK_COLS) {
                val value = current.values[index]
                grid[cell.row][cell.col] = BlockTile(nextTileId++, value)
                    .apply { poppedAtMs = nowMs }
                if (value > bestValue) bestValue = value
            }
        }
        falling = null
        pendingEvents += BlocksEvent.Locked

        resolveBoard(nowMs)

        if (status == BlocksStatus.PLAYING) spawnNext()
        markDirty()
    }

    // ---------------------------------------------------------------- resolution

    /**
     * Settle, clear, merge, repeat until the board is stable.
     *
     * The loop order matters. Gravity runs first so nothing is left floating over a hole
     * that a merge just opened; line clears are taken before merges so a row completed by
     * the placement itself still counts; and only one merge is applied per pass so each
     * link of a chain can be scored separately as a combo.
     *
     * Termination is guaranteed — every merge removes a tile and every clear removes a
     * row — but the guard stays because an infinite loop here would freeze the UI thread.
     */
    private fun resolveBoard(nowMs: Long) {
        var depth = 0
        var guard = 0
        while (guard++ < BLOCK_ROWS * BLOCK_COLS * 4) {
            settleGravity()
            if (clearFullRows(nowMs)) continue
            if (!mergeOnePair(depth + 1, nowMs)) break
            depth++
        }
        lastComboDepth = depth
        settleGravity()
    }

    /** Compact every column downward, preserving tile identity so the UI can animate. */
    private fun settleGravity() {
        for (col in 0 until BLOCK_COLS) {
            val stack = ArrayList<BlockTile>(BLOCK_ROWS)
            for (row in 0 until BLOCK_ROWS) {
                grid[row][col]?.let { stack.add(it) }
                grid[row][col] = null
            }
            var writeRow = BLOCK_ROWS - 1
            for (i in stack.indices.reversed()) {
                grid[writeRow][col] = stack[i]
                writeRow--
            }
        }
    }

    /** Remove every completely filled row. Returns true when any were taken. */
    private fun clearFullRows(nowMs: Long): Boolean {
        val full = (0 until BLOCK_ROWS).filter { row ->
            (0 until BLOCK_COLS).all { col -> grid[row][col] != null }
        }
        if (full.isEmpty()) return false

        for (row in full) {
            for (col in 0 until BLOCK_COLS) grid[row][col] = null
        }
        lines += full.size
        val previousLevel = level
        score += LINE_SCORES[min(full.size, LINE_SCORES.size - 1)] * level
        pendingEvents += BlocksEvent.LinesCleared(full.size, full)
        if (level > previousLevel) pendingEvents += BlocksEvent.LevelUp(level)
        return true
    }

    private fun comboMultiplier(depth: Int): Double =
        (1.0 + (depth - 1).coerceAtLeast(0) * BLOCK_COMBO_STEP).coerceAtMost(BLOCK_MAX_COMBO)

    /**
     * Apply a single merge, if the no-equal-neighbours rule is being broken anywhere.
     *
     * Vertical pairs are taken first and scanned bottom-up, so a column collapses from
     * the floor upward the way a player expects to see. For a horizontal pair the tile
     * with support beneath it survives, falling back to the left-hand one, which keeps
     * the outcome deterministic.
     */
    private fun mergeOnePair(depth: Int, nowMs: Long): Boolean {
        for (row in BLOCK_ROWS - 1 downTo 1) {
            for (col in 0 until BLOCK_COLS) {
                val lower = grid[row][col]
                val upper = grid[row - 1][col]
                if (lower != null && upper != null && lower.value == upper.value) {
                    lower.value *= 2
                    lower.poppedAtMs = nowMs
                    grid[row - 1][col] = null
                    award(lower.value, depth, row, col, nowMs)
                    return true
                }
            }
        }
        for (row in BLOCK_ROWS - 1 downTo 0) {
            for (col in 0 until BLOCK_COLS - 1) {
                val left = grid[row][col]
                val right = grid[row][col + 1]
                if (left == null || right == null || left.value != right.value) continue

                val leftSupported = row == BLOCK_ROWS - 1 || grid[row + 1][col] != null
                val rightSupported = row == BLOCK_ROWS - 1 || grid[row + 1][col + 1] != null
                val keepRight = rightSupported && !leftSupported

                val keep = if (keepRight) right else left
                val keepCol = if (keepRight) col + 1 else col
                val dropCol = if (keepRight) col else col + 1

                grid[row][dropCol] = null
                grid[row][keepCol] = keep
                keep.value *= 2
                keep.poppedAtMs = nowMs
                award(keep.value, depth, row, keepCol, nowMs)
                return true
            }
        }
        return false
    }

    private fun award(value: Int, depth: Int, row: Int, col: Int, nowMs: Long) {
        score += (value * comboMultiplier(depth)).toInt()
        if (value > bestValue) bestValue = value
        pendingEvents += BlocksEvent.Merged(value, row, col, depth)
        if (value >= BLOCK_TARGET && !reachedTarget) {
            reachedTarget = true
            pendingEvents += BlocksEvent.TargetReached(value)
        }
    }

    // ---------------------------------------------------------------- rendering

    /** Empty rows above the highest settled tile. */
    fun clearance(): Int {
        for (row in 0 until BLOCK_ROWS) {
            for (col in 0 until BLOCK_COLS) {
                if (grid[row][col] != null) return row
            }
        }
        return BLOCK_ROWS
    }

    fun snapshot(): BlocksSnapshot {
        val cells = ArrayList<BlockCellView>(BLOCK_ROWS * BLOCK_COLS)
        for (row in 0 until BLOCK_ROWS) {
            for (col in 0 until BLOCK_COLS) {
                val tile = grid[row][col] ?: continue
                cells.add(BlockCellView(tile.id, row, col, tile.value, tile.poppedAtMs))
            }
        }
        val current = falling
        val fallingCells = current?.boardCells()?.mapIndexed { index, cell ->
            FallingCellView(cell.row, cell.col, current.values[index])
        } ?: emptyList()

        return BlocksSnapshot(
            cells = cells,
            falling = fallingCells,
            ghost = ghostCells(),
            nextPieces = queue.take(2).map { piece ->
                val shape = PIECE_SHAPES.getValue(piece.type)
                PiecePreview(piece.type, shape.cells, piece.values)
            },
            score = score,
            bestValue = bestValue,
            lines = lines,
            level = level,
            status = status,
            lastComboDepth = lastComboDepth,
            clearance = clearance(),
            stepStartMs = lastStepMs,
            stepDurationMs = currentIntervalMs()
        )
    }

    // ---------------------------------------------------------------- test hooks

    internal fun debugPlace(row: Int, col: Int, value: Int): BlockTile {
        val tile = BlockTile(nextTileId++, value)
        grid[row][col] = tile
        return tile
    }

    internal fun debugAt(row: Int, col: Int): BlockTile? = grid[row][col]

    internal fun debugClear() {
        for (r in 0 until BLOCK_ROWS) for (c in 0 until BLOCK_COLS) grid[r][c] = null
    }

    internal fun debugSetFalling(piece: FallingPiece) {
        falling = piece
    }

    internal fun debugForcePlaying() {
        status = BlocksStatus.PLAYING
    }

    internal fun debugTileCount(): Int = grid.sumOf { row -> row.count { it != null } }

    internal fun debugResolve(nowMs: Long) = resolveBoard(nowMs)

    /** Pairs of equal values sharing an edge — the invariant, so this must always be 0. */
    internal fun debugTouchingEqualPairs(): Int {
        var count = 0
        for (row in 0 until BLOCK_ROWS) {
            for (col in 0 until BLOCK_COLS) {
                val cell = grid[row][col] ?: continue
                if (row < BLOCK_ROWS - 1 && grid[row + 1][col]?.value == cell.value) count++
                if (col < BLOCK_COLS - 1 && grid[row][col + 1]?.value == cell.value) count++
            }
        }
        return count
    }

    internal fun debugFloatingTiles(): Int {
        var count = 0
        for (col in 0 until BLOCK_COLS) {
            var sawGap = false
            for (row in BLOCK_ROWS - 1 downTo 0) {
                if (grid[row][col] == null) sawGap = true else if (sawGap) count++
            }
        }
        return count
    }

    internal fun debugFullRows(): Int = (0 until BLOCK_ROWS).count { row ->
        (0 until BLOCK_COLS).all { col -> grid[row][col] != null }
    }
}
