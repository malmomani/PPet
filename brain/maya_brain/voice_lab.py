"""Audition Maya's voice: render one line in several voices and pitches.

Run from brain/: python -m maya_brain.voice_lab [--play] [--voices a b "a:0.6,b:0.4"] [--pitches 0,2] [--text "..."]
Files land in voice-lab/ named like af_sky_p+2.wav; copy the winner's settings into maya.toml.
"""

import argparse
import asyncio
import sys
from collections.abc import Callable, Sequence
from pathlib import Path

from .config import load_config
from .sim import _play_wav
from .tts import TTSError, load_kokoro

DEFAULT_TEXT = "Hi! I'm Maya. Ooh, what are you doing? Can I watch too?"
DEFAULT_VOICES = ["af_heart", "af_sky", "af_nicole", "af_bella", "af_jessica", "af_kore", "bf_lily", "bf_isabella"]
DEFAULT_PITCHES = [0.0, 2.0, 3.5]


def candidate_name(voice: str, pitch: float) -> str:
    """A filename-safe label: 'af_sky:0.6,af_nicole:0.4' at +2.5 -> 'af_sky-0.6+af_nicole-0.4_p+2.5'."""
    v = voice.replace(" ", "").replace(":", "-").replace(",", "+")
    return f"{v}_p{pitch:+g}"


async def render_lab(
    renderer,
    text: str,
    voices: Sequence[str],
    pitches: Sequence[float],
    speed: float,
    out_dir: Path,
    log: Callable[[str], None] = print,
) -> list[Path]:
    """Render every voice x pitch into out_dir; a voice that fails is reported and skipped."""
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for voice in voices:
        for pitch in pitches:
            try:
                wav = await renderer.render(text, voice, speed, pitch)
            except TTSError as exc:
                log(f"  {voice}: skipped ({exc})")
                break
            path = out_dir / f"{candidate_name(voice, pitch)}.wav"
            path.write_bytes(wav)
            written.append(path)
            log(f"  {path.name}")
    return written


def _csv(value: str) -> list[str]:
    return [v.strip() for v in value.split(",") if v.strip()]


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="maya_brain.voice_lab", description="Audition voices for Maya")
    p.add_argument("--config", default="maya.toml", help="path to config TOML (default: maya.toml)")
    p.add_argument("--text", default=DEFAULT_TEXT, help="line to speak")
    p.add_argument("--voices", nargs="+", default=DEFAULT_VOICES,
                   help='space-separated voices; a blend is one quoted item, e.g. "af_sky:0.6,af_nicole:0.4"')
    p.add_argument("--pitches", type=lambda s: [float(x) for x in _csv(s)], default=DEFAULT_PITCHES,
                   help="comma-separated semitone shifts (default: 0,2,3.5)")
    p.add_argument("--out", default="voice-lab", help="output folder (default: voice-lab)")
    p.add_argument("--play", action="store_true", help="play each sample after rendering (Windows)")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    cfg_path = Path(args.config)
    cfg = load_config(cfg_path)
    tts = load_kokoro(cfg.voice, base_dir=cfg_path.resolve().parent)
    if tts is None:
        print("Voice isn't available; see the warning above.", file=sys.stderr)
        return 1
    print(f"Rendering into {args.out}/ (speed {cfg.voice.speed}) ...")
    written = asyncio.run(render_lab(tts, args.text, args.voices, args.pitches, cfg.voice.speed, Path(args.out)))
    if args.play:
        for path in written:
            print(f"> {path.stem}")
            _play_wav(path)
    print('\nPick one and put it in maya.toml under [voice], e.g. for af_sky_p+2:  voice = "af_sky"  pitch = 2.0')
    print('Blends work too:  voice = "af_sky:0.6,af_nicole:0.4"')
    return 0


if __name__ == "__main__":
    sys.exit(main())
