"""Download the Kokoro model files: `python -m maya_brain.fetch_voice [--dir models]`."""

import argparse
import sys
from pathlib import Path

import httpx

BASE_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"
FILES: tuple[str, ...] = ("kokoro-v1.0.onnx", "voices-v1.0.bin")


def missing_files(dest: Path) -> list[tuple[str, str]]:
    """(name, url) for each model file not yet present in `dest`."""
    return [(name, BASE_URL + name) for name in FILES if not (Path(dest) / name).exists()]


def _download(name: str, url: str, dest: Path) -> None:
    target = dest / name
    part = dest / (name + ".part")
    with httpx.stream("GET", url, follow_redirects=True, timeout=60.0) as resp:
        resp.raise_for_status()
        total = int(resp.headers.get("content-length") or 0)
        done = 0
        with open(part, "wb") as f:
            for chunk in resp.iter_bytes(1 << 20):
                f.write(chunk)
                done += len(chunk)
                pct = f" ({done * 100 // total}%)" if total else ""
                print(f"\r{name}: {done / 1e6:.1f} MB{pct}", end="", flush=True)
    part.replace(target)
    print(f"\r{name}: done ({done / 1e6:.1f} MB)      ")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Download Kokoro TTS model files.")
    parser.add_argument("--dir", default="models", help="destination directory (default: models)")
    args = parser.parse_args(argv)
    dest = Path(args.dir)
    dest.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        if (dest / name).exists():
            print(f"{name}: already present, skipping")
    try:
        for name, url in missing_files(dest):
            print(f"{name}: downloading from {url}")
            _download(name, url, dest)
    except httpx.HTTPError as exc:
        print(f"download failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
