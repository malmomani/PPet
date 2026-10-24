"""JSON messages exchanged with the phone app over WebSocket."""

import json
import uuid
from datetime import datetime, timezone
from typing import Annotated, Literal

from pydantic import BaseModel, Field, TypeAdapter, ValidationError

MOODS: tuple[str, ...] = (
    "happy",
    "curious",
    "bored",
    "excited",
    "sulky",
    "worried",
    "sleepy",
)
ANIMATIONS: tuple[str, ...] = (
    "idle",
    "walk",
    "sit",
    "nap",
    "wave",
    "peek",
    "happy",
    "sulk",
    "surprised",
)


def _new_id() -> str:
    return uuid.uuid4().hex


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class _Message(BaseModel):
    id: str = Field(default_factory=_new_id)
    ts: datetime = Field(default_factory=_utc_now)


# ---- Events (phone -> brain) ----


class Hello(_Message):
    type: Literal["hello"] = "hello"
    client_version: str
    screen_w: int
    screen_h: int


class UserMessage(_Message):
    type: Literal["user_message"] = "user_message"
    text: str = Field(min_length=1, max_length=2000)


class UserTap(_Message):
    type: Literal["user_tap"] = "user_tap"


class UserDrag(_Message):
    type: Literal["user_drag"] = "user_drag"
    thrown: bool = False


class UserShush(_Message):
    type: Literal["user_shush"] = "user_shush"


class AppOpened(_Message):
    type: Literal["app_opened"] = "app_opened"
    package: str
    label: str


class AppClosed(_Message):
    type: Literal["app_closed"] = "app_closed"
    package: str
    label: str
    duration_s: int = Field(ge=0)


class Screen(_Message):
    type: Literal["screen"] = "screen"
    on: bool


class Charging(_Message):
    type: Literal["charging"] = "charging"
    on: bool


class OfflineSummary(_Message):
    type: Literal["offline_summary"] = "offline_summary"
    events: list[dict]
    since: datetime


Event = Annotated[
    Hello
    | UserMessage
    | UserTap
    | UserDrag
    | UserShush
    | AppOpened
    | AppClosed
    | Screen
    | Charging
    | OfflineSummary,
    Field(discriminator="type"),
]

_EVENT_ADAPTER: TypeAdapter[Event] = TypeAdapter(Event)
_EVENT_TYPES = frozenset(
    {
        "hello",
        "user_message",
        "user_tap",
        "user_drag",
        "user_shush",
        "app_opened",
        "app_closed",
        "screen",
        "charging",
        "offline_summary",
    }
)


class ProtocolError(ValueError):
    """Raised for malformed JSON or invalid fields on a known event type."""


def parse_event(raw: str | dict) -> Event | None:
    """Parse an inbound message. Unknown or missing `type` yields None."""
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ProtocolError(f"invalid JSON: {exc}") from exc
    if not isinstance(raw, dict):
        return None
    event_type = raw.get("type")
    if not isinstance(event_type, str) or event_type not in _EVENT_TYPES:
        return None
    try:
        return _EVENT_ADAPTER.validate_python(raw)
    except ValidationError as exc:
        raise ProtocolError(str(exc)) from exc


# ---- Actions (brain -> phone) ----


class Say(_Message):
    type: Literal["say"] = "say"
    text: str
    bubble_ms: int = 6000


class Animate(_Message):
    type: Literal["animate"] = "animate"
    name: Literal[*ANIMATIONS]


class State(_Message):
    type: Literal["state"] = "state"
    mood: str
    closeness_tier: str


class Typing(_Message):
    type: Literal["typing"] = "typing"
    on: bool


class Error(_Message):
    type: Literal["error"] = "error"
    code: str
    message: str


Action = Annotated[
    Say | Animate | State | Typing | Error,
    Field(discriminator="type"),
]
