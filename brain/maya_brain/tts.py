"""Text-to-speech: Kokoro (optional dependency) plus a fake for tests."""

import asyncio
import io
import re
import threading
import unicodedata
import wave
from collections.abc import Sequence
from pathlib import Path
from typing import Protocol

from maya_brain.config import VoiceConfig

SAMPLE_RATE = 24000
_DROP_CATEGORIES = frozenset({"So", "Sk", "Cs", "Co"})
_DROP_CHARS = frozenset({"️", "︎", "‍"})
_WS_RE = re.compile(r"\s+")


class TTSError(Exception):
    pass


class TTS(Protocol):
    async def synth(self, text: str) -> bytes: ...


def speakable(text: str) -> str:
    """Strip emoji, pictographs and symbols; keep letters, digits and punctuation."""
    kept = "".join(
        ch for ch in text if ch not in _DROP_CHARS and unicodedata.category(ch) not in _DROP_CATEGORIES
    )
    return _WS_RE.sub(" ", kept).strip()


def pcm_wav(samples: Sequence[float], rate: int) -> bytes:
    """Encode float samples (clamped to [-1, 1]) as a mono 16-bit PCM WAV."""
    frames = bytearray()
    for s in samples:
        v = int(round(max(-1.0, min(1.0, float(s))) * 32767))
        frames += v.to_bytes(2, "little", signed=True)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(frames))
    return buf.getvalue()


class KokoroTTS:
    def __init__(self, cfg: VoiceConfig, base_dir: Path):
        self.cfg = cfg
        self.base_dir = Path(base_dir)
        self._kokoro = None
        self._load_lock = threading.Lock()

    @property
    def model_file(self) -> Path:
        return self.base_dir / self.cfg.model_path

    @property
    def voices_file(self) -> Path:
        return self.base_dir / self.cfg.voices_path

    def available(self) -> bool:
        try:
            import kokoro_onnx  # noqa: F401
        except Exception:  # a broken onnxruntime/numpy install must not crash startup
            return False
        return self.model_file.is_file() and self.voices_file.is_file()

    def _load(self):
        with self._load_lock:
            if self._kokoro is None:
                from kokoro_onnx import Kokoro

                self._kokoro = Kokoro(str(self.model_file), str(self.voices_file))
            return self._kokoro

    def _synth_blocking(self, text: str) -> bytes:
        samples, rate = self._load().create(text, voice=self.cfg.voice, speed=self.cfg.speed, lang="en-us")
        return pcm_wav(samples, rate)

    async def synth(self, text: str) -> bytes:
        clean = speakable(text)
        if not clean:
            raise TTSError("nothing to say")
        try:
            return await asyncio.to_thread(self._synth_blocking, clean)
        except TTSError:
            raise
        except Exception as exc:
            raise TTSError(f"kokoro failed: {exc}") from exc


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
