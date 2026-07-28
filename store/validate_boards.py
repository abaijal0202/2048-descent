"""
Checks that every board drawn in the store screenshots is a state the real engine could
actually reach.

Worth having: a screenshot showing two touching 4s, or a tile hovering over a gap, is a
state the engine resolves away instantly. Anyone who plays the game would spot it, and it
undermines the listing. The two invariants mirror GameEngineTest's fuzz assertions.
"""

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("gen", os.path.join(HERE, "generate_assets.py"))
gen = importlib.util.module_from_spec(spec)
sys.modules["gen"] = gen
spec.loader.exec_module(gen)

COLS, ROWS = gen.COLS, gen.ROWS


def value_of(entry):
    return entry[0] if isinstance(entry, tuple) else entry


def is_locked(entry):
    return isinstance(entry, tuple) and entry[1]


def check(name, board, gravity_on=True, auto_resolves=True):
    """
    [gravity_on] false means tiles may hover over gaps.
    [auto_resolves] false means equal tiles may sit touching without combining.

    Both are true during normal play and both are false during a Plan window, where the
    engine runs no resolver at all — merges happen only on a swipe. A pair sitting
    adjacent mid-Plan is not a bug, it is the player lining up a payoff.
    """
    unresolved = []
    for r in range(ROWS):
        for c in range(COLS):
            cell = board[r][c]
            if cell is None or is_locked(cell):
                continue
            for dr, dc, tag in ((1, 0, "below"), (0, 1, "right")):
                nr, nc = r + dr, c + dc
                if nr >= ROWS or nc >= COLS:
                    continue
                other = board[nr][nc]
                if other is None or is_locked(other):
                    continue
                if value_of(cell) == value_of(other):
                    unresolved.append(f"{value_of(cell)} at r{r}c{c} equals the tile {tag}")

    floating = 0
    for c in range(COLS):
        seen_gap = False
        for r in range(ROWS - 1, -1, -1):
            if board[r][c] is None:
                seen_gap = True
            elif seen_gap:
                floating += 1

    problems = list(unresolved) if auto_resolves else []
    if gravity_on and floating:
        problems.append(f"{floating} tile(s) floating over a gap")

    print(f"{'OK  ' if not problems else 'FAIL'}  {name}")
    for p in problems[:5]:
        print(f"        - {p}")
    return not problems


if __name__ == "__main__":
    results = [
        check("screen_1_play", gen.core_play()),
        # Plan suspends gravity and the resolver, so mid-air tiles and touching pairs
        # are both expected — that board is a player mid-setup, about to swipe.
        check("screen_2_plan", gen.plan_state(), gravity_on=False, auto_resolves=False),
        check("screen_3_delete", gen.delete_state()),
        check("screen_4_trophy", gen.trophy_state()),
    ]
    print()
    print("all boards legal" if all(results) else "SOME BOARDS ARE ILLEGAL")
    sys.exit(0 if all(results) else 1)
