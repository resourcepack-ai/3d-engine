"""
Builds what 3D Pool adds to the resource pack beside its models, into
src/main/resources/pack/ (shipped into 3D Engine's pack/ folder):

  * sounds: a ball meeting a ball, a cushion, a pocket, the cue's tip, all
    synthesised here (modal synthesis: a few damped resonances and a click),
    several takes of each so no two hits sound alike;
  * the HUD font: a cue ball with a red dot where the tip will meet it, and a
    power bar;
  * the cue item: an icon in the inventory, nothing in the hand (the cue you
    see is the one on the table).

    python tools/make_pack.py
"""
import json
import math
import os

import numpy as np
import soundfile as sf
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "pack", "assets", "pool")
SR = 44100
rng = np.random.default_rng(5)


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as f:
        f.write(data)


# ---- sounds --------------------------------------------------------------------------------

def modes(length, partials, click=0.0, click_ms=1.5, noise_lp=None):
    t = np.arange(int(SR * length)) / SR
    y = np.zeros_like(t)
    for f, amp, decay in partials:
        f *= 1 + rng.normal(0, 0.015)
        y += amp * np.sin(2 * math.pi * f * t + rng.random() * 6.28) * np.exp(-t / decay)
    if click:
        n = rng.standard_normal(len(t)) * np.exp(-t / (click_ms / 1000))
        if noise_lp:
            k = int(SR / noise_lp)
            n = np.convolve(n, np.ones(k) / k, mode="same")
        y += click * n
    # A 0.3 ms attack, so nothing pops.
    a = int(SR * 0.0003)
    y[:a] *= np.linspace(0, 1, a)
    return y


def save(name, y):
    y = y / (np.abs(y).max() + 1e-9) * 0.9
    tail = int(SR * 0.004)
    y[-tail:] *= np.linspace(1, 0, tail)
    path = os.path.join(OUT, "sounds", name + ".ogg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    sf.write(path, y.astype(np.float32), SR, format="OGG", subtype="VORBIS")


def make_sounds():
    sounds = {}
    # Phenolic balls: a hard, bright clack, a short ring around 3-7 kHz.
    for i in range(4):
        save("ball%d" % i, modes(0.16, [(2950, 0.6, 0.010), (4420, 0.9, 0.007), (6800, 0.5, 0.004), (1150, 0.25, 0.012)],
                                 click=1.4, click_ms=0.6))
    # Cushion: a dull thump of rubber under cloth, the rail's wood under it.
    for i in range(3):
        save("cushion%d" % i, modes(0.22, [(160, 1.0, 0.035), (310, 0.5, 0.02), (720, 0.2, 0.01)],
                                    click=0.6, click_ms=4, noise_lp=1800))
    # Pocket: the ball dropping into a leather pocket, a thud and a little roll.
    for i in range(3):
        y = modes(0.5, [(120, 1.0, 0.05), (240, 0.4, 0.03), (2100, 0.15, 0.006)], click=0.5, click_ms=6, noise_lp=1200)
        t = np.arange(len(y)) / SR
        roll = rng.standard_normal(len(y))
        roll = np.convolve(roll, np.ones(30) / 30, mode="same") * np.exp(-((t - 0.16) / 0.12) ** 2) * 0.5
        save("pocket%d" % i, y + roll)
    # The cue's tip: leather on phenolic, softer and lower than a ball hit.
    for i in range(3):
        save("cue%d" % i, modes(0.12, [(1650, 0.5, 0.006), (2600, 0.4, 0.004), (900, 0.4, 0.009)], click=1.0, click_ms=1.2, noise_lp=5000))
    for kind, n in (("ball", 4), ("cushion", 3), ("pocket", 3), ("cue", 3)):
        sounds[kind] = {"subtitle": "subtitles.pool." + kind, "sounds": [{"name": "pool:%s%d" % (kind, i)} for i in range(n)]}
    write(os.path.join(OUT, "sounds.json"), json.dumps(sounds, indent=2))
    write(os.path.join(OUT, "lang", "en_us.json"), json.dumps({
        "subtitles.pool.ball": "Pool balls clack", "subtitles.pool.cushion": "Pool ball hits cushion",
        "subtitles.pool.pocket": "Pool ball drops", "subtitles.pool.cue": "Cue strikes"}, indent=2))


# ---- HUD font ------------------------------------------------------------------------------

SPIN = 11          # 11 x 11 tip positions, -0.5 .. 0.5 ball radii
CELL = 48          # drawn at 48 px, shown at 20


def make_font():
    sheet = Image.new("RGBA", (SPIN * CELL, SPIN * CELL), (0, 0, 0, 0))
    d = ImageDraw.Draw(sheet)
    for row in range(SPIN):
        for col in range(SPIN):
            x0, y0 = col * CELL, row * CELL
            c = CELL / 2
            r = CELL / 2 - 3
            d.ellipse([x0 + c - r - 2, y0 + c - r - 2, x0 + c + r + 2, y0 + c + r + 2], fill=(20, 20, 20, 200))
            d.ellipse([x0 + c - r, y0 + c - r, x0 + c + r, y0 + c + r], fill=(246, 243, 233, 255))
            # Faint cross-hairs, and the half-radius ring the tip can reach.
            d.line([x0 + c, y0 + c - r + 4, x0 + c, y0 + c + r - 4], fill=(200, 196, 186, 255), width=1)
            d.line([x0 + c - r + 4, y0 + c, x0 + c + r - 4, y0 + c], fill=(200, 196, 186, 255), width=1)
            hr = r * 0.5
            d.ellipse([x0 + c - hr, y0 + c - hr, x0 + c + hr, y0 + c + hr], outline=(214, 210, 200, 255), width=1)
            a = (col - (SPIN - 1) / 2) / (SPIN - 1)          # -0.5 .. 0.5, right
            b = ((SPIN - 1) / 2 - row) / (SPIN - 1)          # -0.5 .. 0.5, up
            px, py = x0 + c + a * 2 * r, y0 + c - b * 2 * r
            dr = 5
            d.ellipse([px - dr, py - dr, px + dr, py + dr], fill=(208, 30, 36, 255))
    os.makedirs(os.path.join(OUT, "textures", "font"), exist_ok=True)
    sheet.save(os.path.join(OUT, "textures", "font", "spin.png"))

    # The power bar: 31 states, empty to full, green through amber to red.
    W, H, N = 400, 36, 31
    bar = Image.new("RGBA", (W, H * N), (0, 0, 0, 0))
    d = ImageDraw.Draw(bar)
    for k in range(N):
        y0 = k * H
        d.rounded_rectangle([0, y0 + 6, W - 1, y0 + H - 7], radius=10, fill=(18, 18, 20, 210), outline=(230, 230, 230, 255), width=3)
        fill = k / (N - 1)
        x1 = 6 + (W - 13) * fill
        for x in range(6, int(x1)):
            t = (x - 6) / (W - 13)
            col = (int(70 + 185 * min(1, t * 1.6)), int(200 - 150 * max(0, t - 0.45) / 0.55), 60, 255)
            d.line([x, y0 + 11, x, y0 + H - 12], fill=col)
    bar.save(os.path.join(OUT, "textures", "font", "power.png"))

    providers = [
        {"type": "bitmap", "file": "pool:font/spin.png", "height": 20, "ascent": 15,
         "chars": ["".join(chr(0xE100 + row * SPIN + col) for col in range(SPIN)) for row in range(SPIN)]},
        {"type": "bitmap", "file": "pool:font/power.png", "height": 12, "ascent": 10,
         "chars": [chr(0xE200 + k) for k in range(N)]},
    ]
    write(os.path.join(OUT, "font", "hud.json"), json.dumps({"providers": providers}, indent=2, ensure_ascii=True))


# ---- the cue item --------------------------------------------------------------------------

def make_item():
    icon = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(icon)
    # A cue lying corner to corner: butt bottom left, tip top right.
    pts = [(3, 29), (28, 4)]
    segs = [(0.0, 0.08, (20, 20, 20)), (0.08, 0.30, (32, 32, 34)), (0.30, 0.50, (60, 30, 20)), (0.50, 0.52, (200, 200, 205)),
            (0.52, 0.94, (228, 200, 150)), (0.94, 0.97, (240, 238, 230)), (0.97, 1.0, (60, 100, 175))]
    for a, b, col in segs:
        x0 = pts[0][0] + (pts[1][0] - pts[0][0]) * a
        y0 = pts[0][1] + (pts[1][1] - pts[0][1]) * a
        x1 = pts[0][0] + (pts[1][0] - pts[0][0]) * b
        y1 = pts[0][1] + (pts[1][1] - pts[0][1]) * b
        w = 3 if a < 0.5 else 2
        d.line([x0, y0, x1, y1], fill=col + (255,), width=w)
    os.makedirs(os.path.join(OUT, "textures", "item"), exist_ok=True)
    icon.save(os.path.join(OUT, "textures", "item", "cue.png"))
    write(os.path.join(OUT, "models", "item", "cue.json"), json.dumps(
        {"parent": "minecraft:item/generated", "textures": {"layer0": "pool:item/cue"}}, indent=2))
    write(os.path.join(OUT, "items", "cue.json"), json.dumps({
        "model": {"type": "minecraft:select", "property": "minecraft:display_context",
                  "cases": [{"when": ["gui", "ground", "fixed", "on_shelf"], "model": {"type": "minecraft:model", "model": "pool:item/cue"}}],
                  "fallback": {"type": "minecraft:empty"}},
        "hand_animation_on_swap": False}, indent=2))


if __name__ == "__main__":
    make_sounds()
    make_font()
    make_item()
