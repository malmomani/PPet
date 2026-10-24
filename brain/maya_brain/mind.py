"""Mind: composes every module and turns phone events into phone actions."""

import asyncio
import logging
import sqlite3
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta

from .clock import Clock
from .config import Config
from .emotion import Emotion
from .initiative import Initiative
from .llm import LLM, LLMError
from .memory import Memory
from .persona import Persona
from .protocol import (
    Action,
    Animate,
    AppClosed,
    Event,
    Hello,
    OfflineSummary,
    ProtocolError,
    Say,
    Screen,
    State,
    Typing,
    UserDrag,
    UserMessage,
    UserShush,
    UserTap,
    parse_event,
)
from .reflection import Reflection
from .thinker import Context, Thinker, Thought

log = logging.getLogger(__name__)

ActionSink = Callable[[Action], Awaitable[None]]

CANNED_FOGGY = "…my head's a bit foggy. Try again?"

TAP_THINK_INTERVAL = timedelta(seconds=30)
SUMMARIZE_IDLE = timedelta(minutes=10)
MIN_APP_USE_S = 120


def bubble_ms(text: str) -> int:
    return min(15000, max(4000, 2500 + 60 * len(text)))


def _hours_minutes(minutes: int) -> str:
    return f"{minutes // 60}h {minutes % 60}m"


def _fmt_use(seconds: int) -> str:
    minutes = seconds // 60
    return f"{minutes} min" if minutes < 60 else _hours_minutes(minutes)


def _fmt_away(seconds: int) -> str:
    minutes = seconds // 60
    return f"{minutes}m" if minutes < 60 else _hours_minutes(minutes)


class Mind:
    def __init__(
        self, cfg: Config, llm: LLM, db: sqlite3.Connection, clock: Clock, sink: ActionSink | None = None
    ) -> None:
        self.cfg = cfg
        self.clock = clock
        self._sink = sink
        self._lock = asyncio.Lock()
        self._last_event: datetime | None = None
        self._last_tap_think: datetime | None = None
        self._connected = True  # the gateway reports the real state; tests and the simulator are always "connected"
        self._screen_on: bool | None = None  # None = unknown (treated as on)
        self.memory = Memory(db, llm, clock)
        self.persona = Persona(db, cfg.persona, clock)
        self.emotion = Emotion(db, clock)
        self.thinker = Thinker(llm, self.persona, self.memory, self.emotion, clock)
        self.initiative = Initiative(cfg.initiative, db, clock)
        self.reflection = Reflection(llm, self.memory, self.persona, db, clock, cfg.reflection)

    def set_sink(self, sink: ActionSink | None) -> None:
        self._sink = sink

    @property
    def connected(self) -> bool:
        return self._connected

    def set_connected(self, on: bool) -> None:
        self._connected = on

    def _can_be_heard(self) -> bool:
        return self._connected and self._screen_on is not False

    async def _emit(self, action: Action) -> None:
        if self._sink is not None:
            await self._sink(action)

    def status(self) -> dict:
        mood, intensity = self.emotion.mood()
        return {
            "mood": mood,
            "intensity": intensity,
            "closeness": self.emotion.closeness,
            "tier": self.emotion.tier(),
            "urge": self.initiative.urge,
            "current_app": self.initiative.current_app(),
        }

    # ---- events ----

    async def handle(self, event: Event) -> None:
        async with self._lock:
            self._last_event = self.clock.now()
            self.initiative.on_event(event)
            if isinstance(event, UserMessage):
                await self._on_message(event)
                return
            if isinstance(event, UserTap):
                await self._on_tap()
                return
            if isinstance(event, Hello):
                self._screen_on = None
                await self._emit_state()
            elif isinstance(event, Screen):
                self._screen_on = event.on
            elif isinstance(event, UserDrag):
                await self._on_drag(event)
            elif isinstance(event, UserShush):
                self.initiative.shush()
                self.memory.add_turn("event", "User asked you to be quiet for a while")
                await self._emit(Animate(name="sit"))
            elif isinstance(event, AppClosed):
                if event.duration_s >= MIN_APP_USE_S:
                    self.memory.add_turn("event", f"User used {event.label} for {_fmt_use(event.duration_s)}")
            elif isinstance(event, OfflineSummary):
                self._on_offline_summary(event)
            await self._maybe_initiate()

    async def _on_message(self, event: UserMessage) -> None:
        self.memory.add_turn("user", event.text)
        self.emotion.note_interaction()
        await self._respond(Context("user_message", f'User says: "{event.text}"'))

    async def _on_tap(self) -> None:
        self.emotion.note_interaction()
        now = self.clock.now()
        if self._last_tap_think is not None and now - self._last_tap_think < TAP_THINK_INTERVAL:
            await self._emit(Animate(name="happy"))
            return
        self._last_tap_think = now
        await self._respond(Context("user_tap", "User tapped you."))

    async def _on_drag(self, event: UserDrag) -> None:
        text = "User picked you up and threw you!" if event.thrown else "User dragged you around"
        self.memory.add_turn("event", text)
        await self._emit(Animate(name="surprised"))
        if event.thrown:
            thought = await self._think(Context("event", text))
            if thought is not None:
                await self._apply(thought)

    def _on_offline_summary(self, event: OfflineSummary) -> None:
        totals: dict[str, int] = {}  # insertion order = first-seen label order
        for raw in event.events:
            try:
                inner = parse_event(raw)
            except ProtocolError:
                continue
            if isinstance(inner, AppClosed):
                totals[inner.label] = totals.get(inner.label, 0) + inner.duration_s
        parts = [f"{label} {_fmt_away(s)}" for label, s in totals.items() if s >= 60]
        if parts:
            self.memory.add_turn("event", "While you were disconnected the user used: " + ", ".join(parts))

    # ---- thinking ----

    async def _think(self, ctx: Context) -> Thought | None:
        try:
            return await self.thinker.think(ctx)
        except LLMError:
            log.warning("thinking failed for trigger %s", ctx.trigger, exc_info=True)
            return None

    async def _respond(self, ctx: Context) -> None:
        """Direct trigger: typing on, think, (canned reply on failure), typing off, apply."""
        await self._emit(Typing(on=True))
        try:
            thought = await self._think(ctx)
            if thought is None:
                await self._emit(Say(text=CANNED_FOGGY, bubble_ms=bubble_ms(CANNED_FOGGY)))
        finally:
            await self._emit(Typing(on=False))
        if thought is not None:
            await self._apply(thought)

    async def _apply(self, thought: Thought) -> bool:
        """Apply a thought to emotion/memory and emit its actions. Returns whether Maya spoke."""
        before = (self.emotion.mood()[0], self.emotion.tier())
        if thought.mood:
            self.emotion.set_mood(thought.mood, thought.mood_intensity)
        self.emotion.apply_closeness(thought.closeness_delta)
        if thought.animation != "idle":
            await self._emit(Animate(name=thought.animation))
        spoke = bool(thought.speak and thought.say)
        if spoke:
            self.memory.add_turn("maya", thought.say)
            await self._emit(Say(text=thought.say, bubble_ms=bubble_ms(thought.say)))
        if (self.emotion.mood()[0], self.emotion.tier()) != before:
            await self._emit_state()
        return spoke

    async def _emit_state(self) -> None:
        await self._emit(State(mood=self.emotion.mood()[0], closeness_tier=self.emotion.tier()))

    async def _maybe_initiate(self) -> None:
        """Call only while holding the lock."""
        if not self._can_be_heard() or not self.initiative.should_fire():
            return
        app = self.initiative.current_app()
        reason = self.initiative.last_reason or "You feel like saying something."
        ctx = Context("initiative", reason, *(app or ()))
        thought = await self._think(ctx)
        spoke = False
        if thought is not None:
            spoke = await self._apply(thought)
        self.initiative.fired(spoke)

    # ---- periodic ----

    async def tick(self) -> None:
        async with self._lock:
            self.emotion.daily_drift()
            if self._can_be_heard():
                self.initiative.tick(self.emotion.tier(), self.emotion.mood()[0])
            last_turn = self.memory.last_turn_time()
            if (
                self.memory.unsummarized_turns()
                and last_turn is not None
                and self.clock.now() - last_turn >= SUMMARIZE_IDLE
            ):
                await self.reflection.summarize_pending()
            if self.reflection.due(self._last_event):
                await self.reflection.reflect()
            await self._maybe_initiate()

    async def run_ticker(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            try:
                await asyncio.wait_for(stop.wait(), timeout=self.cfg.initiative.tick_s)
                return
            except asyncio.TimeoutError:
                pass
            try:
                await self.tick()
            except Exception:
                log.exception("tick failed")
