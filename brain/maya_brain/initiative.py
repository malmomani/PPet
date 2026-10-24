"""Cheap rule-based urge meter: decides when Maya wants to speak unprompted."""

import sqlite3
from datetime import datetime, time, timedelta

from .clock import Clock
from .config import InitiativeConfig
from .db import get_state, set_state
from .protocol import (
    AppClosed,
    AppOpened,
    Charging,
    Hello,
    Screen,
    UserDrag,
    UserMessage,
    UserShush,
    UserTap,
)

STATE_KEY = "initiative"

TIER_MULT: dict[str, float] = {"stranger": 0.5, "friend": 1.0, "close": 1.5}
MOOD_MULT: dict[str, float] = {"bored": 2.0, "excited": 1.5, "sleepy": 0.3}

LATE_NIGHT_START = time(0, 0)
LATE_NIGHT_END = time(5, 0)
AWAY_AFTER = timedelta(hours=3)
LONG_SESSION_MIN = 45

APP_SWITCH_BUMP = 5.0
CHARGING_BUMP = 3.0
RETURN_BUMP = 30.0
LATE_NIGHT_BUMP = 10.0
LONG_SESSION_BUMP = 15.0

# Events that mean the user is actually around. Hello, Charging and OfflineSummary are not.
USER_ACTIVITY = (AppOpened, AppClosed, Screen, UserMessage, UserTap, UserDrag, UserShush)


def _parse_hhmm(s: str) -> time:
    hh, mm = s.split(":")
    return time(int(hh), int(mm))


def in_window(t: time, start: time, end: time) -> bool:
    """True if t is within [start, end); wraps midnight when start > end."""
    if start <= end:
        return start <= t < end
    return t >= start or t < end


def _dt(value: str | None) -> datetime | None:
    return None if value is None else datetime.fromisoformat(value)


class Initiative:
    def __init__(self, cfg: InitiativeConfig, db: sqlite3.Connection, clock: Clock) -> None:
        self.cfg = cfg
        self.db = db
        self.clock = clock
        self._quiet_start = _parse_hhmm(cfg.quiet_start)
        self._quiet_end = _parse_hhmm(cfg.quiet_end)
        s = get_state(db, STATE_KEY) or {}
        self._urge: float = s.get("urge", 0.0)
        self._last_reason: str = s.get("last_reason", "")
        self._app: dict | None = s.get("app")  # {"package", "label", "start"}
        self._session_bumped: bool = s.get("session_bumped", False)
        self._last_activity: str | None = s.get("last_activity")
        self._silent_until: str | None = s.get("silent_until")
        self._last_remark: str | None = s.get("last_remark")
        self._day: str | None = s.get("day")
        self._day_count: int = s.get("day_count", 0)
        self._night: str | None = s.get("night")
        self._night_count: int = s.get("night_count", 0)
        self._late_pending: bool = s.get("late_pending", False)
        self._late_hour: str | None = s.get("late_hour")

    # ---- persistence ----

    def _save(self) -> None:
        set_state(
            self.db,
            STATE_KEY,
            {
                "urge": self._urge,
                "last_reason": self._last_reason,
                "app": self._app,
                "session_bumped": self._session_bumped,
                "last_activity": self._last_activity,
                "silent_until": self._silent_until,
                "last_remark": self._last_remark,
                "day": self._day,
                "day_count": self._day_count,
                "night": self._night,
                "night_count": self._night_count,
                "late_pending": self._late_pending,
                "late_hour": self._late_hour,
            },
        )

    def _set_urge(self, v: float) -> None:
        """Test helper: set the urge directly and persist it."""
        self._urge = v
        self._save()

    # ---- properties ----

    @property
    def urge(self) -> float:
        return self._urge

    @property
    def last_reason(self) -> str:
        return self._last_reason

    def current_app(self) -> tuple[str, int] | None:
        if self._app is None:
            return None
        minutes = int((self.clock.now() - datetime.fromisoformat(self._app["start"])).total_seconds() // 60)
        return self._app["label"], minutes

    # ---- inputs ----

    def _bump(self, amount: float, reason: str | None = None) -> None:
        self._urge += amount
        if reason is not None:
            self._last_reason = reason

    def on_event(self, event: object) -> None:
        now = self.clock.now()
        previous = _dt(self._last_activity)
        bumps: list[tuple[float, str | None]] = []

        opened = isinstance(event, AppOpened)
        screen_on = isinstance(event, Screen) and event.on
        wake = opened or screen_on or isinstance(event, (UserMessage, UserTap))

        if wake and previous is not None and now - previous > AWAY_AFTER:
            hours = int((now - previous).total_seconds() // 3600)
            bumps.append((RETURN_BUMP, f"User came back after {hours} hours"))

        if isinstance(event, AppOpened):
            self._app = {"package": event.package, "label": event.label, "start": now.isoformat()}
            self._session_bumped = False
            bumps.append((APP_SWITCH_BUMP, f"User switched to {event.label}"))
        elif isinstance(event, AppClosed):
            if self._app is not None and self._app["package"] == event.package:
                self._app = None
                self._session_bumped = False
        elif isinstance(event, Screen) and not event.on:
            self._app = None
            self._session_bumped = False
            self._late_pending = False  # nobody is looking any more
        elif isinstance(event, Hello):
            self._app = None  # the phone (re)connected: whatever app we tracked is stale
            self._session_bumped = False
        elif isinstance(event, Charging) and event.on:
            bumps.append((CHARGING_BUMP, None))

        if not in_window(now.time(), LATE_NIGHT_START, LATE_NIGHT_END):
            self._late_pending = False  # a stale trigger from an earlier night must not linger
        elif opened or screen_on:
            self._late_pending = True
            hour_key = now.strftime("%Y-%m-%d %H")
            if self._late_hour != hour_key:
                self._late_hour = hour_key
                bumps.append(
                    (
                        LATE_NIGHT_BUMP,
                        f"It's {now.strftime('%H:%M')} at night and the user is still on their phone",
                    )
                )

        for amount, _ in bumps:
            self._urge += amount
        reasoned = [b for b in bumps if b[1] is not None]
        if reasoned:
            self._last_reason = max(reasoned, key=lambda b: b[0])[1]  # the biggest bump explains the urge

        if isinstance(event, USER_ACTIVITY):
            self._last_activity = now.isoformat()
        self._save()

    def tick(self, tier: str, mood: str) -> None:
        now = self.clock.now()
        silent_until = _dt(self._silent_until)
        silenced = silent_until is not None and now < silent_until
        quiet = self._in_quiet(now) and not self._late_pending
        if not (silenced or quiet):
            self._bump(TIER_MULT.get(tier, 1.0) * MOOD_MULT.get(mood, 1.0))
        app = self.current_app()
        if app is not None and app[1] > LONG_SESSION_MIN and not self._session_bumped:
            self._session_bumped = True
            self._bump(LONG_SESSION_BUMP, f"User has been on {app[0]} for {app[1]} minutes")
        self._save()

    def engaged(self) -> None:
        """Maya just answered the user directly: start over and respect the cooldown, but don't count it."""
        self._urge = 0.0
        self._last_remark = self.clock.now().isoformat()
        self._save()

    def shush(self) -> None:
        self._urge = 0.0
        self._silent_until = (self.clock.now() + timedelta(minutes=self.cfg.shush_min)).isoformat()
        self._save()

    # ---- decisions ----

    @staticmethod
    def _tonight(now: datetime) -> str:
        return str((now - timedelta(hours=12)).date())

    def _in_quiet(self, now: datetime) -> bool:
        return in_window(now.time(), self._quiet_start, self._quiet_end)

    def should_fire(self) -> bool:
        now = self.clock.now()
        if self._urge < self.cfg.threshold:
            return False
        silent_until = _dt(self._silent_until)
        if silent_until is not None and now < silent_until:
            return False
        last_remark = _dt(self._last_remark)
        if last_remark is not None and now - last_remark < timedelta(minutes=self.cfg.cooldown_min):
            return False
        today = str(now.date())
        if self._day == today and self._day_count >= self.cfg.daily_cap:
            return False
        if self._in_quiet(now):
            if not (self._late_pending and in_window(now.time(), LATE_NIGHT_START, LATE_NIGHT_END)):
                return False
            night_count = self._night_count if self._night == self._tonight(now) else 0
            if night_count >= self.cfg.late_night_max:
                return False
        return True

    def fired(self, spoke: bool) -> None:
        now = self.clock.now()
        self._urge = 0.0
        self._late_pending = False
        if spoke:
            self._last_remark = now.isoformat()
            today = str(now.date())
            if self._day != today:
                self._day, self._day_count = today, 0
            self._day_count += 1
            if self._in_quiet(now):
                tonight = self._tonight(now)
                if self._night != tonight:
                    self._night, self._night_count = tonight, 0
                self._night_count += 1
        self._save()
