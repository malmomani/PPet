"""Mind: composes every module and turns phone events into phone actions."""

import asyncio
import base64
import logging
import random
import sqlite3
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta

from .clock import Clock
from .config import Config
from .emotion import Emotion
from .initiative import USER_ACTIVITY, Initiative
from .llm import LLM, LLMError
from .memory import Memory
from .persona import Persona
from .protocol import (
    Action,
    Animate,
    AppClosed,
    AppOpened,
    Audio,
    BodyEvent,
    BodyMomentEvent,
    Event,
    GlanceEvent,
    Hello,
    Move,
    NotificationEvent,
    OfflineSummary,
    ProtocolError,
    Say,
    Screen,
    State,
    TEASE_INTENTS,
    Typing,
    UserDrag,
    UserMessage,
    UserShush,
    UserTap,
    parse_event,
)
from .reflection import Reflection
from .thinker import BODY_QUIET_DETAIL, Context, Thinker, Thought, describe_body
from .tts import TTS, TTSError, speakable

log = logging.getLogger(__name__)

ActionSink = Callable[[Action], Awaitable[None]]

CANNED_FOGGY = "…my head's a bit foggy. Try again?"

TAP_THINK_INTERVAL = timedelta(seconds=30)
SUMMARIZE_IDLE = timedelta(minutes=10)
SUMMARIZE_RETRY = timedelta(minutes=30)  # back-off after a failed summary
REFLECT_IDLE = timedelta(minutes=10)  # reflection holds the lock for several LLM calls: not while the user is around
MIN_APP_USE_S = 120
SCREEN_FRESH = timedelta(minutes=10)  # how long a glance counts as "on screen now"
TEASE_TIERS = {"friend", "close"}
TEASE_MOODS = {"happy", "curious", "excited"}
BODY_THOUGHT_EVERY_S = (120, 240)
BODY_CHAT_QUIET = timedelta(seconds=60)  # no silent body thought this soon after the user talked to or tapped her


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


def _notification_line(event: NotificationEvent) -> str:
    if event.kind == "email":
        return f"{event.sender} emailed ({event.app})"
    if event.kind == "call":
        return f"missed call from {event.sender} ({event.app})"
    if event.group:
        return f"{event.sender} in {event.group} on {event.app}"
    return f"{event.sender} messaged on {event.app}"


def _glance_headline(lines: list[str]) -> str:
    """The first line with real words in it (a clock or a counter is not), else the first line."""
    for line in lines:
        if sum(c.isalpha() for c in line) >= 12:
            return line[:120].rstrip()
    return lines[0][:120].rstrip()


def _moment_line(event: BodyMomentEvent) -> str:
    if event.kind == "fell":
        return "you fell when the screen moved under you"
    if event.target:
        return f"the user shooed you off the {event.target} button"
    return "the user shooed you away"


class Mind:
    def __init__(
        self,
        cfg: Config,
        llm: LLM,
        db: sqlite3.Connection,
        clock: Clock,
        sink: ActionSink | None = None,
        tts: TTS | None = None,
    ) -> None:
        self.cfg = cfg
        self.tts = tts
        self.voice_mode = "off"  # from the latest Hello: off | replies | always
        self._voice_reply = False  # True while handling a UserMessage that was spoken
        self._voice_task: asyncio.Task | None = None
        self.clock = clock
        self._sink = sink
        self._lock = asyncio.Lock()
        self._last_event: datetime | None = None
        self._last_tap_think: datetime | None = None
        self._last_chat: datetime | None = None  # the user's last message or tap
        self._summarize_after: datetime | None = None
        self._connected = True  # the gateway reports the real state; tests and the simulator are always "connected"
        self._screen_on: bool | None = None  # None = unknown (treated as on)
        self.glance: GlanceEvent | None = None  # the latest screen text (RAM only)
        self.glance_at: datetime | None = None
        self.body: BodyEvent | None = None  # where her body is now (RAM only)
        self._rng = random.Random()
        self._next_body_at: datetime | None = None  # when the next silent body thought is due
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
            if isinstance(event, USER_ACTIVITY):
                self._last_event = self.clock.now()
            if isinstance(event, (UserMessage, UserTap)):
                self._last_chat = self.clock.now()
            fall_counts = self.initiative.fall_counts()  # before on_event starts a new fall window
            self.initiative.on_event(event)
            if isinstance(event, UserMessage):
                await self._on_message(event)
                return
            if isinstance(event, UserTap):
                await self._on_tap()
                return
            if isinstance(event, Hello):
                self._screen_on = None
                self.voice_mode = event.voice_mode
                await self._emit_state()
            elif isinstance(event, Screen):
                self._screen_on = event.on
                if not event.on:
                    self._forget_glance()
            elif isinstance(event, UserDrag):
                await self._on_drag(event)
            elif isinstance(event, UserShush):
                self.initiative.shush()
                self.memory.add_turn("event", "User asked you to be quiet for a while")
                await self._emit(Animate(name="sit"))
            elif isinstance(event, AppOpened):
                if self.glance is not None and self.glance.package != event.package:
                    self._forget_glance()
            elif isinstance(event, AppClosed):
                if self.glance is not None and self.glance.package == event.package:
                    self._forget_glance()
                if event.duration_s >= MIN_APP_USE_S:
                    self.memory.add_turn("event", f"User used {event.label} for {_fmt_use(event.duration_s)}")
            elif isinstance(event, NotificationEvent):
                self.memory.add_turn("event", _notification_line(event))
            elif isinstance(event, GlanceEvent):
                self.glance, self.glance_at = event, self.clock.now()
                if event.lines:
                    self.memory.add_turn("event", f"on screen in {event.app}: {_glance_headline(event.lines)}")
            elif isinstance(event, BodyEvent):
                self.body = event
            elif isinstance(event, BodyMomentEvent):
                if event.kind != "fell" or fall_counts:
                    self.memory.add_turn("event", _moment_line(event))
            elif isinstance(event, OfflineSummary):
                self._on_offline_summary(event)
            await self._maybe_initiate()

    def _forget_glance(self) -> None:
        """The screen she last saw is gone (another app, the app closed, the screen off)."""
        self.glance, self.glance_at = None, None

    async def _on_message(self, event: UserMessage) -> None:
        self.memory.add_turn("user", event.text)
        self.emotion.note_interaction()
        self._voice_reply = event.voice
        try:
            await self._respond(self._ctx("user_message", f'User says: "{event.text}"'))
        finally:
            self._voice_reply = False

    async def _on_tap(self) -> None:
        self.emotion.note_interaction()
        now = self.clock.now()
        if self._last_tap_think is not None and now - self._last_tap_think < TAP_THINK_INTERVAL:
            await self._emit(Animate(name="happy"))
            return
        self._last_tap_think = now
        await self._respond(self._ctx("user_tap", "User tapped you."))

    async def _on_drag(self, event: UserDrag) -> None:
        text = "User picked you up and threw you!" if event.thrown else "User dragged you around"
        self.memory.add_turn("event", text)
        await self._emit(Animate(name="surprised"))
        if event.thrown:
            thought = await self._think(self._ctx("event", text))
            if thought is not None:
                await self._apply(thought, closeness=True)

    def _on_offline_summary(self, event: OfflineSummary) -> None:
        totals: dict[str, int] = {}  # insertion order = first-seen label order
        contacts: dict[tuple[str, str], int] = {}  # (sender, app) -> notification count, first-seen order
        for raw in event.events:
            try:
                inner = parse_event(raw)
            except ProtocolError:
                continue
            if isinstance(inner, AppClosed):
                totals[inner.label] = totals.get(inner.label, 0) + inner.duration_s
            elif isinstance(inner, NotificationEvent):
                key = (inner.sender, inner.app)
                contacts[key] = contacts.get(key, 0) + 1
        parts = [f"{label} {_fmt_away(s)}" for label, s in totals.items() if s >= 60]
        line = "While you were disconnected"
        if parts:
            line += " the user used: " + ", ".join(parts)
        if contacts:
            messages = "messages: " + ", ".join(f"{n} from {sender} ({app})" for (sender, app), n in contacts.items())
            line += "; " + messages if parts else " the user got " + messages
        if parts or contacts:
            self.memory.add_turn("event", line)

    # ---- thinking ----

    def _glance_fresh(self) -> bool:
        return self.glance is not None and self.glance_at is not None and self.clock.now() - self.glance_at <= SCREEN_FRESH

    def _tease_allowed(self) -> bool:
        """May she tease the user right now (and so be told which buttons she can tease)?"""
        return (
            self._glance_fresh()
            and bool(self.glance.targets)
            and self.emotion.tier() in TEASE_TIERS
            and self.emotion.mood()[0] in TEASE_MOODS
            and self.initiative.tease_ready()
            and self._can_be_heard()
        )

    def _ctx(self, trigger: str, detail: str) -> Context:
        """Every thought's context: what she is doing, where her body is, what is on screen."""
        app = self.initiative.current_app()
        ctx = Context(trigger, detail, *(app or ()))
        if self.body is not None:
            ctx.body = describe_body(self.body, app[0] if app else None)
        if self._glance_fresh():
            ctx.screen_app, ctx.screen_lines = self.glance.app, tuple(self.glance.lines)
            if self._tease_allowed():
                ctx.tease_targets = tuple(self.glance.targets)
        return ctx

    async def _think(self, ctx: Context) -> Thought | None:
        try:
            return await self.thinker.think(ctx)
        except LLMError as e:
            log.warning("thinking failed for trigger %s: %s", ctx.trigger, e)
            return None

    async def _respond(self, ctx: Context) -> None:
        """Direct trigger: typing on, think, (canned reply on failure), typing off, apply."""
        await self._emit(Typing(on=True))
        try:
            thought = await self._think(ctx)
            if thought is None:
                await self._say(CANNED_FOGGY)
        finally:
            await self._emit(Typing(on=False))
        self.initiative.engaged()
        if thought is not None:
            await self._apply(thought, closeness=True)

    async def _apply(self, thought: Thought, closeness: bool = False) -> bool:
        """Apply a thought to emotion/memory and emit its actions. Returns whether Maya spoke.

        Closeness only moves for thoughts about something the user did to her (`closeness=True`);
        her own unprompted musings must not make her fonder or colder of the user.
        """
        before = (self.emotion.mood()[0], self.emotion.tier())
        tease_ok = self._tease_allowed()  # as offered to her in the prompt, before this thought changes her mood
        if thought.mood:
            self.emotion.set_mood(thought.mood, thought.mood_intensity)
        if closeness:
            self.emotion.apply_closeness(thought.closeness_delta)
        if thought.animation != "idle":
            await self._emit(Animate(name=thought.animation))
        spoke = bool(thought.speak and thought.say)
        if spoke:
            self.memory.add_turn("maya", thought.say)
            await self._say(thought.say)
        if thought.move is not None:
            await self._move(thought.move.intent, thought.move.target, tease_ok)
        if (self.emotion.mood()[0], self.emotion.tier()) != before:
            await self._emit_state()
        return spoke

    async def _move(self, intent: str, target: str | None, tease_ok: bool) -> None:
        """Send a move to the body. Teases only when allowed and aimed at a button that is really on screen."""
        if intent in TEASE_INTENTS:
            if not tease_ok or target not in self.glance.targets:
                return
            self.initiative.teased()
            self.memory.add_turn("event", f"you teased the user: {intent} {target}")
        else:
            target = None
        await self._emit(Move(intent=intent, target=target))

    async def _say(self, text: str) -> None:
        say = Say(text=text, bubble_ms=bubble_ms(text))
        await self._emit(say)
        self._maybe_voice(say)

    # ---- voice ----

    def _should_voice(self) -> bool:
        if self.tts is None or not self.cfg.voice.enabled:
            return False
        if self.voice_mode == "always":
            return True
        return self.voice_mode == "replies" and self._voice_reply

    def _maybe_voice(self, say: Say) -> None:
        """Start synthesis in the background (the caller holds the lock; TTS must not)."""
        if not self._should_voice():
            return
        text = speakable(say.text)
        if not text:
            return
        if self._voice_task is not None:
            self._voice_task.cancel()  # a newer say supersedes a pending older one
        self._voice_task = asyncio.create_task(self._voice(say.id, text))

    async def _voice(self, say_id: str, text: str) -> None:
        assert self.tts is not None
        try:
            wav = await self.tts.synth(text)
        except TTSError as e:
            log.warning("voice synthesis failed: %s", e)
            return
        try:
            await self._emit(Audio(ref_id=say_id, data_b64=base64.b64encode(wav).decode("ascii")))
        except Exception as e:  # e.g. the phone went away mid-send
            log.warning("sending voice audio failed: %s", e)

    async def wait_voice(self) -> None:
        """Wait for the in-flight TTS task (if any); for tests and the simulator."""
        while self._voice_task is not None and not self._voice_task.done():
            await asyncio.wait([self._voice_task])  # cancelled tasks end quietly; our own cancellation still raises

    async def _emit_state(self) -> None:
        await self._emit(State(mood=self.emotion.mood()[0], closeness_tier=self.emotion.tier()))

    async def _maybe_initiate(self) -> None:
        """Call only while holding the lock."""
        if not self._can_be_heard() or not self.initiative.should_fire():
            return
        reason = self.initiative.last_reason or "You feel like saying something."
        thought = await self._think(self._ctx("initiative", reason))
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
            now = self.clock.now()
            last_turn = self.memory.last_turn_time()
            if (
                self.memory.unsummarized_turns()
                and last_turn is not None
                and now - last_turn >= SUMMARIZE_IDLE
                and (self._summarize_after is None or now >= self._summarize_after)
            ):
                if await self.reflection.summarize_pending() is None:
                    self._summarize_after = now + SUMMARIZE_RETRY
            user_idle = self._last_event is None or now - self._last_event >= REFLECT_IDLE
            if user_idle and self.reflection.due(self._last_event):
                await self.reflection.reflect()
            await self._maybe_initiate()
            await self._maybe_body_thought()

    async def _maybe_body_thought(self) -> None:
        """Every 2-4 minutes while the user is around, a silent thought about what her body does next.

        Only for a phone that reports her body, and not right after the user talked to or tapped her.
        """
        if not (self.body is not None and self._can_be_heard() and self.initiative.body_allowed()):
            return
        now = self.clock.now()
        if self._next_body_at is None:
            self._schedule_body_thought(now)
            return
        if now < self._next_body_at:
            return
        if self._last_chat is not None and now - self._last_chat < BODY_CHAT_QUIET:
            return  # stays due
        thought = await self._think(self._ctx("body", BODY_QUIET_DETAIL))
        if thought is not None:
            await self._apply(thought)
        self._schedule_body_thought(self.clock.now())

    def _schedule_body_thought(self, now: datetime) -> None:
        self._next_body_at = now + timedelta(seconds=self._rng.uniform(*BODY_THOUGHT_EVERY_S))

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
