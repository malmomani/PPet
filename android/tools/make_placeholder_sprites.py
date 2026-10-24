#!/usr/bin/env python3
"""Draws Maya's placeholder pixel sprites (a chibi anime girl) with the Python stdlib only.

Usage:  python3 android/tools/make_placeholder_sprites.py

Writes android/app/src/main/assets/sprites/maya.png (RGBA sheet) and sprites.json next to it.
The sheet has one 32x32-frame row per animation, in the same order as ANIMATIONS in
android/core/.../Protocol.kt. Output is deterministic. Real art simply replaces these two files.
"""
import json
import os
import struct
import zlib

FRAME = 32

# name -> (frames, fps, loop); dict order == sheet row order == Protocol.kt ANIMATIONS
ANIMATIONS = {
    "idle": (4, 4, True),
    "walk": (6, 8, True),
    "sit": (2, 2, True),
    "nap": (2, 1, True),
    "wave": (6, 8, False),
    "peek": (4, 6, False),
    "happy": (4, 8, False),
    "sulk": (2, 2, False),
    "surprised": (2, 6, False),
}

# ---- palette (RGBA) ----
CLEAR = (0, 0, 0, 0)
OUTLINE = (44, 26, 58, 255)
HAIR = (84, 52, 128, 255)
HAIR_HI = (126, 88, 176, 255)
SKIN = (255, 226, 204, 255)
SKIN_SH = (238, 190, 166, 255)
EYE_LASH = (44, 26, 58, 255)
EYE_IRIS = (96, 120, 220, 255)
EYE_IRIS_LO = (150, 184, 255, 255)
EYE_PUPIL = (30, 24, 72, 255)
WHITE = (255, 255, 255, 255)
MOUTH = (190, 70, 90, 255)
MOUTH_IN = (120, 36, 56, 255)
BLUSH = (255, 150, 160, 255)
DRESS = (255, 142, 180, 255)
DRESS_SH = (226, 104, 146, 255)
BOW = (255, 84, 128, 255)
BOW_SH = (200, 52, 96, 255)
SHOE = (140, 60, 80, 255)
ZZZ = (110, 150, 255, 255)
TEAR = (150, 205, 255, 255)


class Canvas:
    """A FRAME x FRAME RGBA canvas; every write is clipped to the frame."""

    def __init__(self):
        self.px = [[CLEAR] * FRAME for _ in range(FRAME)]

    def set(self, x, y, c):
        if 0 <= x < FRAME and 0 <= y < FRAME:
            self.px[y][x] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)

    def outline(self):
        """Adds a 1-px dark outline around the silhouette (4-neighbourhood)."""
        add = []
        for y in range(FRAME):
            for x in range(FRAME):
                if self.px[y][x][3]:
                    continue
                for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                    if 0 <= nx < FRAME and 0 <= ny < FRAME and self.px[ny][nx][3]:
                        add.append((x, y))
                        break
        for x, y in add:
            self.px[y][x] = OUTLINE


class Pose:
    def __init__(self, **kw):
        self.dy = 0          # whole-body vertical offset (bob / hop / peek)
        self.hx = 0          # extra head offset (tilt)
        self.hy = 0
        self.eyes = "open"   # open | blink | closed | happy | round | sulk
        self.mouth = "smile"  # smile | open | frown | o | none
        self.blush = True
        self.legs = "stand"  # stand | sit | air
        self.spread = 0      # walking: how far apart the legs are (0..2)
        self.lift = None     # walking: "l" / "r" leg raised
        self.arms = "down"   # down | wave | out | up
        self.wave_x = 0      # wave: horizontal hand swing
        self.wave_y = 0      # wave: extra hand drop while the arm is still rising
        self.face_dx = 0     # shifts the face features (looking right when walking)
        self.tear = False
        self.zs = ()         # sleeping "z" marks as (x, y, size) tuples
        for k, v in kw.items():
            if not hasattr(self, k):
                raise AttributeError(k)
            setattr(self, k, v)


def draw_eye(c, x, y, kind):
    """One eye whose top-left corner is (x, y); eyes are 4 wide and 5 tall."""
    if kind == "blink" or kind == "closed":
        c.rect(x, y + 3, x + 3, y + 3, EYE_LASH)
        c.set(x, y + 2, EYE_LASH)
        c.set(x + 3, y + 2, EYE_LASH)
    elif kind == "happy":  # ^ shape
        c.set(x, y + 3, EYE_LASH)
        c.set(x + 1, y + 2, EYE_LASH)
        c.set(x + 2, y + 2, EYE_LASH)
        c.set(x + 3, y + 3, EYE_LASH)
    elif kind == "round":  # wide open, small pupil
        c.rect(x, y, x + 3, y + 4, WHITE)
        c.rect(x, y, x + 3, y, EYE_LASH)
        c.rect(x + 1, y + 1, x + 2, y + 3, EYE_PUPIL)
    elif kind == "sulk":  # heavy lid, looking down
        c.rect(x, y + 1, x + 3, y + 1, EYE_LASH)
        c.rect(x, y + 2, x + 3, y + 3, EYE_IRIS)
        c.rect(x + 1, y + 2, x + 2, y + 3, EYE_PUPIL)
        c.set(x + 3, y + 1, EYE_LASH)
    else:  # open: lash, iris, pupil, highlights
        c.rect(x, y, x + 3, y, EYE_LASH)
        c.rect(x, y + 1, x + 3, y + 3, EYE_IRIS)
        c.rect(x + 1, y + 2, x + 2, y + 3, EYE_PUPIL)
        c.rect(x, y + 4, x + 3, y + 4, EYE_IRIS_LO)
        c.set(x, y + 1, WHITE)           # big highlight
        c.set(x + 1, y + 1, WHITE)
        c.set(x + 3, y + 3, WHITE)       # small highlight


def draw_girl(c, p):
    dy = p.dy
    hx, hy = p.hx, p.dy + p.hy
    sitting = p.legs == "sit"

    # long back hair, behind everything
    c.rect(6 + hx, 12 + hy, 25 + hx, 25 + hy, HAIR)
    c.rect(7 + hx, 26 + hy, 24 + hx, 26 + hy, HAIR)
    for x in (6, 25):
        c.set(x + hx, 12 + hy, CLEAR)
    c.set(6 + hx, 25 + hy, CLEAR)
    c.set(25 + hx, 25 + hy, CLEAR)

    # legs
    if p.legs == "stand" or p.legs == "air":
        top = 26 + dy
        lx, rx = 13 - p.spread, 17 + p.spread
        for x0, side in ((lx, "l"), (rx, "r")):
            raised = (p.lift == side) or p.legs == "air"
            c.rect(x0, top, x0 + 1, top + (0 if raised else 1), SKIN)
            sy = top + (1 if raised else 2)
            c.rect(x0 - 1, sy, x0 + 1 if side == "l" else x0 + 2, sy + (0 if raised else 1), SHOE)
    else:  # sitting: legs stick out forward, feet apart
        top = 26 + dy
        c.rect(12, top, 14, top, SKIN)
        c.rect(17, top, 19, top, SKIN)
        c.rect(10, top + 1, 13, top + 2, SHOE)
        c.rect(18, top + 1, 21, top + 2, SHOE)

    # dress (trapezoid), hem shade, white collar
    c.rect(12, 20 + dy, 19, 20 + dy, DRESS)
    c.rect(11, 21 + dy, 20, 22 + dy, DRESS)
    c.rect(10, 23 + dy, 21, 25 + dy, DRESS)
    c.rect(10, 25 + dy, 21, 25 + dy, DRESS_SH)
    c.rect(14, 20 + dy, 17, 20 + dy, WHITE)
    c.set(15, 21 + dy, BOW)
    c.set(16, 21 + dy, BOW)

    # arms (the mirrored left arm is x -> 31 - x)
    def arm_down(mirror):
        mx = (lambda x: 31 - x) if mirror else (lambda x: x)
        for x in (21, 22, 23):
            for y in (21 + dy, 22 + dy):
                c.set(mx(x), y, DRESS)
        c.rect(mx(22), 23 + dy, mx(22), 24 + dy, SKIN)
        c.set(mx(22), 25 + dy, SKIN_SH)

    def arm_out(mirror):
        mx = (lambda x: 31 - x) if mirror else (lambda x: x)
        for x in (21, 22, 23):
            c.set(mx(x), 21 + dy, DRESS)
            c.set(mx(x), 22 + dy, DRESS)
        for x in (24, 25, 26):
            c.set(mx(x), 21 + dy, SKIN)
            c.set(mx(x), 22 + dy, SKIN)

    def arm_up(mirror, sway=0, drop=0):
        mx = (lambda x: 31 - x) if mirror else (lambda x: x)
        for x in (21, 22, 23):
            c.set(mx(x), 20 + dy, DRESS)
            c.set(mx(x), 21 + dy, DRESS)
        for x in (24, 25, 26):
            c.set(mx(x), 19 + dy, SKIN)
            c.set(mx(x), 20 + dy, SKIN)
        top = 10 + dy + drop
        c.rect(mx(26), top + 3, mx(26), 19 + dy, SKIN)  # forearm
        hand = sorted((mx(26 + sway), mx(27 + sway)))
        c.rect(hand[0], top, hand[1], top + 2, SKIN)    # hand

    if p.arms == "down":
        arm_down(False)
        arm_down(True)
    elif p.arms == "wave":
        arm_down(True)
        arm_up(False, p.wave_x, p.wave_y)
    elif p.arms == "out":
        arm_out(False)
        arm_out(True)
    elif p.arms == "up":
        arm_up(False)
        arm_up(True)

    # head: hair silhouette, then face skin
    head = [(3, 11, 20), (4, 9, 22), (5, 8, 23)] + [(y, 7, 24) for y in range(6, 18)] + [(18, 8, 23)]
    for y, x0, x1 in head:
        c.rect(x0 + hx, y + hy, x1 + hx, y + hy, HAIR)
    face = [(8, 9, 22), (9, 8, 23)] + [(y, 8, 23) for y in range(10, 18)] + [(18, 8, 23), (19, 10, 21)]
    for y, x0, x1 in face:
        c.rect(x0 + hx, y + hy, x1 + hx, y + hy, SKIN)
    # hair shine
    c.rect(10 + hx, 5 + hy, 13 + hx, 5 + hy, HAIR_HI)
    c.rect(18 + hx, 5 + hy, 20 + hx, 5 + hy, HAIR_HI)
    c.set(9 + hx, 6 + hy, HAIR_HI)
    # bangs (jagged fringe over the forehead) and side locks framing the face
    c.rect(8 + hx, 8 + hy, 23 + hx, 10 + hy, HAIR)
    for x in (8, 9, 10, 12, 13, 15, 16, 18, 19, 21, 22, 23):
        c.set(x + hx, 11 + hy, HAIR)
    for x in (8, 9, 22, 23):
        c.set(x + hx, 12 + hy, HAIR)
    c.rect(8 + hx, 13 + hy, 8 + hx, 16 + hy, HAIR)
    c.rect(23 + hx, 13 + hy, 23 + hx, 16 + hy, HAIR)
    c.set(14 + hx, 11 + hy, SKIN)  # parting
    c.set(17 + hx, 11 + hy, SKIN)

    # bow on the right side of the head
    c.rect(19 + hx, 3 + hy, 20 + hx, 6 + hy, BOW)
    c.rect(23 + hx, 3 + hy, 24 + hx, 6 + hy, BOW)
    c.rect(21 + hx, 4 + hy, 22 + hx, 5 + hy, BOW_SH)
    c.set(19 + hx, 6 + hy, BOW_SH)
    c.set(24 + hx, 6 + hy, BOW_SH)

    # face features
    fx = hx + p.face_dx
    fy = hy
    draw_eye(c, 10 + fx, 12 + fy, p.eyes)
    draw_eye(c, 18 + fx, 12 + fy, p.eyes)
    if p.blush:
        for x in (9, 10, 21, 22):
            c.set(x + fx, 17 + fy, BLUSH)
    mx, my = 15 + fx, 18 + fy
    if p.mouth == "smile":
        c.rect(mx, my, mx + 1, my, MOUTH)
    elif p.mouth == "open":
        c.rect(mx - 1, my - 1, mx + 2, my - 1, MOUTH)
        c.rect(mx, my, mx + 1, my, MOUTH_IN)
    elif p.mouth == "frown":
        c.rect(mx, my - 1, mx + 1, my - 1, MOUTH)
        c.set(mx - 1, my, MOUTH)
        c.set(mx + 2, my, MOUTH)
    elif p.mouth == "o":
        c.rect(mx, my - 1, mx + 1, my, MOUTH_IN)
    if p.tear:
        c.rect(10 + fx, 17 + fy, 10 + fx, 18 + fy, TEAR)

    c.outline()

    # drawn after the outline so they stay light
    for zx, zy, size in p.zs:
        for i in range(size):
            c.set(zx + i, zy, ZZZ)
            c.set(zx + i, zy + size - 1, ZZZ)
            c.set(zx + size - 1 - i, zy + i, ZZZ)


def frames_for(name):
    n = ANIMATIONS[name][0]
    if name == "idle":
        # gentle 1-px bob with a blink on the third frame
        return [
            Pose(dy=0),
            Pose(dy=1),
            Pose(dy=1, eyes="blink"),
            Pose(dy=0),
        ]
    if name == "walk":
        # alternating legs, bob on the passing frames, looking to the right
        steps = [(2, None, 0), (1, "l", 0), (0, None, 1), (2, None, 0), (1, "r", 0), (0, None, 1)]
        return [Pose(spread=s, lift=l, dy=d, face_dx=1, arms="down") for s, l, d in steps]
    if name == "sit":
        return [Pose(legs="sit", dy=2), Pose(legs="sit", dy=2, eyes="blink", hy=1)]
    if name == "nap":
        return [
            Pose(legs="sit", dy=2, hy=1, eyes="closed", mouth="none", zs=((27, 9, 4),)),
            Pose(legs="sit", dy=2, hy=2, eyes="closed", mouth="none",
                 zs=((27, 2, 4), (26, 8, 3))),
        ]
    if name == "wave":
        return [
            Pose(arms="wave", wave_y=3, wave_x=0, mouth="open"),
            Pose(arms="wave", wave_y=0, wave_x=0, mouth="open"),
            Pose(arms="wave", wave_y=0, wave_x=1, mouth="open", dy=0),
            Pose(arms="wave", wave_y=0, wave_x=-1, mouth="open", dy=0),
            Pose(arms="wave", wave_y=0, wave_x=1, mouth="open", dy=0),
            Pose(arms="wave", wave_y=0, wave_x=0, mouth="smile"),
        ]
    if name == "peek":
        # only the top half is visible, rising from the bottom edge of the frame
        return [Pose(dy=d, face_dx=0) for d in (21, 17, 14, 12)]
    if name == "happy":
        return [
            Pose(eyes="happy", mouth="open", arms="out"),
            Pose(eyes="happy", mouth="open", arms="up", dy=-2, legs="air"),
            Pose(eyes="happy", mouth="open", arms="up", dy=-2, legs="air"),
            Pose(eyes="happy", mouth="open", arms="out", dy=0),
        ]
    if name == "sulk":
        return [
            Pose(eyes="sulk", mouth="frown", blush=False, hy=2, hx=-1),
            Pose(eyes="sulk", mouth="frown", blush=False, hy=2, hx=-1, tear=True),
        ]
    if name == "surprised":
        return [
            Pose(eyes="round", mouth="o", arms="out", dy=-2, legs="air", blush=False),
            Pose(eyes="round", mouth="o", arms="out", dy=-1, legs="air", blush=False),
        ]
    raise KeyError(name)


def build_sheet():
    """Returns (width, height, rows) where rows is a list of lists of RGBA tuples."""
    max_frames = max(f for f, _, _ in ANIMATIONS.values())
    width, height = FRAME * max_frames, FRAME * len(ANIMATIONS)
    rows = [[CLEAR] * width for _ in range(height)]
    for row, name in enumerate(ANIMATIONS):
        poses = frames_for(name)
        assert len(poses) == ANIMATIONS[name][0], name
        for col, pose in enumerate(poses):
            canvas = Canvas()
            draw_girl(canvas, pose)
            for y in range(FRAME):
                rows[row * FRAME + y][col * FRAME:(col + 1) * FRAME] = canvas.px[y]
    return width, height, rows


def png_bytes(width, height, rows):
    """Encodes an RGBA PNG (color type 6, 8-bit) using only zlib and struct."""
    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    raw = bytearray()
    for r in rows:
        raw.append(0)  # filter: none
        for px in r:
            raw.extend(px)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def sprites_json():
    return json.dumps(
        {
            "frame_w": FRAME,
            "frame_h": FRAME,
            "animations": {
                name: {"row": i, "frames": f, "fps": fps, "loop": loop}
                for i, (name, (f, fps, loop)) in enumerate(ANIMATIONS.items())
            },
        },
        indent=2,
    ) + "\n"


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "sprites")
    out = os.path.normpath(out)
    os.makedirs(out, exist_ok=True)
    width, height, rows = build_sheet()
    with open(os.path.join(out, "maya.png"), "wb") as f:
        f.write(png_bytes(width, height, rows))
    with open(os.path.join(out, "sprites.json"), "w", newline="\n") as f:
        f.write(sprites_json())
    print(f"wrote {out}/maya.png ({width}x{height}) and sprites.json")


if __name__ == "__main__":
    main()
