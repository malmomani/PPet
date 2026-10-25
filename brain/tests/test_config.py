from pathlib import Path

from maya_brain.config import load_config


def test_defaults_when_no_file(tmp_path):
    cfg = load_config(tmp_path / "missing.toml")
    assert cfg.server.port == 8765 and cfg.llm.chat_model == "qwen3:8b"
    assert cfg.persona.name == "Maya" and cfg.persona.core_traits == ["curious", "playful"]
    assert cfg.initiative.threshold == 60 and cfg.initiative.quiet_start == "23:30"


def test_defaults_when_path_is_none():
    assert load_config(None).db_path == "maya.db"


def test_partial_override_and_unknown_keys(tmp_path):
    p = tmp_path / "m.toml"
    p.write_text('[server]\ntoken="abc"\nbogus=1\n[llm]\nchat_model="llama3.1:8b"\n')
    cfg = load_config(p)
    assert cfg.server.token == "abc" and cfg.server.port == 8765
    assert cfg.llm.chat_model == "llama3.1:8b" and cfg.llm.embed_model == "nomic-embed-text"


def test_example_config_loads():
    cfg = load_config(Path(__file__).parent.parent / "maya.example.toml")
    assert cfg.persona.name == "Maya"


def test_voice_config_defaults_and_override(tmp_path):
    c0 = load_config(None).voice
    assert c0.enabled is True and c0.voice == "af_heart" and c0.speed == 1.0
    assert c0.model_path == "models/kokoro-v1.0.onnx" and c0.voices_path == "models/voices-v1.0.bin"
    p = tmp_path / "m.toml"
    p.write_text('[voice]\nenabled=false\nvoice="af_bella"\n')
    c = load_config(p).voice
    assert c.enabled is False and c.voice == "af_bella" and c.speed == 1.0
