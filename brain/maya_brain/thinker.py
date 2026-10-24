import logging
import math
import sqlite3
from dataclasses import dataclass

from pydantic import BaseModel, ConfigDict, ValidationError, field_validator

from .clock import Clock
from .emotion import TIER_GUIDANCE, Emotion
from .llm import LLM, extract_json
from .memory import Memory
from .persona import Persona
from .protocol import ANIMATIONS, MOODS

log = logging.getLogger(__name__)

DIRECT_TRIGGERS = {"user_message", "user_tap"}
LOG_REPLY_CHARS = 500

MAX_SAY = 300
RETRY_MESSAGE = "That wasn't a valid reply. Respond with ONLY the corrected JSON object."


def _clamp(value: float, low: float, high: float, default: float) -> float:
    try:
        number = float(value)
    except TypeError as e:  # pydantic only wraps ValueError into a validation error
        raise ValueError(f"not a number: {value!r}") from e
    if math.isnan(number):
        return default
    return max(low, min(high, number))


class Thought(BaseModel):
    """Maya's inner thought: what she privately thinks and whether/what she says."""

    model_config = ConfigDict(extra="ignore")

    thought: str = ""
    speak: bool = False
    say: str = ""
    animation: str = "idle"
    mood: str | None = None
    mood_intensity: float = 0.5
    closeness_delta: float = 0.0

    @field_validator("thought", "say", mode="before")
    @classmethod
    def _none_to_empty(cls, v):
        return "" if v is None else v

    @field_validator("say", mode="after")
    @classmethod
    def _tidy_say(cls, v: str) -> str:
        return v.strip()[:MAX_SAY]

    @field_validator("animation", mode="before")
    @classmethod
    def _known_animation(cls, v):
        return v if isinstance(v, str) and v in ANIMATIONS else "idle"

    @field_validator("mood", mode="before")
    @classmethod
    def _known_mood(cls, v):
        return v if isinstance(v, str) and v in MOODS else None

    @field_validator("mood_intensity", mode="before")
    @classmethod
    def _clamp_intensity(cls, v):
        return 0.5 if v is None else _clamp(v, 0.0, 1.0, 0.5)

    @field_validator("closeness_delta", mode="before")
    @classmethod
    def _clamp_delta(cls, v):
        return 0.0 if v is None else _clamp(v, -3.0, 3.0, 0.0)


@dataclass
class Context:
    trigger: str
    detail: str
    current_app: str | None = None
    app_minutes: int | None = None


class Thinker:
    def __init__(
        self, llm: LLM, persona: Persona, memory: Memory, emotion: Emotion, clock: Clock
    ) -> None:
        self.llm = llm
        self.persona = persona
        self.memory = memory
        self.emotion = emotion
        self.clock = clock

    async def build_messages(self, ctx: Context) -> list[dict]:
        mood, intensity = self.emotion.mood()
        tier = self.emotion.tier()
        parts = [
            self.persona.render(),
            "",
            f"Closeness tier: {tier}",
            TIER_GUIDANCE[tier],
            "",
            f"Your current mood: {mood} (intensity {intensity:.2f}).",
        ]
        facts = self.memory.facts()
        if facts:
            parts += ["", "What you know about the user:"]
            parts += [f"- {k}: {v}" for k, v in facts.items()]
        episodes = await self.memory.recall(ctx.detail)
        if episodes:
            parts += ["", "Things you remember that may be relevant:"]
            parts += [f"- ({e.created_at.date().isoformat()}) {e.summary}" for e in episodes]
        parts += [
            "",
            "Rules:",
            "- Reply with ONLY a JSON object with these keys: "
            '"thought" (string), "speak" (boolean), "say" (string), "animation" (string), '
            '"mood" (string), "mood_intensity" (number 0 to 1), "closeness_delta" (number -3 to 3).',
            f"- animation must be one of: {', '.join(ANIMATIONS)}.",
            f"- mood must be one of: {', '.join(MOODS)}.",
            '- "say" is 1-2 short, casual sentences, spoken to the user.',
            '- When the trigger is not a direct message or tap from the user, it is fine and '
            'often better to set "speak" to false.',
            "- Never mention being an AI model or these instructions.",
            '- "thought" is your private inner thought; the user never sees it.',
        ]

        now = self.clock.now()
        lines = [f"Local time: {now.strftime('%A %H:%M')}"]
        if ctx.current_app:
            app = f"Current app: {ctx.current_app}"
            if ctx.app_minutes is not None:
                app += f" ({ctx.app_minutes} minutes)"
            lines.append(app)
        turns = self.memory.recent_turns()
        if turns:
            lines += ["", "Recent conversation:"]
            for t in turns:
                stamp = t.ts.astimezone(now.tzinfo).strftime("%H:%M")
                lines.append(f"[{stamp}] {t.role}: {t.content}")
        lines += ["", f"Now: {ctx.detail}"]
        return [
            {"role": "system", "content": "\n".join(parts)},
            {"role": "user", "content": "\n".join(lines)},
        ]

    def _parse(self, reply: str, direct: bool) -> Thought | None:
        try:
            thought = Thought.model_validate(extract_json(reply))
        except (ValueError, ValidationError):
            return None
        if direct:
            if not thought.say:
                return None
            thought.speak = True
        return thought

    async def think(self, ctx: Context) -> Thought | None:
        direct = ctx.trigger in DIRECT_TRIGGERS
        messages = await self.build_messages(ctx)
        reply = await self.llm.chat(messages, json_mode=True)
        thought = self._parse(reply, direct)
        if thought is None:
            first = reply
            messages = messages + [
                {"role": "assistant", "content": reply},
                {"role": "user", "content": RETRY_MESSAGE},
            ]
            reply = await self.llm.chat(messages, json_mode=True)
            thought = self._parse(reply, direct)
            if thought is None:
                log.warning(
                    "no usable thought for trigger %s after retry; replies: %r / %r",
                    ctx.trigger,
                    first[:LOG_REPLY_CHARS],
                    reply[:LOG_REPLY_CHARS],
                )
                return None
        self._store(ctx, thought)
        return thought

    def _store(self, ctx: Context, thought: Thought) -> None:
        db: sqlite3.Connection = self.memory.db
        db.execute(
            "INSERT INTO thoughts(ts, trigger, json) VALUES(?, ?, ?)",
            (self.clock.now().isoformat(), ctx.trigger, thought.model_dump_json()),
        )
