import json

import pytest
from pydantic import ValidationError

from maya_brain.protocol import (
    Animate,
    Audio,
    ProtocolError,
    Say,
    UserMessage,
    parse_event,
)


def test_parse_user_message():
    e = parse_event('{"type":"user_message","text":"hi"}')
    assert isinstance(e, UserMessage) and e.text == "hi" and e.id and e.ts.tzinfo


def test_unknown_type_ignored():
    assert parse_event({"type": "teleport"}) is None
    assert parse_event({"no": "type"}) is None


def test_bad_json_and_bad_fields_raise():
    with pytest.raises(ProtocolError):
        parse_event("{not json")
    with pytest.raises(ProtocolError):
        parse_event({"type": "app_closed", "package": "x"})
    with pytest.raises(ProtocolError):
        parse_event({"type": "user_message", "text": ""})


def test_all_event_types_parse():
    for raw in [
        {"type": "hello", "client_version": "1", "screen_w": 1, "screen_h": 2},
        {"type": "user_tap"},
        {"type": "user_drag", "thrown": True},
        {"type": "user_shush"},
        {"type": "app_opened", "package": "p", "label": "L"},
        {"type": "app_closed", "package": "p", "label": "L", "duration_s": 5},
        {"type": "screen", "on": False},
        {"type": "charging", "on": True},
        {"type": "offline_summary", "events": [], "since": "2026-10-24T00:00:00Z"},
    ]:
        assert parse_event(raw) is not None


def test_action_serialization():
    d = json.loads(Say(text="yo").model_dump_json())
    assert d["type"] == "say" and d["text"] == "yo" and d["bubble_ms"] == 6000 and "id" in d
    with pytest.raises(ValidationError):
        Animate(name="moonwalk")


def test_hello_without_voice_mode_defaults_off():
    assert parse_event({"type": "hello", "client_version": "1", "screen_w": 1, "screen_h": 1}).voice_mode == "off"


def test_hello_voice_mode_and_unknown_value():
    base = {"type": "hello", "client_version": "1", "screen_w": 1, "screen_h": 1}
    assert parse_event({**base, "voice_mode": "replies"}).voice_mode == "replies"
    assert parse_event({**base, "voice_mode": "shout"}).voice_mode == "off"
    assert parse_event({**base, "voice_mode": 7}).voice_mode == "off"


def test_user_message_voice_flag():
    assert parse_event({"type": "user_message", "text": "hi"}).voice is False
    assert parse_event({"type": "user_message", "text": "hi", "voice": True}).voice is True


def test_audio_action_serializes():
    d = json.loads(Audio(ref_id="abc", data_b64="UklGRg==").model_dump_json())
    assert d["type"] == "audio" and d["ref_id"] == "abc"
    assert d["mime"] == "audio/wav" and d["data_b64"] == "UklGRg=="
