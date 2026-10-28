#!/usr/bin/env python3
"""Verify Maya's atlas, manifest, original frames and Android asset copies.

Requires Pillow. Run from any directory; paths resolve relative to this script.
"""
import hashlib
import json
import struct
from pathlib import Path
from PIL import Image

ART = Path(__file__).resolve().parent
ROOT = ART.parents[1]
EXPECTED = [
    ("idle", 4, 4, True), ("walk", 6, 8, True), ("sit", 2, 2, True),
    ("nap", 2, 1, True), ("wave", 6, 8, False), ("peek", 4, 6, False),
    ("happy", 4, 8, False), ("sulk", 2, 2, False), ("surprised", 2, 6, False),
    ("talk", 3, 8, True), ("listen", 4, 4, True), ("think", 4, 4, True),
    ("jump", 4, 8, False), ("fall", 2, 6, True), ("perch", 2, 2, True),
    ("poke", 4, 6, True), ("tease", 4, 6, True), ("look", 4, 4, True),
    ("ask", 3, 4, True), ("type", 4, 8, True), ("held", 2, 4, True),
]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    png_bytes = (ART / "maya_sprites.png").read_bytes()
    require(png_bytes[:8] == b"\x89PNG\r\n\x1a\n", "Not a PNG")
    require(png_bytes[24] == 8 and png_bytes[25] == 6, "Expected 8-bit RGBA PNG")
    pos = 8
    chunks = []
    while pos < len(png_bytes):
        length = struct.unpack(">I", png_bytes[pos:pos + 4])[0]
        chunks.append(png_bytes[pos + 4:pos + 8])
        pos += length + 12
    require(b"iCCP" not in chunks, "ICC profile found")

    image = Image.open(ART / "maya_sprites.png")
    require(image.mode == "RGBA" and image.size == (512, 1344), "Incorrect atlas format")
    manifest = json.loads((ART / "sprites.json").read_text())
    report = json.loads((ART / "validation.json").read_text())
    require((manifest["width"], manifest["height"], manifest["rows"], manifest["frameCount"])
            == (512, 1344, 21, 72), "Incorrect manifest dimensions/counts")
    require(manifest["anchor"] == {"x": 32, "y": 60}, "Incorrect global anchor")
    require(list(manifest["animations"]) == [x[0] for x in EXPECTED], "Animation order changed")

    colors = image.getcolors(maxcolors=18)
    require(colors is not None, "More than 16 colors plus transparency")
    palette = {tuple(bytes.fromhex(h[1:])) for h in manifest["palette"]}
    require(len(palette) == 16, "Palette must contain 16 distinct colors")
    for _, color in colors:
        require(color[3] in (0, 255), "Non-binary alpha")
        if color[3]:
            require(color[:3] in palette, "Pixel outside required palette")

    actual_frame_names = set()
    for row, (name, count, fps, loop) in enumerate(EXPECTED):
        anim = manifest["animations"][name]
        require((anim["row"], anim["frames"], anim["fps"], anim["loop"])
                == (row, count, fps, loop), f"Timing or row mismatch: {name}")
        require(anim["onComplete"] == ("repeat" if loop else "hold"), f"Completion: {name}")
        require(abs(anim["totalDurationMs"] - round(count * 1000 / fps, 3)) < .001,
                f"Duration: {name}")
        require(anim["cells"] == [
            {"x": i * 64, "y": row * 64, "w": 64, "h": 64}
            for i in range(count)
        ], f"Crop rectangles: {name}")
        for col in range(8):
            frame = image.crop((col * 64, row * 64, col * 64 + 64, row * 64 + 64))
            require(bool(frame.getchannel("A").getbbox()) == (col < count),
                    f"Occupied/empty cell mismatch: {name} {col}")
            if col < count:
                fn = f"{name}_{col:02}.png"
                actual_frame_names.add(fn)
                saved = Image.open(ART / "frames" / fn)
                require(saved.mode == "RGBA" and saved.size == (64, 64), f"Frame format: {fn}")
                require(saved.tobytes() == frame.tobytes(), f"Atlas/frame mismatch: {fn}")

                if row >= 12:
                    alpha = frame.getchannel("A")
                    bottom = alpha.getbbox()[3] - 1
                    if name in {"fall", "tease", "look", "ask", "type"} or (
                            name == "jump" and col in {0, 3}):
                        require(bottom == 60, f"Ground/seat baseline: {fn}")
                    if name in {"fall", "poke", "look", "ask", "type"} or (
                            name == "jump" and col in {0, 3}):
                        require(alpha.crop((24, 60, 41, 61)).getbbox() is not None,
                                f"Feet must contact y=60 near x=32: {fn}")
                    if name == "poke":
                        require(bottom <= 63, f"Contact sparkle exceeds cell: {fn}")
                    if name == "perch":
                        require(61 <= bottom <= 63, f"Perch boots below edge: {fn}")
                    if name == "tease":
                        require(frame.getpixel((32, 32))[3] == 255, f"Tease must cover center: {fn}")
                    if name == "held":
                        require(52 <= bottom <= 58, f"Held boots not above ground: {fn}")
                        grab = frame.getpixel((32, 6))
                        require(grab[:3] in {(255, 79, 122), (255, 127, 176)} and grab[3] == 255,
                                f"Held grab point not at (32,6): {fn}")

    require(actual_frame_names == {p.name for p in (ART / "frames").glob("*.png")},
            "Missing or unexpected individual frame files")
    original_rgba = image.crop((0, 0, 512, 768)).tobytes()
    require(sha(original_rgba) == report["originalRows"]["rgbaSha256"], "Original rows changed")
    for fn, expected_sha in report["originalRows"]["frameFileSha256"].items():
        require(sha((ART / "frames" / fn).read_bytes()) == expected_sha, f"Original file changed: {fn}")
    require(len(report["originalRows"]["frameFileSha256"]) == 43, "Original frame hash count")
    old_animations = {k: manifest["animations"][k] for k, *_ in EXPECTED[:12]}
    animation_hash = sha(json.dumps(old_animations, sort_keys=True, separators=(",", ":")).encode())
    if "manifestAnimationsSha256" in report["originalRows"]:
        require(animation_hash == report["originalRows"]["manifestAnimationsSha256"],
                "Original animation manifest entries changed")

    asset_dir = ROOT / "android/app/src/main/assets/sprites"
    require((asset_dir / "maya.png").read_bytes() == png_bytes, "Android atlas copy differs")
    require((asset_dir / "sprites.json").read_bytes() == (ART / "sprites.json").read_bytes(),
            "Android manifest copy differs")
    for fn in ("maya_preview.gif", "maya_new_poses_preview.gif"):
        preview = Image.open(ART / fn)
        duration = 0
        for i in range(preview.n_frames):
            preview.seek(i)
            duration += preview.info.get("duration", 0)
        require(duration == 4000, f"GIF cycle duration: {fn}")

    print("PASS: 21 animations, 72 frames, original 12 rows unchanged, exact timing,")
    print("16-color RGBA, binary alpha, cell geometry, anchors, individual frames,")
    print("Android asset copies and both animated previews.")
    print("NOT RUN: Android core Gradle tests; application source was not supplied.")


if __name__ == "__main__":
    main()
