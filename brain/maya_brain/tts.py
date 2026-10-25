"""Text-to-speech: Kokoro (optional dependency) plus a fake for tests."""

import asyncio
import io
import re
import sys
import threading
import unicodedata
import wave
from collections.abc import Sequence
from pathlib import Path
from typing import Protocol

from maya_brain.config import VoiceConfig

try:  # a core dependency, but the encoder below must not need it
    import numpy as _np
except ImportError:  # pragma: no cover - exercised by monkeypatching _np in tests
    _np = None

SAMPLE_RATE = 24000
_DROP_CATEGORIES = frozenset({"So", "Sk", "Cs", "Co"})
_DROP_CHARS = frozenset({"️", "︎", "‍"})
_WS_RE = re.compile(r"\s+")


class TTSError(Exception):
    pass


def parse_voice(spec: str) -> list[tuple[str, float]]:
    """'af_sky' or 'af_sky:0.6,af_nicole:0.4' -> [(name, weight)]. Raises ValueError on a bad spec."""
    parts = [p.strip() for p in spec.split(",") if p.strip()]
    if not parts:
        raise ValueError("empty voice")
    out = []
    for part in parts:
        name, _, weight = part.partition(":")
        name = name.strip()
        w = float(weight) if weight.strip() else 1.0  # float() raises ValueError on junk
        if not name or not w > 0:
            raise ValueError(f"bad voice entry {part!r}")
        out.append((name, w))
    return out


def pitch_shift(samples, semitones: float):
    """Raise (or lower) pitch by resampling: n samples become n / 2**(semitones/12).

    Played back at the same rate, the result is higher and shorter by the same factor, so callers
    render the speech that much slower first to keep the original pace.
    """
    if semitones == 0:
        return samples
    a = _np.asarray(samples, dtype=_np.float64)
    if len(a) == 0:
        return a
    factor = 2 ** (semitones / 12)
    n = max(1, int(round(len(a) / factor)))
    return _np.interp(_np.linspace(0, len(a) - 1, n), _np.arange(len(a)), a)


class TTS(Protocol):
    async def synth(self, text: str) -> bytes: ...


def speakable(text: str) -> str:
    """Strip emoji, pictographs and symbols; keep letters, digits and punctuation."""
    kept = "".join(
        ch for ch in text if ch not in _DROP_CHARS and unicodedata.category(ch) not in _DROP_CATEGORIES
    )
    return _WS_RE.sub(" ", kept).strip()


def _pcm16(samples: Sequence[float]) -> bytes:
    """Little-endian 16-bit PCM: each sample clamped to [-1, 1], scaled by 32767, truncated toward 0."""
    if _np is not None:
        a = _np.asarray(samples, dtype=_np.float64)
        return (_np.clip(a, -1, 1) * 32767).astype("<i2").tobytes()
    frames = bytearray()
    for s in samples:
        v = int(max(-1.0, min(1.0, float(s))) * 32767)
        frames += v.to_bytes(2, "little", signed=True)
    return bytes(frames)


def pcm_wav(samples: Sequence[float], rate: int) -> bytes:
    """Encode float samples (clamped to [-1, 1]) as a mono 16-bit PCM WAV."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(_pcm16(samples))
    return buf.getvalue()


class KokoroTTS:
    def __init__(self, cfg: VoiceConfig, base_dir: Path):
        self.cfg = cfg
        self.base_dir = Path(base_dir)
        self._kokoro = None
        self._load_lock = threading.Lock()
        # Why `import kokoro_onnx` failed in the last available() check (e.g. "DLL load failed").
        self.import_error: str | None = None

    @property
    def model_file(self) -> Path:
        return self.base_dir / self.cfg.model_path

    @property
    def voices_file(self) -> Path:
        return self.base_dir / self.cfg.voices_path

    def available(self) -> bool:
        try:
            import kokoro_onnx  # noqa: F401
        except Exception as exc:  # a broken onnxruntime/numpy install must not crash startup
            self.import_error = f"{type(exc).__name__}: {exc}"
            return False
        self.import_error = None
        return self.model_file.is_file() and self.voices_file.is_file()

    def _load(self):
        with self._load_lock:
            if self._kokoro is None:
                from kokoro_onnx import Kokoro

                self._kokoro = Kokoro(str(self.model_file), str(self.voices_file))
            return self._kokoro

    def warm_up(self) -> None:
        """Load the model now (blocking), so the first spoken reply isn't late. Never raises."""
        try:
            self._load()
        except Exception as exc:  # synth() retries the load and reports its own error
            print(f"Warning: couldn't load Kokoro yet ({type(exc).__name__}: {exc}).", file=sys.stderr)

    def _style(self, kokoro, spec: str):
        """A single voice name as-is, or the weighted average of several voices' style vectors."""
        voices = parse_voice(spec)
        if len(voices) == 1:
            return voices[0][0]
        total = sum(w for _, w in voices)
        return sum(_np.asarray(kokoro.get_voice_style(name)) * w for name, w in voices) / total

    def _render_blocking(self, text: str, voice: str, speed: float, pitch: float) -> bytes:
        kokoro = self._load()
        factor = 2 ** (pitch / 12)
        samples, rate = kokoro.create(text, voice=self._style(kokoro, voice), speed=speed / factor, lang="en-us")
        return pcm_wav(pitch_shift(samples, pitch), rate)

    async def render(self, text: str, voice: str, speed: float, pitch: float) -> bytes:
        """Synthesize with explicit voice settings (used by synth and the voice lab)."""
        clean = speakable(text)
        if not clean:
            raise TTSError("nothing to say")
        try:
            return await asyncio.to_thread(self._render_blocking, clean, voice, speed, pitch)
        except TTSError:
            raise
        except Exception as exc:
            raise TTSError(f"kokoro failed: {exc}") from exc

    async def synth(self, text: str) -> bytes:
        return await self.render(text, self.cfg.voice, self.cfg.speed, self.cfg.pitch)


NOT_READY_WARNING = (
    "Warning: voice is enabled but Kokoro isn't ready ({why}), so Maya will stay text-only. "
    'To enable it, run: pip install -e ".[voice]" and then: python -m maya_brain.fetch_voice '
    "(a broken onnxruntime install also shows up as unavailable)."
)
PYTHON_HINT = (
    " kokoro-onnx supports Python 3.11-3.13 only and this is Python {version}: make the venv with "
    "Python 3.13, e.g. `py -3.13 -m venv .venv` on Windows, then reinstall."
)


def python_version_hint(version_info: Sequence[int]) -> str:
    """Extra advice for the not-ready warning when Python is too new for kokoro-onnx."""
    if tuple(version_info[:2]) >= (3, 14):
        return PYTHON_HINT.format(version=f"{version_info[0]}.{version_info[1]}")
    return ""


def load_kokoro(cfg: VoiceConfig, base_dir: Path) -> "KokoroTTS | None":
    """Kokoro if voice is enabled and usable; otherwise None, after one warning on stderr."""
    if not cfg.enabled:
        return None
    tts = KokoroTTS(cfg, base_dir)
    if tts.available():
        return tts
    if tts.import_error is not None:
        why = f"can't import kokoro_onnx: {tts.import_error}"
    else:
        why = f"looked for {tts.model_file} and {tts.voices_file}"
    print(NOT_READY_WARNING.format(why=why) + python_version_hint(sys.version_info), file=sys.stderr)
    return None


class FakeTTS:
    def __init__(self) -> None:
        self.calls: list[str] = []
        self.fail_next = 0
        self.delay_s = 0.0

    async def synth(self, text: str) -> bytes:
        self.calls.append(text)
        if self.delay_s:
            await asyncio.sleep(self.delay_s)
        if self.fail_next > 0:
            self.fail_next -= 1
            raise TTSError("fake failure")
        return pcm_wav([0.0] * 240, SAMPLE_RATE)
