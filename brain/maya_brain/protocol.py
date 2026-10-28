"""JSON messages exchanged with the phone app over WebSocket."""

import json
import math
import unicodedata
import uuid
from datetime import datetime, timezone
from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    Field,
    TypeAdapter,
    ValidationError,
    ValidationInfo,
    field_validator,
    model_validator,
)

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
SIGHT_MAX_B64 = 600_000  # a bigger screenshot is dropped
ACT_RESULT_MAX_DETAIL = 200

ACT_KINDS: tuple[str, ...] = (
    "open_app",
    "media",
    "timer",
    "alarm",
    "volume",
    "web_search",
    "scroll",
    "press",
    "type",
)
# The fields each act kind carries (spec §5.1), and which of them it needs.
ACT_FIELDS: dict[str, tuple[str, ...]] = {
    "open_app": ("app",),
    "media": ("key",),
    "timer": ("seconds", "label"),
    "alarm": ("hour", "minute", "label"),
    "volume": ("dir",),
    "web_search": ("query",),
    "scroll": ("dir",),
    "press": ("target",),
    "type": ("text", "into"),
}
_ACT_REQUIRED: dict[str, tuple[str, ...]] = {
    "open_app": ("app",),
    "media": ("key",),
    "timer": ("seconds",),
    "alarm": ("hour", "minute"),
    "volume": ("dir",),
    "web_search": ("query",),
    "scroll": ("dir",),
    "press": ("target",),
    "type": ("text",),
}
_ALL_ACT_FIELDS = frozenset(f for fields in ACT_FIELDS.values() for f in fields)
_ACT_MAX_LEN = {"app": 100, "label": 60, "query": 200, "target": 40, "text": 500, "into": 40}
_DIRS: dict[str, tuple[str, ...]] = {"volume": ("up", "down", "mute"), "scroll": ("up", "down")}
MEDIA_KEYS: tuple[str, ...] = ("play_pause", "next", "previous")
ACT_STATUSES: tuple[str, ...] = ("done", "declined", "timeout", "failed", "blocked")

# A press needs the user's yes when its tag is one of these, or its target/label holds a risky word (spec §5.2).
RISKY_TAGS: tuple[str, ...] = ("send", "share", "comment", "follow")


def _utf16_len(s: str) -> int:
    return len(s.encode("utf-16-le")) // 2


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


class SightEvent(_Message):
    """A screenshot of the user's phone (JPEG, base64). RAM only: never stored or logged. Never queued offline."""

    type: Literal["sight"] = "sight"
    package: str
    app: str = Field(min_length=1, max_length=100)
    reason: Literal["auto", "asked"]
    image_b64: str = Field(max_length=SIGHT_MAX_B64)


class SightFailedEvent(_Message):
    """A look the brain asked for could not happen. Never queued offline."""

    type: Literal["sight_failed"] = "sight_failed"
    reason: Literal["blocked", "error"] = "error"

    @field_validator("reason", mode="before")
    @classmethod
    def _known_reason(cls, v: object) -> object:
        return v if v in ("blocked", "error") else "error"


class ActResultEvent(_Message):
    """How an `act` ended on the phone. Never queued offline."""

    type: Literal["act_result"] = "act_result"
    act_id: str
    status: Literal[*ACT_STATUSES]
    detail: str | None = None

    @field_validator("detail", mode="before")
    @classmethod
    def _tidy_detail(cls, v: object) -> str | None:
        if not isinstance(v, str) or not v.strip():
            return None
        return v.strip()[:ACT_RESULT_MAX_DETAIL]


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
    | SightEvent
    | SightFailedEvent
    | ActResultEvent
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
        "sight",
        "sight_failed",
        "act_result",
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
    image = raw.get("image_b64") if event_type == "sight" else None
    if isinstance(image, str) and len(image) > SIGHT_MAX_B64:
        raise ProtocolError("sight image too large")  # a fixed message: never echo the picture
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


class Look(_Message):
    """Ask the phone for a screenshot now; it answers with a `sight` (reason "asked") or `sight_failed`."""

    type: Literal["look"] = "look"


class ActChoice(BaseModel):
    """Something to do on the phone: a kind from ACT_KINDS and that kind's fields (spec §5.1).

    Fields of other kinds are dropped; a missing or out-of-bounds field is a validation error.
    """

    kind: Literal[*ACT_KINDS]
    app: str | None = None
    key: Literal[*MEDIA_KEYS] | None = None
    seconds: int | None = Field(default=None, ge=5, le=86_400)
    label: str | None = None
    hour: int | None = Field(default=None, ge=0, le=23)
    minute: int | None = Field(default=None, ge=0, le=59)
    dir: str | None = None
    query: str | None = None
    target: str | None = None
    text: str | None = None
    into: str | None = None

    @model_validator(mode="before")
    @classmethod
    def _only_its_fields(cls, data: object) -> object:
        if not isinstance(data, dict):
            return data
        kind = data.get("kind")
        keep = ACT_FIELDS.get(kind, ()) if isinstance(kind, str) else ()
        return {k: v for k, v in data.items() if k not in _ALL_ACT_FIELDS or k in keep}

    @field_validator("seconds", "hour", "minute", mode="before")
    @classmethod
    def _no_bools(cls, v: object) -> object:
        if isinstance(v, bool):
            raise ValueError("a number, not a boolean")
        return v

    @field_validator("app", "query", "target", "text", mode="after")
    @classmethod
    def _not_blank(cls, v: str | None) -> str | None:
        if v is not None and not v.strip():
            raise ValueError("must not be blank")
        return v

    @field_validator("app", "label", "query", "target", "text", "into", mode="after")
    @classmethod
    def _fits(cls, v: str | None, info: ValidationInfo) -> str | None:
        """Lengths in UTF-16 units, as the phone (Kotlin) counts them."""
        if v is not None and _utf16_len(v) > _ACT_MAX_LEN[info.field_name]:
            raise ValueError(f"longer than {_ACT_MAX_LEN[info.field_name]} UTF-16 units")
        return v

    @model_validator(mode="after")
    def _complete(self) -> "ActChoice":
        missing = [f for f in _ACT_REQUIRED[self.kind] if getattr(self, f) is None]
        if missing:
            raise ValueError(f"{self.kind} needs {', '.join(missing)}")
        if self.kind in _DIRS and self.dir not in _DIRS[self.kind]:
            raise ValueError(f"{self.kind} dir must be one of {', '.join(_DIRS[self.kind])}")
        return self

    def fields(self) -> dict:
        """The kind and its set fields, e.g. {"kind": "timer", "seconds": 600}."""
        return {"kind": self.kind} | {f: getattr(self, f) for f in ACT_FIELDS[self.kind] if getattr(self, f) is not None}


class Act(_Message, ActChoice):
    """Do something on the phone (brain -> phone); answered by an `act_result` whose act_id is this id."""

    type: Literal["act"] = "act"


def parse_act(raw: dict) -> Act:
    """Validate an act from a dict; anything invalid raises ProtocolError."""
    if not isinstance(raw, dict):
        raise ProtocolError("an act must be an object")
    try:
        return Act.model_validate(raw)
    except ValidationError as exc:
        raise ProtocolError(str(exc)) from exc


_TATWEEL = "\u0640"  # Arabic letter stretching: decoration, not a letter


def words(s: str | None) -> list[str]:
    """Lower-case whole words (letters and digits, any script) without combining marks or tatweel (like core `words`)."""
    if not s:
        return []
    out: list[str] = []
    word: list[str] = []
    for c in s.lower():
        cat = unicodedata.category(c)
        if cat.startswith("M") or c == _TATWEEL:
            continue
        if cat[0] in "LN":
            word.append(c)
        elif word:
            out.append("".join(word))
            word = []
    if word:
        out.append("".join(word))
    return out


RISKY_WORDS: tuple[str, ...] = tuple(
    words(w)[0]
    for w in (
        "send", "post", "publish", "buy", "pay", "order", "checkout", "purchase", "delete", "remove",
        "call", "dial", "share", "subscribe", "follow", "confirm", "submit", "install", "uninstall", "reply",
        "إرسال", "ارسال", "نشر", "شراء", "دفع", "حذف", "اتصال", "مشاركة", "متابعة", "اشتراك", "تأكيد",
    )
)  # fmt: skip
_RISKY_SET = frozenset(RISKY_WORDS)
_ARTICLE = "ال"
_ARABIC_PREFIXES = ("", "ب", "ل")  # bi-, li- before the article: "بالإرسال"; li + al is written "لل"


def _without_article(w: str) -> list[str]:
    """The forms of `w` without an Arabic article, with and without a leading و ("and"), bi- or li- (as core)."""
    out = []
    for base in (w, w[1:]) if w.startswith("و") else (w,):
        if base.startswith("لل"):
            out.append(base[2:])
        out += [base[len(p + _ARTICLE):] for p in _ARABIC_PREFIXES if base.startswith(p + _ARTICLE)]
    return [f for f in out if f]


def _has_risky_word(s: str | None) -> bool:
    """A risky whole word in `s`; an Arabic word also counts without its article and prefix ("والإرسال", "للدفع")."""
    return any(w in _RISKY_SET or any(f in _RISKY_SET for f in _without_article(w)) for w in words(s))


def is_risky(act: ActChoice) -> bool:
    """Does `act` need the user's yes? The target-only subset of core `isRisky` (the brain can't see the nodes):
    any `type`, and a `press` whose target is a risky tag or holds a risky word."""
    if act.kind == "type":
        return True
    if act.kind == "press":
        target = act.target or ""
        return target.strip().lower() in RISKY_TAGS or _has_risky_word(target)
    return False


Action = Annotated[
    Say | Animate | State | Move | Typing | Audio | Error | Look | Act,
    Field(discriminator="type"),
]
