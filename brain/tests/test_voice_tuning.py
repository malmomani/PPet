import io
import sys
import types
import wave

import numpy as np
import pytest

from maya_brain.config import VoiceConfig, load_config
from maya_brain.tts import KokoroTTS, parse_voice, pitch_shift
from maya_brain.voice_lab import candidate_name, parse_args, render_lab


def _frames(wav_bytes):
    return wave.open(io.BytesIO(wav_bytes)).getnframes()


def _install_stub(monkeypatch, created, styles=None):
    styles = styles or {}

    class Kokoro:
        def __init__(self, model, voices):
            created.append(("init",))

        def get_voice_style(self, name):
            created.append(("style", name))
            return styles[name]

        def create(self, text, voice, speed, lang):
            created.append(("create", text, voice, speed, lang))
            return np.zeros(1200), 24000

    stub = types.ModuleType("kokoro_onnx")
    stub.Kokoro = Kokoro
    monkeypatch.setitem(sys.modules, "kokoro_onnx", stub)


def _touch_models(base, cfg):
    for rel in (cfg.model_path, cfg.voices_path):
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"x")


def test_pitch_defaults_and_config(tmp_path):
    assert VoiceConfig().pitch == 0.0
    p = tmp_path / "m.toml"
    p.write_text('[voice]\nvoice="af_sky:0.6,af_nicole:0.4"\npitch=2.5\n')
    c = load_config(p).voice
    assert c.pitch == 2.5 and c.voice == "af_sky:0.6,af_nicole:0.4"


def test_parse_voice():
    assert parse_voice("af_sky") == [("af_sky", 1.0)]
    assert parse_voice(" af_sky:0.6 , af_nicole:0.4 ") == [("af_sky", 0.6), ("af_nicole", 0.4)]
    assert parse_voice("af_sky,af_bella") == [("af_sky", 1.0), ("af_bella", 1.0)]
    for bad in ("", "af_sky:abc", "af_sky:-1", ":0.5", "af_sky:0"):
        with pytest.raises(ValueError):
            parse_voice(bad)


def test_pitch_shift():
    ramp = np.linspace(-1, 1, 1200)
    assert np.array_equal(pitch_shift(ramp, 0.0), ramp)
    up = pitch_shift(ramp, 12.0)  # one octave up -> half as many samples
    assert len(up) == 600
    assert up[0] == pytest.approx(-1.0) and up[-1] == pytest.approx(1.0, abs=0.01)
    assert len(pitch_shift(ramp, -12.0)) == 2400
    assert len(pitch_shift(np.zeros(0), 3.0)) == 0


async def test_kokoro_pitch_keeps_duration(monkeypatch, tmp_path):
    created = []
    _install_stub(monkeypatch, created)
    cfg = VoiceConfig(voice="af_sky", speed=1.1, pitch=12.0)
    _touch_models(tmp_path, cfg)
    out = await KokoroTTS(cfg, tmp_path).synth("hi")
    create = [c for c in created if c[0] == "create"][0]
    assert create[2] == "af_sky"
    assert create[3] == pytest.approx(0.55)  # rendered slower by the pitch factor...
    assert _frames(out) == 600  # ...then raised an octave, back to the requested pace


async def test_kokoro_blends_voices(monkeypatch, tmp_path):
    created = []
    styles = {"af_sky": np.full((2, 3), 1.0), "af_nicole": np.full((2, 3), 3.0)}
    _install_stub(monkeypatch, created, styles)
    cfg = VoiceConfig(voice="af_sky:3,af_nicole:1")
    _touch_models(tmp_path, cfg)
    await KokoroTTS(cfg, tmp_path).synth("hi")
    voice = [c for c in created if c[0] == "create"][0][2]
    assert isinstance(voice, np.ndarray) and np.allclose(voice, 1.5)  # (3*1 + 1*3) / 4


async def test_kokoro_bad_voice_spec_is_tts_error(monkeypatch, tmp_path):
    from maya_brain.tts import TTSError

    _install_stub(monkeypatch, [])
    cfg = VoiceConfig(voice="af_sky:nope")
    _touch_models(tmp_path, cfg)
    with pytest.raises(TTSError):
        await KokoroTTS(cfg, tmp_path).synth("hi")


class FakeRenderer:
    def __init__(self, fail=()):
        self.calls = []
        self.fail = set(fail)

    async def render(self, text, voice, speed, pitch):
        self.calls.append((text, voice, speed, pitch))
        if voice in self.fail:
            from maya_brain.tts import TTSError

            raise TTSError("unknown voice")
        return b"RIFF" + voice.encode()


def test_candidate_name():
    assert candidate_name("af_sky", 0.0) == "af_sky_p+0"
    assert candidate_name("af_sky:0.6,af_nicole:0.4", 2.5) == "af_sky-0.6+af_nicole-0.4_p+2.5"
    assert candidate_name("bf_lily", -1.0) == "bf_lily_p-1"


async def test_render_lab_writes_every_candidate(tmp_path):
    r = FakeRenderer(fail={"af_broken"})
    lines = []
    written = await render_lab(r, "Hi!", ["af_sky", "af_broken"], [0.0, 2.0], 1.1, tmp_path, lines.append)
    assert sorted(p.name for p in written) == ["af_sky_p+0.wav", "af_sky_p+2.wav"]
    assert (tmp_path / "af_sky_p+2.wav").read_bytes() == b"RIFFaf_sky"
    assert ("Hi!", "af_sky", 1.1, 2.0) in r.calls
    assert any("af_broken" in line and "skipped" in line for line in lines)


def test_parse_args_defaults_and_overrides():
    a = parse_args([])
    assert "af_sky" in a.voices and a.pitches == [0.0, 2.0, 3.5] and a.play is False
    b = parse_args(["--voices", "af_sky", "af_sky:0.6,bf_lily:0.4", "--pitches", "1,3", "--text", "Yo", "--play"])
    assert b.voices == ["af_sky", "af_sky:0.6,bf_lily:0.4"] and b.pitches == [1.0, 3.0] and b.text == "Yo" and b.play
