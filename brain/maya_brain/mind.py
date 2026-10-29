"""Mind: composes every module and turns phone events into phone actions."""

import asyncio
import base64
import logging
import random
import sqlite3
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta
from typing import NamedTuple

from .clock import Clock
from .config import Config
from .emotion import Emotion
from .initiative import USER_ACTIVITY, Initiative
from .llm import LLM, LLMError
from .memory import Memory
from .persona import Persona
from .protocol import (
    Act,
    ActChoice,
    Action,
    ActResultEvent,
    Animate,
    AppClosed,
    AppOpened,
    Audio,
    BodyEvent,
    BodyMomentEvent,
    Event,
    GlanceEvent,
    Hello,
    Look,
    Move,
    NotificationEvent,
    OfflineSummary,
    ProtocolError,
    Say,
    Screen,
    SightEvent,
    SightFailedEvent,
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
from .thinker import (
    ACT_RESULT_TRIGGER,
    BODY_QUIET_DETAIL,
    LOOK_TRIGGER,
    USER_MESSAGE_TRIGGER,
    Context,
    Thinker,
    Thought,
    describe_body,
)
from .tts import TTS, TTSError, speakable
from .vision import Describer, VisionModelMissing

log = logging.getLogger(__name__)

ActionSink = Callable[[Action], Awaitable[None]]

CANNED_FOGGY = "…my head's a bit foggy. Try again?"

TAP_THINK_INTERVAL = timedelta(seconds=30)
SUMMARIZE_IDLE = timedelta(minutes=10)
SUMMARIZE_RETRY = timedelta(minutes=30)  # back-off after a failed summary
REFLECT_IDLE = timedelta(minutes=10)  # reflection holds the lock for several LLM calls: not while the user is around
MIN_APP_USE_S = 120
SCREEN_FRESH = timedelta(minutes=10)  # how long a glance counts as "on screen now"
TEASE_TIERS = {"stranger", "friend", "close"}
TEASE_MOODS = {"happy", "curious", "excited", "bored"}
BODY_THOUGHT_EVERY_S = (60, 120)
BODY_CHAT_QUIET = timedelta(seconds=60)  # no silent body thought this soon after the user talked to or tapped her
ACT_TIERS = {"friend", "close"}  # tiers that may do things on the phone by themselves
# The only kinds she may do on her own: never press, type or web_search (those only when the user asks in a message).
OWN_ACT_KINDS = frozenset({"open_app", "media", "timer", "alarm", "volume", "scroll"})
VISION_MISSING_PAUSE = timedelta(minutes=30)  # automatic looks aren't described this long after a model-not-found
SIGHT_LINE_MAX = 160
SUMMARY_PART_MAX = 40
PENDING_ACTS_MAX = 20  # acts still waiting for their act_result
COULDNT_SEE = "You tried to look at the screen but couldn't see it."
REWARM_AFTER = timedelta(minutes=20)  # the user is back after this long without a thought: load the model again


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


def _clip(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _timer_length(seconds: int) -> str:
    if seconds % 3600 == 0:
        return f"{seconds // 3600}-hour"
    if seconds % 60 == 0:
        return f"{seconds // 60}-minute"
    return f"{seconds}-second"


_MEDIA_WORDS = {
    "play_pause": "play or pause the music",
    "next": "skip to the next song",
    "previous": "go back to the previous song",
}
_VOLUME_WORDS = {"up": "turn the volume up", "down": "turn the volume down", "mute": "mute the sound"}
# Why an act failed or was blocked on the phone (the act_result detail), in plain words.
_DETAIL_WORDS = {
    "off": "your phone-use switch is off",
    "not here": "a private app was open",
    "hidden": "you were hidden",
    "busy": "you were busy doing something else on the phone",
    "screen changed": "the screen changed",
    "not on screen": "it wasn't on the screen",
    "no screen access": "you have no screen access",
    "android blocked it": "Android blocked it",
    "locked": "the phone was locked",
    "offline": "the phone lost its connection",
    "took too long": "it took too long",
}


def summarize(act: Act) -> str:
    """What an act does, as words that follow "you ..." (e.g. `set a 10-minute timer`)."""
    if act.kind == "open_app":
        return f"open {act.app}"
    if act.kind == "media":
        return _MEDIA_WORDS[act.key]
    if act.kind == "timer":
        text = f"set a {_timer_length(act.seconds)} timer"
        return text + (f' for "{act.label}"' if act.label else "")
    if act.kind == "alarm":
        text = f"set an alarm for {act.hour:02d}:{act.minute:02d}"
        return text + (f' for "{act.label}"' if act.label else "")
    if act.kind == "volume":
        return _VOLUME_WORDS[act.dir]
    if act.kind == "web_search":
        return f'search the web for "{_clip(act.query, SUMMARY_PART_MAX)}"'
    if act.kind == "scroll":
        return f"scroll {act.dir}"
    if act.kind == "press":
        return f"press {act.target}"
    text = f'type "{_clip(act.text, SUMMARY_PART_MAX)}"'
    return text + (f" into {act.into}" if act.into else "")


def _act_result_line(act: Act, event: ActResultEvent) -> str:
    summary = summarize(act)
    if event.status == "done":
        return f"you {summary}"
    if event.status == "declined":
        return f"the user said no to {summary}"
    if event.status == "timeout":
        return f"the user didn't answer when you asked to {summary}"
    if not event.detail:
        return f"you couldn't {summary}"
    return f"you couldn't {summary} ({_DETAIL_WORDS.get(event.detail.lower(), event.detail)})"


def _act_failed_detail(line: str) -> str:
    """The prompt for a direct reply when something the user asked her to do on the phone didn't happen."""
    return f"What the user asked you to do on the phone didn't happen: {line}. Tell them in a few words."


class SeenScreen(NamedTuple):
    """The latest screenshot she saw, as words (RAM only)."""

    app: str
    description: str
    at: datetime
    package: str


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
        describer: Describer | None = None,
    ) -> None:
        self.cfg = cfg
        self.tts = tts
        self.describer = describer
        self.sight: SeenScreen | None = None  # the latest screenshot she saw, as words (RAM only)
        self._screen_changes = 0  # bumped when what's on screen changes (another app, app closed, screen off)
        self._seeing: set[asyncio.Task] = set()  # sights being taken in (kept alive when a describe is cancelled)
        # acts sent to the phone, by id, until their act_result arrives; True when the user asked for it in a message
        self._acts: dict[str, tuple[Act, bool]] = {}
        self._acts_off = False  # the phone-use switch was off: no acts of her own until the next Hello or a done
        self._vision_paused_until: datetime | None = None  # automatic looks aren't described until then
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
        self._replies_waiting = 0  # messages and taps waiting for the lock: her own thoughts let them go first
        self._last_think: datetime | None = None
        self._warming: asyncio.Task | None = None
        self.llm = llm
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
        if isinstance(event, SightEvent):
            await self.handle_sight(event)  # describes outside the lock
            return
        if isinstance(event, (Hello, Screen)) and getattr(event, "on", True):
            self._maybe_rewarm()
        if isinstance(event, (UserMessage, UserTap)):
            self._replies_waiting += 1
            try:
                if isinstance(event, UserMessage) and self._lock.locked():
                    await self._emit(Typing(on=True))  # she heard it: dots now, not after her current thought
                await self._handle(event)
            finally:
                self._replies_waiting -= 1
            return
        await self._handle(event)

    def _maybe_rewarm(self) -> None:
        """The user is back after a long quiet: load the model in the background so her first reply is quick."""
        warm = getattr(self.llm, "warm_up", None)
        now = self.clock.now()
        if warm is None or (self._last_think is not None and now - self._last_think < REWARM_AFTER):
            return
        if self._warming is not None and not self._warming.done():
            return
        self._last_think = now  # one warm-up per return

        async def run() -> None:
            try:
                await warm()
            except Exception as e:
                log.debug("warming the model failed: %s", e)

        self._warming = asyncio.create_task(run())

    async def _handle(self, event: Event) -> None:
        async with self._lock:
            if isinstance(event, USER_ACTIVITY):
                self._last_event = self.clock.now()
            if isinstance(event, (UserMessage, UserTap)):
                self._last_chat = self.clock.now()
            fall_counts = self.initiative.fall_counts()  # before on_event starts a new fall window
            if self._changes_screen(event):
                self._screen_changes += 1
            self.initiative.on_event(event)
            if isinstance(event, UserMessage):
                await self._on_message(event)
                return
            if isinstance(event, UserTap):
                await self._on_tap()
                return
            if isinstance(event, SightFailedEvent):
                await self._respond(self._ctx(LOOK_TRIGGER, COULDNT_SEE))
                return
            if isinstance(event, ActResultEvent):
                if await self._on_act_result(event):
                    return
            elif isinstance(event, Hello):
                self._screen_on = None
                self._acts_off = False
                self.voice_mode = event.voice_mode
                await self._emit_state()
            elif isinstance(event, Screen):
                self._screen_on = event.on
                if not event.on:
                    self._forget_glance()
                    self.sight = None
            elif isinstance(event, UserDrag):
                await self._on_drag(event)
            elif isinstance(event, UserShush):
                self.initiative.shush()
                self.memory.add_turn("event", "User asked you to be quiet for a while")
                await self._emit(Animate(name="sit"))
            elif isinstance(event, AppOpened):
                if self.glance is not None and self.glance.package != event.package:
                    self._forget_glance()
                if self.sight is not None and self.sight.package != event.package:
                    self.sight = None
            elif isinstance(event, AppClosed):
                if self.glance is not None and self.glance.package == event.package:
                    self._forget_glance()
                if self.sight is not None and self.sight.package == event.package:
                    self.sight = None
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

    async def handle_sight(self, event: SightEvent) -> None:
        """A screenshot: described by the vision model outside the lock (chat goes on meanwhile), then seen.

        The picture is dropped as soon as it is described; nothing about it is logged.
        """
        image, event.image_b64 = event.image_b64, ""
        changes = self._screen_changes
        description = None
        if self.describer is not None and (event.reason == "asked" or not self._vision_paused()):
            try:
                description = await asyncio.wait_for(self.describer.describe(image), self.cfg.llm.timeout_s)
            except VisionModelMissing as e:  # asked looks still try; automatic ones wait a while
                self._vision_paused_until = self.clock.now() + VISION_MISSING_PAUSE
                log.warning("%s Automatic looks are skipped for 30 minutes.", e)
            except Exception as e:  # a failure or a timeout: she just didn't see it
                log.warning("describing a screenshot failed: %s", type(e).__name__)
        del image
        # From here on a cancel (a newer asked look, the phone going away) no longer stops her taking it in.
        seeing = asyncio.create_task(self.see(event.package, event.app, event.reason, description, changes))
        self._seeing.add(seeing)
        seeing.add_done_callback(self._seeing.discard)
        await asyncio.shield(seeing)

    async def see(
        self, package: str, app: str, reason: str, description: str | None, changes: int | None = None
    ) -> None:
        """What she saw on the screen (None: she couldn't make it out). An asked look gets an answer.

        `changes` is the screen-change count when the screenshot was taken; if the screen changed since,
        she still remembers it (and answers), but doesn't treat it as what's on screen now.
        """
        async with self._lock:
            if description is None:
                if reason == "asked":
                    await self._respond(self._ctx(LOOK_TRIGGER, COULDNT_SEE))
                return  # an auto look that failed is simply forgotten
            if changes is None or changes == self._screen_changes:
                self.sight = SeenScreen(app, description, self.clock.now(), package)
            self.memory.add_turn("event", _clip(f"you looked at {app}: {description}", SIGHT_LINE_MAX))
            if reason == "asked":
                detail = f"You looked at the screen as the user asked. You saw: {description}"
                await self._respond(self._ctx(LOOK_TRIGGER, detail))
                return
            self.initiative.sighted(app)
            await self._maybe_initiate()

    def _vision_paused(self) -> bool:
        return self._vision_paused_until is not None and self.clock.now() < self._vision_paused_until

    async def _on_act_result(self, event: ActResultEvent) -> bool:
        """Remember how an act went. Returns True when she answered the user (an asked act that didn't happen)."""
        pending = self._acts.pop(event.act_id, None)
        if pending is None:
            log.debug("act_result for an unknown act")
            return False
        act, asked = pending
        if event.status == "done":
            self._acts_off = False
        elif event.status == "blocked" and event.detail == "off":
            if not self._acts_off:
                log.info("the phone-use switch is off: no acts of her own until the phone reconnects")
            self._acts_off = True
        line = _act_result_line(act, event)
        self.memory.add_turn("event", line)
        if not asked or event.status == "done":
            return False
        await self._respond(self._ctx(ACT_RESULT_TRIGGER, _act_failed_detail(line)))
        return True

    def _changes_screen(self, event: Event) -> bool:
        """Does this event replace what's on screen (so a screenshot taken before it is stale)?"""
        if isinstance(event, AppOpened):
            return event.package != self.initiative.current_package()
        return isinstance(event, AppClosed) or (isinstance(event, Screen) and not event.on)

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
                await self._apply(thought, "event", closeness=True)

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
        if trigger != LOOK_TRIGGER and self._sight_fresh():
            ctx.sight = f"You saw on screen ({self.sight.app}): {self.sight.description}"
        return ctx

    def _sight_fresh(self) -> bool:
        """A sight counts while it is recent and of the app on screen (the latest glance's, else the current one)."""
        if self.sight is None or self.clock.now() - self.sight.at > SCREEN_FRESH:
            return False
        package = self.glance.package if self.glance is not None else self.initiative.current_package()
        return package is None or package == self.sight.package

    async def _think(self, ctx: Context) -> Thought | None:
        self._last_think = self.clock.now()
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
            await self._apply(thought, ctx.trigger, closeness=True)

    async def _apply(self, thought: Thought, trigger: str, closeness: bool = False) -> bool:
        """Apply a thought to emotion/memory and emit its actions. Returns whether Maya spoke.

        Closeness only moves for thoughts about something the user did to her (`closeness=True`);
        her own unprompted musings must not make her fonder or colder of the user.
        """
        before = (self.emotion.mood()[0], self.emotion.tier())
        tease_ok = self._tease_allowed()  # as offered to her in the prompt, before this thought changes her mood
        # Only a message from the user asks her to act or look; anything else (a tap, the answer to a look,
        # her own idea) follows the own-initiative rules, and a silent body thought never acts.
        asked = trigger == USER_MESSAGE_TRIGGER
        act = thought.act if trigger != "body" else None
        act_ok = act is not None and (asked or self._own_act_allowed(act))
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
        if act_ok:
            await self._act(Act(**act.fields()), asked)
        if thought.look and asked:
            # Only when the user messaged her: the phone answers with an "asked" sight, which she replies to.
            await self._emit(Look())
        if (self.emotion.mood()[0], self.emotion.tier()) != before:
            await self._emit_state()
        return spoke

    def _own_act_allowed(self, act: ActChoice) -> bool:
        """Her own idea to use the phone: only harmless kinds, as a friend, while the user can see it, a few per hour,
        and not while the phone-use switch is off."""
        return (
            act.kind in OWN_ACT_KINDS
            and not self._acts_off
            and self.emotion.tier() in ACT_TIERS
            and self._can_be_heard()
            and self.initiative.act_ready()  # not quiet hours, not shushed, under the hourly limit
        )

    async def _act(self, act: Act, asked: bool) -> None:
        self._acts[act.id] = (act, asked)
        while len(self._acts) > PENDING_ACTS_MAX:
            del self._acts[next(iter(self._acts))]
        if not asked:
            self.initiative.acted()
        log.info("act %s (%s)", act.kind, "asked by the user" if asked else "her own idea")
        await self._emit(act)

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
        """Call only while holding the lock. A reply the user waits for goes first."""
        if self._replies_waiting or not self._can_be_heard() or not self.initiative.should_fire():
            return
        reason = self.initiative.last_reason or "You feel like saying something."
        thought = await self._think(self._ctx("initiative", reason))
        spoke = False
        if thought is not None:
            spoke = await self._apply(thought, "initiative")
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
            if user_idle and not self._replies_waiting and self.reflection.due(self._last_event):
                await self.reflection.reflect()
            await self._maybe_initiate()
            await self._maybe_body_thought()

    async def _maybe_body_thought(self) -> None:
        """Every 2-4 minutes while the user is around, a silent thought about what her body does next.

        Only for a phone that reports her body, and not right after the user talked to or tapped her.
        """
        if self._replies_waiting or not (
            self.body is not None and self._can_be_heard() and self.initiative.body_allowed()
        ):
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
            await self._apply(thought, "body")
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
