import json

import pytest
from pydantic import ValidationError

from maya_brain.protocol import (
    Animate,
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
