import io
import sys
import types
import wave

import pytest

from maya_brain.config import VoiceConfig
from maya_brain.fetch_voice import BASE_URL, FILES, missing_files
from maya_brain.tts import FakeTTS, KokoroTTS, TTSError, pcm_wav, speakable


def test_speakable_strips_emoji():
    assert speakable("hehe 😊") == "hehe"
    assert speakable("👀") == ""
    assert speakable("isn't your exam tomorrow? 👀✨") == "isn't your exam tomorrow?"


def test_speakable_handles_zwj_variation_selectors_and_whitespace():
    assert speakable("ok ❤️  fine") == "ok fine"
    assert speakable("a 👩‍💻 b") == "a b"
    assert speakable("Hello,\n\tworld 42!") == "Hello, world 42!"


def test_pcm_wav_header():
    w = wave.open(io.BytesIO(pcm_wav([0.0, 0.5, -2.0], 24000)))
    assert (w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()) == (1, 2, 24000, 3)


def test_pcm_wav_clamps():
    w = wave.open(io.BytesIO(pcm_wav([2.0, -2.0], 24000)))
    frames = w.readframes(2)
    assert int.from_bytes(frames[:2], "little", signed=True) == 32767
    assert int.from_bytes(frames[2:], "little", signed=True) in (-32767, -32768)


async def test_fake_tts_and_failure():
    t = FakeTTS()
    assert (await t.synth("hi"))[:4] == b"RIFF" and t.calls == ["hi"]
    t.fail_next = 1
    with pytest.raises(TTSError):
        await t.synth("hi")
    assert (await t.synth("again"))[:4] == b"RIFF"


async def test_fake_tts_delay():
    t = FakeTTS()
    t.delay_s = 0.01
    assert (await t.synth("hi"))[:4] == b"RIFF"


def test_kokoro_unavailable_without_files(tmp_path):
    assert KokoroTTS(VoiceConfig(), tmp_path).available() is False


def _install_stub(monkeypatch, created):
    class Kokoro:
        def __init__(self, model, voices):
            created.append(("init", model, voices))

        def create(self, text, voice, speed, lang):
            created.append(("create", text, voice, speed, lang))
            return [0.0] * 10, 24000

    stub = types.ModuleType("kokoro_onnx")
    stub.Kokoro = Kokoro
    monkeypatch.setitem(sys.modules, "kokoro_onnx", stub)


def _touch_models(base, cfg):
    for rel in (cfg.model_path, cfg.voices_path):
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"x")


async def test_kokoro_synth_uses_library(monkeypatch, tmp_path):
    created = []
    _install_stub(monkeypatch, created)
    cfg = VoiceConfig()
    _touch_models(tmp_path, cfg)
    tts = KokoroTTS(cfg, tmp_path)
    assert tts.available() is True
    out = await tts.synth("hehe 😊 hi")
    assert out[:4] == b"RIFF"
    assert created[0] == ("init", str(tmp_path / cfg.model_path), str(tmp_path / cfg.voices_path))
    assert created[1] == ("create", "hehe hi", "af_heart", 1.0, "en-us")
    await tts.synth("again")
    assert [c[0] for c in created].count("init") == 1  # lazy-loaded once


async def test_kokoro_empty_text_and_library_failure(monkeypatch, tmp_path):
    created = []
    _install_stub(monkeypatch, created)
    cfg = VoiceConfig()
    _touch_models(tmp_path, cfg)
    tts = KokoroTTS(cfg, tmp_path)
    with pytest.raises(TTSError, match="nothing to say"):
        await tts.synth("👀")
    assert created == []

    class Boom:
        def __init__(self, *a):
            raise RuntimeError("bad model")

    monkeypatch.setattr(sys.modules["kokoro_onnx"], "Kokoro", Boom)
    with pytest.raises(TTSError):
        await KokoroTTS(cfg, tmp_path).synth("hi")


def test_kokoro_unavailable_when_library_missing(monkeypatch, tmp_path):
    cfg = VoiceConfig()
    _touch_models(tmp_path, cfg)
    monkeypatch.setitem(sys.modules, "kokoro_onnx", None)  # makes import raise ImportError
    assert KokoroTTS(cfg, tmp_path).available() is False


def test_fetch_voice_missing_files(tmp_path):
    todo = missing_files(tmp_path)
    assert [name for name, _ in todo] == ["kokoro-v1.0.onnx", "voices-v1.0.bin"]
    assert all(url == BASE_URL + name for name, url in todo)
    (tmp_path / FILES[0]).write_bytes(b"x")
    assert [name for name, _ in missing_files(tmp_path)] == [FILES[1]]
