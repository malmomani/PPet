from datetime import datetime, timedelta, timezone

from maya_brain.clock import FakeClock, SystemClock


def test_fake_clock_advance():
    start = datetime(2026, 10, 24, 12, 0, tzinfo=timezone.utc)
    c = FakeClock(start)
    c.advance(timedelta(hours=3))
    assert c.now() == start + timedelta(hours=3)


def test_fake_clock_set():
    c = FakeClock(datetime(2026, 10, 24, 12, 0, tzinfo=timezone.utc))
    later = datetime(2026, 10, 25, 1, 0, tzinfo=timezone.utc)
    c.set(later)
    assert c.now() == later


def test_system_clock_is_aware():
    assert SystemClock().now().tzinfo is not None
