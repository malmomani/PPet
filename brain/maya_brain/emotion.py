import sqlite3
from datetime import datetime

from .clock import Clock
from .db import get_state, set_state
from .protocol import MOODS

STATE_KEY = "emotion"

BASELINE_MOOD = "curious"
BASELINE_INTENSITY = 0.3
HALF_LIFE_HOURS = 2.0
REVERT_BELOW = 0.15

CLOSENESS_START = 10.0
CLOSENESS_MIN = 0.0
CLOSENESS_MAX = 100.0
DELTA_LIMIT = 3.0
DAILY_LOSS_CAP = 8.0
DAILY_GAIN_CAP = 8.0
DRIFT_AFTER_DAYS = 2
DRIFT_STEP = 1.0

TIER_GUIDANCE: dict[str, str] = {
    "stranger": (
        "You barely know the user yet: be polite and curious about them, "
        "and rarely start conversations yourself."
    ),
    "friend": (
        "You are comfortable with the user: be chatty and playful, and crack jokes."
    ),
    "close": (
        "You are very close to the user: tease them, check on how they are doing, "
        "remember small things, and get a little sulky if they ignore you."
    ),
}


def closeness_tier(closeness: float) -> str:
    if closeness < 30:
        return "stranger"
    if closeness < 70:
        return "friend"
    return "close"


class Emotion:
    """Maya's mood (decaying toward a baseline) and closeness to the user, persisted in `state`."""

    def __init__(self, db: sqlite3.Connection, clock: Clock) -> None:
        self._db = db
        self._clock = clock
        s = get_state(db, STATE_KEY) or {}
        self._mood: str = s.get("mood") if s.get("mood") in MOODS else BASELINE_MOOD
        self._intensity: float = float(s.get("intensity", BASELINE_INTENSITY))
        self._set_at: datetime = (
            datetime.fromisoformat(s["set_at"]) if s.get("set_at") else self._clock.now()
        )
        self._closeness: float = float(s.get("closeness", CLOSENESS_START))
        self._loss_date: str | None = s.get("loss_date")
        self._loss_today: float = float(s.get("loss_today", 0.0))
        self._gain_today: float = float(s.get("gain_today", 0.0))
        self._last_interaction: datetime | None = (
            datetime.fromisoformat(s["last_interaction"]) if s.get("last_interaction") else None
        )
        self._last_drift_date: str | None = s.get("last_drift_date")

    def _save(self) -> None:
        set_state(
            self._db,
            STATE_KEY,
            {
                "mood": self._mood,
                "intensity": self._intensity,
                "set_at": self._set_at.isoformat(),
                "closeness": self._closeness,
                "loss_date": self._loss_date,
                "loss_today": self._loss_today,
                "gain_today": self._gain_today,
                "last_interaction": (
                    self._last_interaction.isoformat() if self._last_interaction else None
                ),
                "last_drift_date": self._last_drift_date,
            },
        )

    def _today(self) -> str:
        return self._clock.now().date().isoformat()

    # --- mood ---

    def mood(self) -> tuple[str, float]:
        hours = max(0.0, (self._clock.now() - self._set_at).total_seconds() / 3600)
        factor = 0.5 ** (hours / HALF_LIFE_HOURS)
        if self._mood == BASELINE_MOOD:
            return BASELINE_MOOD, BASELINE_INTENSITY + (self._intensity - BASELINE_INTENSITY) * factor
        intensity = self._intensity * factor
        if intensity < REVERT_BELOW:
            return BASELINE_MOOD, BASELINE_INTENSITY
        return self._mood, intensity

    def set_mood(self, mood: str, intensity: float) -> None:
        if mood not in MOODS:
            return
        self._mood = mood
        self._intensity = min(1.0, max(0.0, float(intensity)))
        self._set_at = self._clock.now()
        self._save()

    # --- closeness ---

    @property
    def closeness(self) -> float:
        return self._closeness

    def tier(self) -> str:
        return closeness_tier(self._closeness)

    def apply_closeness(self, delta: float) -> float:
        today = self._today()
        if self._loss_date != today:  # one day key for both daily caps
            self._loss_date = today
            self._loss_today = 0.0
            self._gain_today = 0.0
        applied = min(DELTA_LIMIT, max(-DELTA_LIMIT, float(delta)))
        if applied < 0:
            applied = max(applied, -(DAILY_LOSS_CAP - self._loss_today))
        else:
            applied = min(applied, DAILY_GAIN_CAP - self._gain_today)
        new = min(CLOSENESS_MAX, max(CLOSENESS_MIN, self._closeness + applied))
        applied = new - self._closeness
        self._closeness = new
        if applied < 0:
            self._loss_today -= applied
        else:
            self._gain_today += applied
        self._save()
        return applied

    def note_interaction(self) -> None:
        self._last_interaction = self._clock.now()
        self._save()

    def daily_drift(self) -> None:
        today = self._today()
        if self._last_drift_date == today:
            return
        self._last_drift_date = today
        if self._last_interaction is not None:
            days = (self._clock.now() - self._last_interaction).total_seconds() / 86400
            if days >= DRIFT_AFTER_DAYS:
                gap = CLOSENESS_START - self._closeness
                self._closeness += min(DRIFT_STEP, abs(gap)) * (1 if gap > 0 else -1)
        self._save()
