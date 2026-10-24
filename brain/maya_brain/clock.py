from datetime import datetime, timedelta
from typing import Protocol


class Clock(Protocol):
    def now(self) -> datetime:
        """Return the current tz-aware local time."""
        ...


class SystemClock:
    def now(self) -> datetime:
        return datetime.now().astimezone()


class FakeClock:
    def __init__(self, start: datetime) -> None:
        self._now = start

    def now(self) -> datetime:
        return self._now

    def set(self, dt: datetime) -> None:
        self._now = dt

    def advance(self, td: timedelta) -> None:
        self._now += td
