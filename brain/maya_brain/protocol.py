"""JSON messages exchanged with the phone app over WebSocket."""

import json
import math
import uuid
from datetime import datetime, timezone
from typing import Annotated, Literal

from pydantic import BaseModel, Field, TypeAdapter, ValidationError, field_validator

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


VOICE_MODES: tuple[str, ...] = ("off", "replies", "always")
NOTIFICATION_KINDS: tuple[str, ...] = ("message", "email", "call")
TEASE_TARGETS: tuple[str, ...] = ("send", "like", "play", "search", "share", "comment", "follow")
MOVE_INTENTS: tuple[str, ...] = (
    "stay",
    "wander",
    "come_closer",
    "go_left",
    "go_right",
    "go_up",
    "go_down",
    "sit",
    "nap",
    "peek",
    "sit_on",
    "poke",
)
TEASE_INTENTS: tuple[str, ...] = ("sit_on", "poke")  # the moves that need a target
BODY_DOING: tuple[str, ...] = ("idle", "walk", "sit", "perch", "jump", "fall", "nap", "peek", "tease", "dragged")
BODY_WHERE: tuple[str, ...] = ("floor", "platform", "target")

GLANCE_MAX_LINES = 40
GLANCE_MAX_LINE = 200


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
    voice_mode: Literal["off", "replies", "always"] = "off"

    @field_validator("voice_mode", mode="before")
    @classmethod
    def _known_voice_mode(cls, v: object) -> object:
        return v if isinstance(v, str) and v in VOICE_MODES else "off"


class UserMessage(_Message):
    type: Literal["user_message"] = "user_message"
    text: str = Field(min_length=1, max_length=2000)
    voice: bool = False


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


class NotificationEvent(_Message):
    """Someone contacted the user: who and where only, never the text."""

    type: Literal["notification"] = "notification"
    package: str
    app: str = Field(min_length=1, max_length=100)
    sender: str = Field(min_length=1, max_length=100)
    group: str | None = Field(default=None, max_length=100)
    kind: Literal["message", "email", "call"] = "message"

    @field_validator("kind", mode="before")
    @classmethod
    def _known_kind(cls, v: object) -> object:
        return v if isinstance(v, str) and v in NOTIFICATION_KINDS else "message"


def _known_target(v: object) -> str | None:
    return v if isinstance(v, str) and v in TEASE_TARGETS else None


def _unit(v: object, default: float) -> float:
    """A fraction of the screen: clamped to 0..1 and rounded to 2 decimals; junk falls back to `default`."""
    if isinstance(v, bool) or not isinstance(v, (int, float)) or math.isnan(v):
        return default
    return round(min(1.0, max(0.0, float(v))), 2)


class GlanceEvent(_Message):
    """The visible text of the app the user is in, and which tease targets it shows. Never queued offline."""

    type: Literal["glance"] = "glance"
    package: str
    app: str = Field(min_length=1, max_length=100)
    lines: list[str] = Field(default_factory=list)
    targets: list[str] = Field(default_factory=list)

    @field_validator("lines", mode="before")
    @classmethod
    def _tidy_lines(cls, v: object) -> list[str]:
        if not isinstance(v, list):
            return []
        lines = [line.strip() for line in v if isinstance(line, str)]
        return [line[:GLANCE_MAX_LINE] for line in lines if line][:GLANCE_MAX_LINES]

    @field_validator("targets", mode="before")
    @classmethod
    def _known_targets(cls, v: object) -> list[str]:
        if not isinstance(v, list):
            return []
        return [t for t in dict.fromkeys(x for x in v if isinstance(x, str)) if t in TEASE_TARGETS]


class BodyEvent(_Message):
    """What Maya's body is doing and where (feet, as fractions of the screen). Never queued offline."""

    type: Literal["body"] = "body"
    doing: Literal[*BODY_DOING] = "idle"
    where: Literal[*BODY_WHERE] = "floor"
    target: Literal[*TEASE_TARGETS] | None = None
    x: float = 0.5
    y: float = 1.0

    @field_validator("doing", mode="before")
    @classmethod
    def _known_doing(cls, v: object) -> object:
        return v if isinstance(v, str) and v in BODY_DOING else "idle"

    @field_validator("where", mode="before")
    @classmethod
    def _known_where(cls, v: object) -> object:
        return v if isinstance(v, str) and v in BODY_WHERE else "floor"

    @field_validator("target", mode="before")
    @classmethod
    def _known_target(cls, v: object) -> str | None:
        return _known_target(v)

    @field_validator("x", mode="before")
    @classmethod
    def _unit_x(cls, v: object) -> float:
        return _unit(v, 0.5)

    @field_validator("y", mode="before")
    @classmethod
    def _unit_y(cls, v: object) -> float:
        return _unit(v, 1.0)


class BodyMomentEvent(_Message):
    """Something happened to her body: she fell (her platform vanished) or the user shooed her off a target."""

    type: Literal["body_moment"] = "body_moment"
    kind: Literal["fell", "shooed"]
    target: Literal[*TEASE_TARGETS] | None = None

    @field_validator("target", mode="before")
    @classmethod
    def _known_target(cls, v: object) -> str | None:
        return _known_target(v)


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
    | NotificationEvent
    | GlanceEvent
    | BodyEvent
    | BodyMomentEvent
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
        "notification",
        "glance",
        "body",
        "body_moment",
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


class Move(_Message):
    type: Literal["move"] = "move"
    intent: Literal[*MOVE_INTENTS]
    target: Literal[*TEASE_TARGETS] | None = None


class Typing(_Message):
    type: Literal["typing"] = "typing"
    on: bool


class Audio(_Message):
    type: Literal["audio"] = "audio"
    ref_id: str
    mime: str = "audio/wav"
    data_b64: str


class Error(_Message):
    type: Literal["error"] = "error"
    code: str
    message: str


Action = Annotated[
    Say | Animate | State | Move | Typing | Audio | Error,
    Field(discriminator="type"),
]
