import asyncio
import builtins
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


def test_kokoro_unavailable_when_import_is_broken(monkeypatch, tmp_path):
    cfg = VoiceConfig()
    _touch_models(tmp_path, cfg)
    monkeypatch.delitem(sys.modules, "kokoro_onnx", raising=False)
    real_import = builtins.__import__

    def broken(name, *a, **kw):
        if name == "kokoro_onnx":
            raise RuntimeError("onnxruntime is broken")
        return real_import(name, *a, **kw)

    monkeypatch.setattr(builtins, "__import__", broken)
    assert KokoroTTS(cfg, tmp_path).available() is False


async def test_kokoro_concurrent_first_synth_loads_once(monkeypatch, tmp_path):
    created = []
    _install_stub(monkeypatch, created)
    cfg = VoiceConfig()
    _touch_models(tmp_path, cfg)
    tts = KokoroTTS(cfg, tmp_path)
    await asyncio.gather(*(tts.synth(f"hi {i}") for i in range(5)))
    assert [c[0] for c in created].count("init") == 1


def _frames(wav: bytes) -> bytes:
    w = wave.open(io.BytesIO(wav))
    return w.readframes(w.getnframes())


def test_pcm_wav_numpy_and_pure_python_paths_agree(monkeypatch):
    import numpy as np

    import maya_brain.tts as tts_mod

    samples = [0.0, 0.5, -0.5, 1.0, -1.0, 2.0, -2.0, 0.123456, -0.999]
    assert tts_mod._np is not None
    with_numpy = pcm_wav(samples, 24000)
    from_array = pcm_wav(np.array(samples, dtype=np.float64), 24000)  # what Kokoro hands over
    monkeypatch.setattr(tts_mod, "_np", None)
    pure = pcm_wav(samples, 24000)
    assert with_numpy == pure == from_array
    values = [int.from_bytes(f, "little", signed=True) for f in (_frames(pure)[i:i + 2] for i in range(0, 18, 2))]
    assert values == [0, 16383, -16383, 32767, -32767, 32767, -32767, 4045, -32734]


def test_pcm_wav_pure_python_path_header(monkeypatch):
    import maya_brain.tts as tts_mod

    monkeypatch.setattr(tts_mod, "_np", None)
    w = wave.open(io.BytesIO(pcm_wav([0.0, 0.5, -2.0], 24000)))
    assert (w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()) == (1, 2, 24000, 3)


def test_python_version_hint():
    from maya_brain.tts import python_version_hint

    assert python_version_hint((3, 13, 1)) == ""
    assert python_version_hint((3, 11, 0)) == ""
    hint = python_version_hint((3, 14, 0))
    assert "3.11-3.13" in hint and "Python 3.14" in hint and "py -3.13 -m venv .venv" in hint


def test_load_kokoro_warning_names_the_import_error(monkeypatch, tmp_path, capsys):
    from maya_brain.tts import load_kokoro

    cfg = VoiceConfig()
    _touch_models(tmp_path, cfg)
    monkeypatch.delitem(sys.modules, "kokoro_onnx", raising=False)
    real_import = builtins.__import__

    def broken(name, *a, **kw):
        if name == "kokoro_onnx":
            raise ImportError("DLL load failed while importing onnxruntime_pybind11_state")
        return real_import(name, *a, **kw)

    monkeypatch.setattr(builtins, "__import__", broken)
    assert load_kokoro(cfg, tmp_path) is None
    err = capsys.readouterr().err
    assert "can't import kokoro_onnx: ImportError: DLL load failed" in err
    assert "looked for" not in err


def test_load_kokoro_warning_names_missing_files(monkeypatch, tmp_path, capsys):
    from maya_brain.tts import load_kokoro

    _install_stub(monkeypatch, [])
    assert load_kokoro(VoiceConfig(), tmp_path) is None
    err = capsys.readouterr().err
    assert "looked for" in err and "can't import" not in err


def test_load_kokoro_warning_adds_python_hint_on_314(monkeypatch, tmp_path, capsys):
    from maya_brain.tts import load_kokoro

    monkeypatch.setitem(sys.modules, "kokoro_onnx", None)
    monkeypatch.setattr(sys, "version_info", (3, 14, 0, "final", 0))
    assert load_kokoro(VoiceConfig(), tmp_path) is None
    monkeypatch.undo()
    err = capsys.readouterr().err
    assert "py -3.13 -m venv .venv" in err and "Python 3.14" in err


def test_load_kokoro_warning_has_no_python_hint_on_313(monkeypatch, tmp_path, capsys):
    from maya_brain.tts import load_kokoro

    monkeypatch.setitem(sys.modules, "kokoro_onnx", None)
    monkeypatch.setattr(sys, "version_info", (3, 13, 2, "final", 0))
    assert load_kokoro(VoiceConfig(), tmp_path) is None
    monkeypatch.undo()
    assert "py -3.13" not in capsys.readouterr().err


def test_kokoro_warm_up_loads_once_and_never_raises(monkeypatch, tmp_path, capsys):
    created = []
    _install_stub(monkeypatch, created)
    cfg = VoiceConfig()
    _touch_models(tmp_path, cfg)
    tts = KokoroTTS(cfg, tmp_path)
    tts.warm_up()
    tts.warm_up()
    assert [c[0] for c in created] == ["init"]

    class Boom:
        def __init__(self, *a):
            raise RuntimeError("bad model")

    monkeypatch.setattr(sys.modules["kokoro_onnx"], "Kokoro", Boom)
    KokoroTTS(cfg, tmp_path).warm_up()  # must not raise
    assert "bad model" in capsys.readouterr().err
