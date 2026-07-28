"""
Generates the Play Store graphics for 2048 Descent.

Everything here is driven by the same colour tokens and layout rules the app itself
uses (ui/theme/Color.kt and ui/GameScreen.kt), so the output matches the real build
rather than being an independent illustration. If you change the palette or the board
size in the app, re-run this.

    python store/generate_assets.py

Rendering is done at SS x scale and downsampled, because Pillow's shape drawing is not
antialiased and rounded tile corners look ragged without it.
"""

import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
os.makedirs(OUT, exist_ok=True)

SS = 3  # supersampling factor

# ---------------------------------------------------------------- palette
# Mirrors ui/theme/Color.kt exactly.
BG_DEEP = (0x13, 0x16, 0x25)
BG_GRADIENT_TOP = (0x1A, 0x1F, 0x3A)
BG_PANEL = (0x1C, 0x21, 0x38)
BG_PANEL2 = (0x24, 0x2A, 0x47)
BOARD_BG = (0x0D, 0x10, 0x20)
ACCENT_CYAN = (0x5E, 0xEA, 0xD4)
ACCENT_PINK = (0xF4, 0x72, 0xB6)
TEXT_LIGHT = (0xF4, 0xF6, 0xFB)
TEXT_MUTED = (0x8B, 0x93, 0xB0)
TROPHY_GOLD = (0xFA, 0xCC, 0x15)
OVERLAY = (0x0A, 0x0C, 0x18)

TILE_FILLS = {
    2:    ((0x6E, 0xE7, 0xB7), (0x0B, 0x3B, 0x2E)),
    4:    ((0x5E, 0xEA, 0xD4), (0x0B, 0x3B, 0x36)),
    8:    ((0x38, 0xBD, 0xF8), (0x08, 0x2A, 0x3D)),
    16:   ((0x81, 0x8C, 0xF8), (0x1A, 0x1A, 0x45)),
    32:   ((0xA7, 0x8B, 0xFA), (0x24, 0x1A, 0x45)),
    64:   ((0xE8, 0x79, 0xF9), (0x3D, 0x0A, 0x3D)),
    128:  ((0xF4, 0x72, 0xB6), (0x3D, 0x0A, 0x22)),
    256:  ((0xFB, 0x71, 0x85), (0x3D, 0x0A, 0x10)),
    512:  ((0xFB, 0x92, 0x3C), (0x3D, 0x1A, 0x00)),
    1024: ((0xFB, 0xBF, 0x24), (0x3D, 0x2A, 0x00)),
    2048: ((0xFA, 0xCC, 0x15), (0x3D, 0x30, 0x00)),
}
FALLBACK_FILL = ((0xF4, 0xF6, 0xFB), (0x13, 0x16, 0x25))

FONT_DIR = r"C:\Windows\Fonts"
F_BLACK = os.path.join(FONT_DIR, "seguibl.ttf")
F_BOLD = os.path.join(FONT_DIR, "segoeuib.ttf")

COLS, ROWS = 6, 10

_font_cache = {}


def font(path, size):
    key = (path, int(size))
    if key not in _font_cache:
        _font_cache[key] = ImageFont.truetype(path, max(1, int(size)))
    return _font_cache[key]


def tile_colors(value):
    return TILE_FILLS.get(value, FALLBACK_FILL)


def vgradient(draw, box, top, bottom):
    x0, y0, x1, y1 = box
    height = max(1, y1 - y0)
    for i in range(height):
        t = i / height
        draw.line(
            [(x0, y0 + i), (x1, y0 + i)],
            fill=tuple(int(top[c] + (bottom[c] - top[c]) * t) for c in range(3)),
        )


def centered(draw, text, fnt, cx, cy, fill):
    left, top, right, bottom = draw.textbbox((0, 0), text, font=fnt)
    draw.text((cx - (right - left) / 2 - left, cy - (bottom - top) / 2 - top),
              text, font=fnt, fill=fill)


def draw_tile(draw, x, y, cell, value, locked=False, alpha=255, scale=1.0):
    """One board tile, matching BoardCanvas.drawTile."""
    pad = cell * 0.07
    base = cell - pad * 2
    grown = base * scale
    inset = (base - grown) / 2
    x0, y0 = x + pad + inset, y + pad + inset
    x1, y1 = x0 + grown, y0 + grown
    radius = cell * 0.19
    bg, fg = tile_colors(value)

    draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=bg + (alpha,))
    if locked:
        draw.rounded_rectangle([x0, y0, x1, y1], radius=radius,
                               outline=(255, 255, 255, 160), width=max(2, int(cell * 0.05)))

    digits = len(str(value))
    ratio = 0.30 if digits >= 4 else (0.36 if digits == 3 else 0.42)
    centered(draw, str(value), font(F_BLACK, cell * ratio),
             (x0 + x1) / 2, (y0 + y1) / 2, fg + (alpha,))


def draw_board(img, draw, bx, by, cell, board, falling=None, landing=None,
               will_merge=False, plan_bar=None, delete_rows=None, falling_alpha=255):
    """The playfield: grid, tiles, landing guide and any active power overlay."""
    w, h = COLS * cell, ROWS * cell
    draw.rectangle([bx, by, bx + w, by + h], fill=BOARD_BG)

    grid_col = (255, 255, 255, 12)
    for c in range(1, COLS):
        draw.line([(bx + c * cell, by), (bx + c * cell, by + h)], fill=grid_col, width=1)
    for r in range(1, ROWS):
        draw.line([(bx, by + r * cell), (bx + w, by + r * cell)], fill=grid_col, width=1)

    if delete_rows:
        for r in delete_rows:
            draw.rectangle([bx, by + r * cell, bx + w, by + (r + 1) * cell],
                           fill=ACCENT_PINK + (38,))
            dash = cell * 0.18
            xx = bx
            while xx < bx + w:
                draw.line([(xx, by + r * cell), (min(xx + dash, bx + w), by + r * cell)],
                          fill=ACCENT_PINK + (190,), width=max(2, int(cell * 0.04)))
                draw.line([(xx, by + (r + 1) * cell),
                           (min(xx + dash, bx + w), by + (r + 1) * cell)],
                          fill=ACCENT_PINK + (190,), width=max(2, int(cell * 0.04)))
                xx += dash * 1.7

    for r in range(ROWS):
        for c in range(COLS):
            entry = board[r][c]
            if entry is None:
                continue
            value, locked = entry if isinstance(entry, tuple) else (entry, False)
            draw_tile(draw, bx + c * cell, by + r * cell, cell, value, locked=locked)

    if falling is not None:
        fv, fr, fc = falling
        if landing is not None:
            lx, ly = bx + fc * cell, by + landing * cell
            pad = cell * 0.07
            box = [lx + pad, ly + pad, lx + cell - pad, ly + cell - pad]
            if will_merge:
                draw.rounded_rectangle(box, radius=cell * 0.19, fill=ACCENT_CYAN + (26,))
                draw.rounded_rectangle(box, radius=cell * 0.19,
                                       outline=ACCENT_CYAN + (255,),
                                       width=max(3, int(cell * 0.07)))
            else:
                draw.rounded_rectangle(box, radius=cell * 0.19,
                                       outline=(255, 255, 255, 72),
                                       width=max(2, int(cell * 0.03)))
        draw_tile(draw, bx + fc * cell, by + fr * cell, cell, fv, alpha=falling_alpha)

    if plan_bar is not None:
        bar_h = cell * 0.16
        draw.rectangle([bx, by, bx + w, by + bar_h], fill=(0, 0, 0, 115))
        draw.rectangle([bx, by, bx + w * plan_bar, by + bar_h],
                       fill=TROPHY_GOLD if plan_bar >= 0.33 else ACCENT_PINK)


def panel(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def label_value(draw, x, y, label, value, s):
    """One 'SCORE 1284' pair from HeaderRow. Returns the width consumed."""
    fl, fv = font(F_BOLD, 10 * s), font(F_BLACK, 14 * s)
    draw.text((x, y - 5 * s), label, font=fl, fill=TEXT_MUTED)
    lw = draw.textbbox((0, 0), label, font=fl)[2]
    draw.text((x + lw + 5 * s, y - 7 * s), value, font=fv, fill=TEXT_LIGHT)
    return lw + 5 * s + draw.textbbox((0, 0), value, font=fv)[2]


def power_button(draw, box, s, label, charges, subtitle, enabled=True, highlighted=False):
    x0, y0, x1, y1 = box
    panel(draw, box, 10 * s, BG_PANEL2 if highlighted else BG_PANEL)
    cx = (x0 + x1) / 2
    a = 255 if enabled else 102
    centered(draw, label, font(F_BOLD, 9 * s), cx, y0 + 11 * s, TEXT_MUTED + (a,))
    centered(draw, f"{charges}/3", font(F_BLACK, 12 * s), cx, y0 + 24 * s,
             ACCENT_CYAN + (a,))
    if subtitle:
        centered(draw, subtitle, font(F_BOLD, 8 * s), cx, y0 + 35 * s, TEXT_MUTED + (255,))


def arrow(draw, cx, cy, size, direction, fill):
    """Solid triangle. Drawn as a polygon because Segoe UI Black has no arrow glyphs."""
    h = size / 2
    pts = {
        "left":  [(cx + h, cy - h), (cx + h, cy + h), (cx - h, cy)],
        "right": [(cx - h, cy - h), (cx - h, cy + h), (cx + h, cy)],
        "up":    [(cx - h, cy + h), (cx + h, cy + h), (cx, cy - h)],
        "down":  [(cx - h, cy - h), (cx + h, cy - h), (cx, cy + h)],
    }[direction]
    draw.polygon(pts, fill=fill)


def control_button(draw, box, s, text):
    panel(draw, box, 11 * s, BG_PANEL)
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    if text in ("left", "right", "up", "down"):
        arrow(draw, cx, cy, 15 * s, text, TEXT_LIGHT)
    else:
        centered(draw, text, font(F_BLACK, 15 * s), cx, cy, TEXT_LIGHT)


def banner(draw, cx, y, s, text, color):
    fnt = font(F_BLACK, 10 * s)
    l, t, r, b = draw.textbbox((0, 0), text, font=fnt)
    w, h = r - l, b - t
    pad_x, pad_y = 8 * s, 4 * s
    draw.rounded_rectangle(
        [cx - w / 2 - pad_x, y - pad_y, cx + w / 2 + pad_x, y + h + pad_y],
        radius=6 * s, fill=OVERLAY + (217,))
    draw.text((cx - w / 2 - l, y - t), text, font=fnt, fill=color)


def overlay_card(img, draw, box, s, title, body_lines, action=None):
    x0, y0, x1, y1 = box
    scrim = Image.new("RGBA", (int(x1 - x0), int(y1 - y0)), OVERLAY + (230,))
    img.paste(scrim, (int(x0), int(y0)), scrim)
    cx = (x0 + x1) / 2
    cy = (y0 + y1) / 2
    centered(draw, title, font(F_BLACK, 30 * s), cx, cy - 60 * s, TEXT_LIGHT)
    for i, line in enumerate(body_lines):
        centered(draw, line, font(F_BOLD, 12 * s), cx, cy - 18 * s + i * 18 * s, TEXT_MUTED)
    if action:
        fnt = font(F_BLACK, 13 * s)
        l, t, r, b = draw.textbbox((0, 0), action, font=fnt)
        w = r - l
        by0 = cy + 44 * s
        draw.rounded_rectangle([cx - w / 2 - 18 * s, by0, cx + w / 2 + 18 * s, by0 + 34 * s],
                               radius=9 * s, fill=ACCENT_CYAN)
        centered(draw, action, fnt, cx, by0 + 17 * s, (0x10, 0x12, 0x25))


# ---------------------------------------------------------------- screenshot

def screenshot(path, board, falling=None, landing=None, will_merge=False,
               score=3480, best=9260, top=512, nxt=(4, 16, 2), goal="2048",
               speed="x1.00", mult="1.0x", powers=(3, 3, 3), planning=False,
               plan_bar=None, plan_secs=None, delete_rows=None, delete_armed=False,
               overlay=None, danger=False, W=1080, H=2160):
    """Renders one 1080x2160 store screenshot of the game screen."""
    img = Image.new("RGBA", (W * SS, H * SS), BG_DEEP + (255,))
    draw = ImageDraw.Draw(img, "RGBA")
    vgradient(draw, (0, 0, W * SS, H * SS), BG_GRADIENT_TOP, BG_DEEP)

    # dp -> device px. 393dp is a typical modern phone width.
    s = (W * SS) / 393.0
    content_w = min(393 - 20, 400) * s
    cx0 = (W * SS - content_w) / 2

    cell = min(content_w / COLS, ((H * SS / s) - 12 - 250) * s / ROWS)
    board_h = ROWS * cell
    total = (30 + 6 + 34 + 6 + 12 + 6 + 44 + 6 + 46) * s + board_h
    y = (H * SS - total) / 2

    # header
    panel(draw, [cx0, y, cx0 + content_w, y + 30 * s], 10 * s, BG_PANEL)
    hx = cx0 + 11 * s
    hy = y + 15 * s
    hx += label_value(draw, hx, hy, "SCORE", f"{score}", s) + 12 * s
    hx += label_value(draw, hx, hy, "BEST", f"{best}", s) + 12 * s
    label_value(draw, hx, hy, "TOP", f"{top}", s)
    pb = [cx0 + content_w - 32 * s, y + 7 * s, cx0 + content_w - 8 * s, y + 23 * s]
    panel(draw, pb, 6 * s, BG_PANEL2)
    centered(draw, "II", font(F_BLACK, 11 * s), (pb[0] + pb[2]) / 2, (pb[1] + pb[3]) / 2,
             TEXT_LIGHT)
    y += 36 * s

    # next + goal
    nw = content_w - 96 * s
    panel(draw, [cx0, y, cx0 + nw, y + 34 * s], 10 * s, BG_PANEL)
    draw.text((cx0 + 9 * s, y + 13 * s), "NEXT", font=font(F_BOLD, 9 * s), fill=TEXT_MUTED)
    tx = cx0 + 42 * s
    for v in nxt:
        bg, fg = tile_colors(v)
        draw.rounded_rectangle([tx, y + 5 * s, tx + 24 * s, y + 29 * s], radius=6 * s, fill=bg)
        centered(draw, str(v), font(F_BLACK, 10 * s), tx + 12 * s, y + 17 * s, fg)
        tx += 29 * s
    panel(draw, [cx0 + nw + 6 * s, y, cx0 + content_w, y + 34 * s], 10 * s, BG_PANEL)
    gcx = (cx0 + nw + 6 * s + cx0 + content_w) / 2
    centered(draw, f"GOAL {goal}", font(F_BLACK, 9 * s), gcx, y + 11 * s, TROPHY_GOLD)
    centered(draw, f"{speed} · {mult}", font(F_BLACK, 10 * s), gcx, y + 24 * s, ACCENT_CYAN)
    y += 40 * s

    # board
    panel(draw, [cx0, y, cx0 + content_w, y + board_h + 12 * s], 14 * s, BG_PANEL2)
    bx = cx0 + (content_w - COLS * cell) / 2
    by = y + 6 * s
    if danger:
        draw.rectangle([bx, by, bx + COLS * cell, by + 4 * cell], fill=ACCENT_PINK + (30,))
    draw_board(img, draw, bx, by, cell, board, falling, landing, will_merge,
               plan_bar=plan_bar, delete_rows=delete_rows,
               falling_alpha=115 if planning else 255)

    if planning:
        banner(draw, bx + COLS * cell / 2, by + 14 * s,
               s, f"PLAN · {plan_secs}s · SWIPE TO SLIDE", TROPHY_GOLD)
    elif delete_armed:
        banner(draw, bx + COLS * cell / 2, by + 6 * s, s, "TAP A ROW TO CLEAR IT", ACCENT_PINK)
    elif danger:
        banner(draw, bx + COLS * cell / 2, by + 6 * s, s, "STACK TOO HIGH", ACCENT_PINK)

    if overlay:
        overlay_card(img, draw, [bx, by, bx + COLS * cell, by + board_h], s, *overlay)
    y += board_h + 18 * s

    # powers
    pw = (content_w - 12 * s) / 3
    labels = [("CANCEL" if delete_armed else "DELETE ROW"), "SLOW 30s", "PLAN 15s"]
    subs = [("pick a row" if delete_armed else ""), "", (f"{plan_secs}s left" if planning else "")]
    for i in range(3):
        px = cx0 + i * (pw + 6 * s)
        power_button(draw, [px, y, px + pw, y + 44 * s], s, labels[i], powers[i], subs[i],
                     enabled=powers[i] > 0,
                     highlighted=(i == 0 and delete_armed) or (i == 2 and planning))
    y += 50 * s

    # controls
    if planning:
        glyphs, weights = ["left", "up", "down", "right"], [1, 1, 1, 1]
    else:
        glyphs, weights = ["left", "down", "right", "DROP"], [1, 1, 1, 1.4]
    unit = (content_w - 18 * s) / sum(weights)
    px = cx0
    for g, w in zip(glyphs, weights):
        control_button(draw, [px, y, px + unit * w, y + 46 * s], s, g)
        px += unit * w + 6 * s

    img.convert("RGB").resize((W, H), Image.LANCZOS).save(path, "PNG", optimize=True)
    print("wrote", os.path.basename(path))


# ---------------------------------------------------------------- icon

def app_icon(path, size=512):
    """
    Two tiles merging into one, descending. Chosen because it survives being shrunk to
    48px in a launcher: a strong silhouette and three flat colours, no small text.
    """
    S = size * SS
    img = Image.new("RGBA", (S, S), BG_DEEP + (255,))
    draw = ImageDraw.Draw(img, "RGBA")
    vgradient(draw, (0, 0, S, S), (0x22, 0x2A, 0x4E), (0x0D, 0x10, 0x20))

    cell = S * 0.30
    gap = S * 0.035
    top_y = S * 0.20
    bot_y = top_y + cell + gap
    cxm = S / 2

    for i, (val, col) in enumerate([(2, TILE_FILLS[2][0]), (2, TILE_FILLS[4][0])]):
        x = cxm - cell - gap / 2 + i * (cell + gap)
        draw.rounded_rectangle([x, top_y, x + cell, top_y + cell],
                               radius=cell * 0.22, fill=col)
        centered(draw, "2", font(F_BLACK, cell * 0.52), x + cell / 2, top_y + cell / 2,
                 TILE_FILLS[2][1])

    # downward arrows in the gutter, signalling the descent
    for i in range(2):
        ax = cxm - cell / 2 - gap / 2 + i * (cell + gap)
        ay = top_y + cell + gap * 0.32
        w = cell * 0.16
        draw.polygon([(ax - w, ay), (ax + w, ay), (ax, ay + gap * 0.42)],
                     fill=(255, 255, 255, 70))

    draw.rounded_rectangle([cxm - cell / 2, bot_y, cxm + cell / 2, bot_y + cell],
                           radius=cell * 0.22, fill=TROPHY_GOLD)
    centered(draw, "4", font(F_BLACK, cell * 0.56), cxm, bot_y + cell / 2,
             TILE_FILLS[2048][1])

    img.convert("RGB").resize((size, size), Image.LANCZOS).save(path, "PNG", optimize=True)
    print("wrote", os.path.basename(path))


# ---------------------------------------------------------------- feature graphic

def feature_graphic(path, W=1024, H=500):
    """The 1024x500 banner Play shows at the top of the listing."""
    img = Image.new("RGBA", (W * SS, H * SS), BG_DEEP + (255,))
    draw = ImageDraw.Draw(img, "RGBA")
    vgradient(draw, (0, 0, W * SS, H * SS), (0x1E, 0x24, 0x44), (0x0D, 0x10, 0x20))

    s = SS
    # faint grid, so the background reads as a playfield
    step = 46 * s
    for x in range(0, W * SS, step):
        draw.line([(x, 0), (x, H * SS)], fill=(255, 255, 255, 8), width=1)
    for y in range(0, H * SS, step):
        draw.line([(0, y), (W * SS, y)], fill=(255, 255, 255, 8), width=1)

    lx = 70 * s
    draw.text((lx, 150 * s), "2048", font=font(F_BLACK, 118 * s), fill=TEXT_LIGHT)
    draw.text((lx + 6 * s, 268 * s), "DESCENT", font=font(F_BLACK, 62 * s), fill=ACCENT_CYAN)
    draw.text((lx + 8 * s, 350 * s), "Tetris falls.  2048 merges.",
              font=font(F_BOLD, 26 * s), fill=TEXT_MUTED)
    draw.text((lx + 8 * s, 388 * s), "Stop time to plan your escape.",
              font=font(F_BOLD, 26 * s), fill=TEXT_MUTED)

    # a board fragment on the right
    cell = 74 * s
    bw, bh = 4 * cell, 5 * cell
    bx, by = W * SS - bw - 86 * s, (H * SS - bh) / 2
    draw.rounded_rectangle([bx - 10 * s, by - 10 * s, bx + bw + 10 * s, by + bh + 10 * s],
                           radius=16 * s, fill=BG_PANEL2)
    draw.rounded_rectangle([bx, by, bx + bw, by + bh], radius=10 * s, fill=BOARD_BG)
    for c in range(1, 4):
        draw.line([(bx + c * cell, by), (bx + c * cell, by + bh)], fill=(255, 255, 255, 14))
    for r in range(1, 5):
        draw.line([(bx, by + r * cell), (bx + bw, by + r * cell)], fill=(255, 255, 255, 14))

    frag = [
        [None, None, 8, None],
        [None, None, None, None],
        [None, 16, 32, None],
        [4, 64, 128, 16],
        [256, 512, 1024, 128],
    ]
    for r, row in enumerate(frag):
        for c, v in enumerate(row):
            if v:
                draw_tile(draw, bx + c * cell, by + r * cell, cell, v)

    # Landing guide, outline only: with a fill it reads as a blank tile rather than
    # as the target the falling 8 above it is heading for.
    pad = cell * 0.07
    draw.rounded_rectangle([bx + 2 * cell + pad, by + 1 * cell + pad,
                            bx + 3 * cell - pad, by + 2 * cell - pad],
                           radius=cell * 0.19, outline=ACCENT_CYAN + (255,), width=4 * s)

    img.convert("RGB").resize((W, H), Image.LANCZOS).save(path, "PNG", optimize=True)
    print("wrote", os.path.basename(path))


# ---------------------------------------------------------------- board states

def blank():
    return [[None] * COLS for _ in range(ROWS)]


def core_play():
    b = blank()
    b[9] = [512, 256, 128, 64, 32, 4]
    b[8] = [128, 64, 32, 16, 8, 2]
    b[7] = [16, 8, 4, 2, 4, 8]
    b[6] = [4, 2, 16, 8, 2, 16]
    b[5] = [2, 8, 4, 32, 16, 4]
    return b


def plan_state():
    b = blank()
    b[9] = [256, 128, 64, 32, 16, 8]
    b[8] = [64, 32, 16, 8, 4, None]
    b[7] = [8, 4, None, None, None, None]
    b[3] = [None, None, 32, 32, None, None]   # parked in mid-air, gravity is off
    b[4] = [None, 16, 16, None, None, None]
    return b


def delete_state():
    b = blank()
    b[9] = [512, 256, 128, 64, 32, 16]
    b[8] = [64, 8, 2, 16, 4, 8]
    b[7] = [4, 32, 8, 2, 64, 2]
    b[6] = [2, 4, 16, 8, 2, 4]
    return b


def trophy_state():
    b = blank()
    b[9][0] = (2048, True)
    return b


if __name__ == "__main__":
    app_icon(os.path.join(OUT, "icon_512.png"))
    feature_graphic(os.path.join(OUT, "feature_graphic_1024x500.png"))

    screenshot(os.path.join(OUT, "screen_1_play.png"),
               core_play(), falling=(32, 1, 3), landing=4, will_merge=True)

    screenshot(os.path.join(OUT, "screen_2_plan.png"),
               plan_state(), falling=(16, 1, 3),
               planning=True, plan_bar=0.62, plan_secs=9,
               powers=(3, 3, 2), score=6120, top=256, nxt=(2, 8, 4))

    screenshot(os.path.join(OUT, "screen_3_delete.png"),
               delete_state(), falling=(8, 1, 3), landing=5,
               delete_armed=True, delete_rows=[6, 7, 8, 9],
               powers=(3, 3, 3), score=4870, top=512, danger=False)

    screenshot(os.path.join(OUT, "screen_4_trophy.png"),
               trophy_state(),
               score=18240, best=18240, top=2048, goal="4096", speed="x1.73", mult="1.5x",
               overlay=("2048!",
                        ["Board cleared and the trophy is locked in.",
                         "Every merge now scores 1.5x.",
                         "Next goal: 4096."],
                        None))

    screenshot(os.path.join(OUT, "screen_5_danger.png"),
               core_play_danger := (lambda: (
                   lambda b: (b.__setitem__(5, [8, 16, 4, 2, None, None]),
                              b.__setitem__(4, [2, 4, 8, 16, None, None]),
                              b.__setitem__(3, [None, None, 2, 4, None, None]),
                              b)[-1])(core_play()))(),
               falling=(4, 1, 3), landing=2, will_merge=False, danger=True,
               score=9310, top=512, powers=(1, 2, 0))
    print("\nAll assets written to", OUT)
