from datetime import datetime, timedelta, timezone

import pytest

from maya_brain.protocol import NotificationEvent, OfflineSummary, Screen, UserTap, parse_event
from maya_brain.sim import parse_command

NOW = datetime(2026, 10, 24, 14, 0).astimezone()


def note(sender="Sara", app="WhatsApp", **kw):
    return NotificationEvent(package=kw.pop("package", app.lower()), app=app, sender=sender, **kw)


def test_parse_notification():
    e = parse_event(
        {
            "type": "notification",
            "package": "com.whatsapp",
            "app": "WhatsApp",
            "sender": "Sara",
            "group": "Family",
            "kind": "email",
        }
    )
    assert isinstance(e, NotificationEvent) and e.type == "notification"
    assert (e.package, e.app, e.sender, e.group, e.kind) == ("com.whatsapp", "WhatsApp", "Sara", "Family", "email")
    assert e.id and e.ts.tzinfo
    d = parse_event({"type": "notification", "package": "p", "app": "A", "sender": "S"})
    assert d.group is None and d.kind == "message"
    assert parse_event({"type": "notification", "package": "p", "app": "A", "sender": "S", "group": None}).group is None


def test_notification_field_bounds():
    from maya_brain.protocol import ProtocolError

    base = {"type": "notification", "package": "p", "app": "A", "sender": "S"}
    for bad in ({"app": ""}, {"sender": ""}, {"app": "x" * 101}, {"sender": "x" * 101}, {"group": "x" * 101}):
        with pytest.raises(ProtocolError):
            parse_event({**base, **bad})
    assert parse_event({**base, "sender": "x" * 100, "app": "y" * 100, "group": "z" * 100})


def test_unknown_kind_is_message():
    e = parse_event({"type": "notification", "package": "p", "app": "A", "sender": "S", "kind": "telepathy"})
    assert e.kind == "message"
    assert parse_event({"type": "notification", "package": "p", "app": "A", "sender": "S", "kind": 7}).kind == "message"


async def test_memory_line_per_kind(mind):
    cases = [
        (note("Sara", "WhatsApp"), "Sara messaged on WhatsApp"),
        (note("Sara", "WhatsApp", group="Family"), "Sara in Family on WhatsApp"),
        (note("Omar", "Gmail", kind="email"), "Omar emailed (Gmail)"),
        (note("Mum", "Phone", kind="call"), "missed call from Mum (Phone)"),
    ]
    for event, expected in cases:
        await mind.handle(event)
        last = mind.memory.recent_turns()[-1]
        assert (last.role, last.content) == ("event", expected)


def test_bump_and_reason(ini):
    ini.on_event(note("Sara", "WhatsApp"))
    assert ini.urge == pytest.approx(8) and ini.last_reason == "Sara just messaged on WhatsApp"
    ini.on_event(note("Omar", "Gmail", kind="email"))
    assert ini.urge == pytest.approx(16) and ini.last_reason == "Omar just emailed (Gmail)"
    ini.on_event(note("Mum", "Phone", kind="call"))
    assert ini.urge == pytest.approx(24) and ini.last_reason == "missed call from Mum (Phone)"


def test_bump_once_per_sender_per_10_min(ini, clock, db):
    from maya_brain.config import InitiativeConfig
    from maya_brain.initiative import Initiative

    ini.on_event(note("Sara"))
    assert ini.urge == pytest.approx(8)
    clock.advance(timedelta(seconds=599))
    ini.on_event(note("Sara"))
    assert ini.urge == pytest.approx(8)  # same sender within 600 s: no bump
    ini.on_event(note("Omar"))
    assert ini.urge == pytest.approx(16)  # another sender bumps
    # the limit state persists with the rest of initiative state
    reopened = Initiative(InitiativeConfig(), db, clock)
    reopened.on_event(note("Sara"))
    assert reopened.urge == pytest.approx(16)
    clock.advance(timedelta(seconds=1))  # 600 s after Sara's bump
    reopened.on_event(note("Sara"))
    assert reopened.urge == pytest.approx(24)
    reopened.on_event(note("Sara"))  # window restarted at the new bump
    assert reopened.urge == pytest.approx(24)


def test_rate_limit_state_does_not_grow_unbounded(ini, clock):
    for i in range(20):
        ini.on_event(note(f"user{i}"))
        clock.advance(timedelta(seconds=601))
    assert len(ini._notified) <= 2


def test_notification_is_not_user_activity(ini, clock):
    ini.on_event(Screen(on=True))
    ini.on_event(note("Sara"))
    clock.advance(timedelta(hours=4))
    ini.on_event(note("Omar"))  # still 4 h since real activity, but a notification is not a wake
    assert ini.urge == pytest.approx(16)
    assert "came back" not in ini.last_reason
    ini.on_event(UserTap())  # the real return still counts
    assert ini.urge == pytest.approx(46) and "came back after 4 hours" in ini.last_reason


async def test_notification_leaves_mind_idle_tracking_alone(mind, clock, monkeypatch):
    await mind.handle(note("Sara"))
    assert mind._last_event is None
    await mind.handle(UserTap())
    stamp = mind._last_event
    assert stamp == clock.now()
    clock.advance(timedelta(minutes=11))
    await mind.handle(note("Omar"))
    assert mind._last_event == stamp

    seen = []

    async def fake_reflect():
        seen.append(True)

    monkeypatch.setattr(mind.reflection, "due", lambda last_event: True)
    monkeypatch.setattr(mind.reflection, "reflect", fake_reflect)
    await mind.tick()  # 11 min since the tap: idle despite the fresh notification
    assert seen == [True]


async def test_notification_can_make_maya_speak(mind, sent):
    mind.thinker.llm.replies = ['{"speak":true,"say":"Sara texted!"}']
    mind.initiative._set_urge(60)
    await mind.handle(note("Sara"))
    assert [a.text for a in sent if a.type == "say"] == ["Sara texted!"]


async def test_offline_summary_counts_messages(mind):
    def n(sender, app, kind="message"):
        return {"type": "notification", "package": app.lower(), "app": app, "sender": sender, "kind": kind}

    events = [n("Sara", "WhatsApp")] * 4 + [n("Omar", "Gmail", "email"), {"type": "garbage"}]
    events.insert(0, {"type": "app_closed", "package": "y", "label": "YouTube", "duration_s": 3600})
    await mind.handle(OfflineSummary(since=datetime(2026, 10, 24, tzinfo=timezone.utc), events=events))
    assert mind.memory.recent_turns()[-1].content == (
        "While you were disconnected the user used: YouTube 1h 0m; messages: 4 from Sara (WhatsApp), 1 from Omar (Gmail)"
    )


async def test_offline_summary_notifications_only(mind):
    events = [
        {"type": "notification", "package": "w", "app": "WhatsApp", "sender": "Sara"},
        {"type": "notification", "package": "w", "app": "WhatsApp", "sender": "Sara"},
    ]
    await mind.handle(OfflineSummary(since=datetime(2026, 10, 24, tzinfo=timezone.utc), events=events))
    assert mind.memory.recent_turns()[-1].content.endswith("messages: 2 from Sara (WhatsApp)")


def test_sim_notify_command():
    kind, ev = parse_command("/notify Sara", NOW)
    assert kind == "event" and isinstance(ev, NotificationEvent)
    assert (ev.sender, ev.app, ev.package, ev.group) == ("Sara", "WhatsApp", "whatsapp", None)
    ev = parse_command("/notify Omar Gmail", NOW)[1]
    assert (ev.sender, ev.app, ev.package, ev.group) == ("Omar", "Gmail", "gmail", None)
    ev = parse_command("/notify Sara WhatsApp Family", NOW)[1]
    assert (ev.sender, ev.app, ev.group) == ("Sara", "WhatsApp", "Family")
    assert parse_command("/notify", NOW) == ("help", None)
