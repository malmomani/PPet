from contextlib import closing
from datetime import time, timedelta

import pytest

from maya_brain.config import InitiativeConfig
from maya_brain.db import open_db
from maya_brain.initiative import Initiative, in_window
from maya_brain.protocol import AppClosed, AppOpened, Charging, Screen, UserTap


def test_quiet_window_wraps_midnight():
    s, e = time(23, 30), time(8, 0)
    assert in_window(time(23, 45), s, e) and in_window(time(3, 0), s, e) and not in_window(time(12, 0), s, e)
    assert in_window(time(10, 0), time(9, 0), time(17, 0))


def test_tick_multipliers(ini):
    ini.tick("close", "bored"); assert ini.urge == pytest.approx(3.0)
    ini.tick("stranger", "sleepy"); assert ini.urge == pytest.approx(3.15)


def test_long_session_bump_once(ini, clock):
    ini.on_event(AppOpened(package="t", label="TikTok"))      # +5
    clock.advance(timedelta(minutes=46)); ini.tick("friend", "curious"); ini.tick("friend", "curious")
    assert ini.urge == pytest.approx(5 + 1 + 15 + 1) and "TikTok" in ini.last_reason
    assert ini.current_app() == ("TikTok", 46)


def test_return_after_3h(ini, clock):
    ini.on_event(Screen(on=True)); clock.advance(timedelta(hours=4)); ini.on_event(Screen(on=True))
    assert ini.urge == pytest.approx(30) and "came back" in ini.last_reason


def test_guardrails_cooldown_and_shush(ini, clock):
    ini._set_urge(60); assert ini.should_fire()
    ini.fired(spoke=True); ini._set_urge(60); assert not ini.should_fire()
    clock.advance(timedelta(minutes=10)); assert ini.should_fire()
    ini.shush(); ini._set_urge(60); assert not ini.should_fire()
    clock.advance(timedelta(minutes=60)); assert ini.should_fire()


def test_daily_cap_resets_next_day(ini, clock):
    for _ in range(15): ini._set_urge(60); assert ini.should_fire(); ini.fired(True); clock.advance(timedelta(minutes=10))
    ini._set_urge(60); assert not ini.should_fire()
    clock.set(clock.now().replace(hour=9) + timedelta(days=1)); ini._set_urge(60); assert ini.should_fire()


def test_quiet_hours_except_late_night_twice(ini, clock):
    clock.set(clock.now().replace(hour=23, minute=45)); ini._set_urge(60); assert not ini.should_fire()
    night = clock.now() + timedelta(days=1)
    for hour, expected in [(1, True), (2, True), (3, False)]:
        clock.set(night.replace(hour=hour, minute=0))
        ini.on_event(AppOpened(package="y", label="YouTube")); ini._set_urge(60)
        assert ini.should_fire() is expected
        if expected: ini.fired(True)


def test_initiative_persists_across_reopen(tmp_path, clock):
    with closing(open_db(tmp_path / "i.db")) as d1:
        Initiative(InitiativeConfig(), d1, clock).tick("close", "bored")
    with closing(open_db(tmp_path / "i.db")) as d2:
        assert Initiative(InitiativeConfig(), d2, clock).urge == pytest.approx(3.0)


def test_app_switch_and_close_and_charging(ini, clock):
    ini.on_event(AppOpened(package="a", label="Maps"))
    assert ini.urge == pytest.approx(5) and ini.last_reason == "User switched to Maps"
    ini.on_event(AppClosed(package="other", label="X", duration_s=1))
    assert ini.current_app() == ("Maps", 0)
    ini.on_event(AppClosed(package="a", label="Maps", duration_s=60))
    assert ini.current_app() is None
    ini.on_event(AppOpened(package="a", label="Maps")); ini.on_event(Screen(on=False))
    assert ini.current_app() is None
    ini.on_event(Charging(on=True)); assert ini.urge == pytest.approx(13)
    ini.on_event(UserTap()); assert ini.urge == pytest.approx(13)


def test_late_night_bump_once_per_hour(ini, clock):
    clock.set(clock.now().replace(hour=1, minute=5))
    ini.on_event(Screen(on=True)); ini.on_event(Screen(on=True))
    assert ini.urge == pytest.approx(10) and "at night" in ini.last_reason and "01:05" in ini.last_reason
    clock.advance(timedelta(hours=1)); ini.on_event(Screen(on=True))
    assert ini.urge == pytest.approx(20)


def test_stale_late_night_pending_does_not_bypass_quiet_hours(ini, clock):
    clock.set(clock.now().replace(hour=4, minute=50) + timedelta(days=1))
    ini.on_event(Screen(on=True))
    clock.set(clock.now().replace(hour=7, minute=30))
    ini._set_urge(60)
    assert not ini.should_fire()


def test_late_night_pending_cleared_by_later_event_outside_window(ini, clock):
    clock.set(clock.now().replace(hour=4, minute=50) + timedelta(days=1))
    ini.on_event(Screen(on=True))
    clock.set(clock.now().replace(hour=12, minute=0))
    ini.on_event(UserTap())
    clock.set(clock.now().replace(hour=2, minute=0) + timedelta(days=1))
    ini._set_urge(60)
    assert not ini.should_fire()


def test_screen_off_clears_late_night_pending(ini, clock):
    clock.set(clock.now().replace(hour=1, minute=0) + timedelta(days=1))
    ini.on_event(Screen(on=True))
    ini.on_event(Screen(on=False))
    ini._set_urge(60)
    assert not ini.should_fire()


def test_no_base_gain_while_shushed(ini, clock):
    ini.shush()
    ini.tick("close", "bored")
    assert ini.urge == 0
    clock.advance(timedelta(minutes=60))
    ini.tick("close", "bored")
    assert ini.urge == pytest.approx(3.0)


def test_no_base_gain_in_quiet_hours_unless_late_night_pending(ini, clock):
    clock.set(clock.now().replace(hour=23, minute=45))
    ini.tick("friend", "curious")
    assert ini.urge == 0
    clock.set(clock.now().replace(hour=1, minute=0) + timedelta(days=1))
    ini.tick("friend", "curious")
    assert ini.urge == 0
    ini.on_event(Screen(on=True))  # late-night use: +10 and pending
    ini.tick("friend", "curious")
    assert ini.urge == pytest.approx(11)


def test_engaged_resets_urge_and_cooldown_without_counting(ini, clock):
    ini._set_urge(70)
    ini.engaged()
    assert ini.urge == 0
    ini._set_urge(60)
    assert not ini.should_fire()
    clock.advance(timedelta(minutes=10))
    assert ini.should_fire()
    assert ini._day_count == 0
