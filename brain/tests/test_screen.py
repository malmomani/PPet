from datetime import datetime, timedelta

import pytest

from maya_brain.protocol import (
    AppClosed,
    AppOpened,
    BodyEvent,
    BodyMomentEvent,
    GlanceEvent,
    Move,
    ProtocolError,
    Screen,
    UserTap,
    parse_event,
)
from maya_brain.sim import parse_command, run_sim

NOW = datetime(2026, 10, 24, 14, 0).astimezone()


def glance(app="Chrome", lines=("hello there",), **kw):
    return GlanceEvent(package=kw.pop("package", app.lower()), app=app, lines=list(lines), targets=kw.pop("targets", []))


def test_parse_glance_lenient():
    raw = {
        "type": "glance",
        "package": "com.android.chrome",
        "app": "Chrome",
        "lines": [f"line {i}" for i in range(41)],
        "targets": ["send", "rocket", "send"],
    }
    e = parse_event(raw)
    assert isinstance(e, GlanceEvent) and e.type == "glance" and e.id and e.ts.tzinfo
    assert len(e.lines) == 40 and e.lines[0] == "line 0" and e.lines[-1] == "line 39"
    assert e.targets == ["send"]

    e = parse_event({**raw, "lines": ["x" * 250, "", "   ", "ok"], "targets": ["like", "send", "like", 7]})
    assert e.lines == ["x" * 200, "ok"]
    assert e.targets == ["like", "send"]

    # blanks do not count towards the 40-line cap
    e = parse_event({**raw, "lines": ["", *[f"l{i}" for i in range(45)]]})
    assert len(e.lines) == 40 and e.lines[0] == "l0"

    # garbage in lines/targets never rejects
    e = parse_event({**raw, "lines": "not a list", "targets": None})
    assert e.lines == [] and e.targets == []
    assert parse_event({**raw, "lines": [1, None, "fine"]}).lines == ["fine"]
    assert parse_event({"type": "glance", "package": "p", "app": "A"}).lines == []


def test_glance_app_bounds():
    base = {"type": "glance", "package": "p", "app": "A"}
    for bad in ({"app": ""}, {"app": "x" * 101}):
        with pytest.raises(ProtocolError):
            parse_event({**base, **bad})
    assert parse_event({**base, "app": "x" * 100})


def test_parse_body_defaults():
    e = parse_event({"type": "body", "doing": "moonwalk", "where": "ceiling", "target": "rocket", "x": 1.7, "y": -0.2})
    assert isinstance(e, BodyEvent) and e.type == "body"
    assert (e.doing, e.where, e.target, e.x, e.y) == ("idle", "floor", None, 1.0, 0.0)
    e = parse_event(
        {"type": "body", "doing": "tease", "where": "target", "target": "send", "x": 0.123456, "y": 0.987}
    )
    assert (e.doing, e.where, e.target, e.x, e.y) == ("tease", "target", "send", 0.12, 0.99)
    for doing in ("idle", "walk", "sit", "perch", "jump", "fall", "nap", "peek", "tease", "dragged"):
        assert parse_event({"type": "body", "doing": doing, "where": "platform", "x": 0, "y": 0}).doing == doing
    e = parse_event({"type": "body", "doing": 5, "where": None, "target": 3, "x": "left", "y": float("nan")})
    assert (e.doing, e.where, e.target) == ("idle", "floor", None)
    assert 0.0 <= e.x <= 1.0 and 0.0 <= e.y <= 1.0


def test_parse_body_moment():
    e = parse_event({"type": "body_moment", "kind": "shooed", "target": "send"})
    assert isinstance(e, BodyMomentEvent) and e.type == "body_moment" and (e.kind, e.target) == ("shooed", "send")
    e = parse_event({"type": "body_moment", "kind": "fell"})
    assert (e.kind, e.target) == ("fell", None)
    assert parse_event({"type": "body_moment", "kind": "fell", "target": "rocket"}).target is None
    with pytest.raises(ProtocolError):
        parse_event({"type": "body_moment", "kind": "exploded"})


def test_move_serializes():
    m = Move(intent="sit_on", target="send")
    assert '"type":"move"' in m.model_dump_json()
    d = m.model_dump(mode="json")
    assert d["type"] == "move" and d["intent"] == "sit_on" and d["target"] == "send" and d["id"] and d["ts"]
    assert Move(intent="stay").model_dump(mode="json")["target"] is None
    with pytest.raises(ValueError):
        Move(intent="fly")
    with pytest.raises(ValueError):
        Move(intent="poke", target="rocket")


def test_constants():
    from maya_brain import protocol as p

    assert p.TEASE_TARGETS == ("send", "like", "play", "search", "share", "comment", "follow")
    assert p.TEASE_INTENTS == ("sit_on", "poke")
    assert p.MOVE_INTENTS == (
        "stay", "wander", "come_closer", "go_left", "go_right", "go_up", "go_down",
        "sit", "nap", "peek", "sit_on", "poke",
    )  # fmt: skip
    assert p.BODY_DOING == ("idle", "walk", "sit", "perch", "jump", "fall", "nap", "peek", "tease", "dragged")
    assert p.BODY_WHERE == ("floor", "platform", "target")


def test_old_types_still_parse():
    assert parse_event({"type": "screen", "on": True}).on is True
    assert parse_event({"type": "screen", "on": False}).on is False
    n = parse_event({"type": "notification", "package": "p", "app": "A", "sender": "S"})
    assert (n.type, n.kind, n.group) == ("notification", "message", None)


async def test_glance_memory_headline(mind):
    await mind.handle(glance("Chrome", ["12:30", "Best budget GPUs 2026 for gaming", "Another long enough line"]))
    last = mind.memory.recent_turns()[-1]
    assert (last.role, last.content) == ("event", "on screen in Chrome: Best budget GPUs 2026 for gaming")


async def test_glance_headline_fallbacks(mind):
    await mind.handle(glance("Clock", ["12:30", "OK"]))
    assert mind.memory.recent_turns()[-1].content == "on screen in Clock: 12:30"
    await mind.handle(glance("Reader", ["x" * 200]))
    assert mind.memory.recent_turns()[-1].content == "on screen in Reader: " + "x" * 120
    n = len(mind.memory.recent_turns())
    await mind.handle(glance("Empty", []))
    assert len(mind.memory.recent_turns()) == n  # nothing readable, nothing remembered
    assert mind.glance.app == "Empty"  # but it is still the latest screen


async def test_glance_is_kept_in_memory_only(mind, clock):
    assert mind.glance is None and mind.glance_at is None and mind.body is None
    g = glance("Chrome", ["hello world, this is long"], targets=["send"])
    await mind.handle(g)
    assert mind.glance is g and mind.glance_at == clock.now()
    clock.advance(timedelta(minutes=3))
    g2 = glance("Maps", ["somewhere"])
    await mind.handle(g2)
    assert mind.glance is g2 and mind.glance_at == clock.now()


async def test_glance_forgotten_when_the_screen_changes(mind, clock):
    async def seen(app="Chrome"):
        await mind.handle(glance(app, ["hello world, this is long"]))
        assert mind.glance is not None

    await seen()
    await mind.handle(AppOpened(package="chrome", label="Chrome"))  # the same app: still on screen
    assert mind.glance is not None
    await mind.handle(AppClosed(package="maps", label="Maps", duration_s=5))  # another app closed
    assert mind.glance is not None
    await mind.handle(AppOpened(package="maps", label="Maps"))  # switched away
    assert mind.glance is None and mind.glance_at is None

    await seen()
    await mind.handle(AppClosed(package="chrome", label="Chrome", duration_s=5))
    assert mind.glance is None and mind.glance_at is None

    await seen()
    await mind.handle(Screen(on=True))
    assert mind.glance is not None
    await mind.handle(Screen(on=False))
    assert mind.glance is None and mind.glance_at is None
    # a forgotten screen is not shown to her
    await mind.handle(UserTap())
    assert "On screen now" not in mind.thinker.llm.calls[-1][-1]["content"]


def test_glance_bump_once_per_5_min(ini, clock, db):
    from maya_brain.config import InitiativeConfig
    from maya_brain.initiative import Initiative

    ini.on_event(glance("Chrome"))
    assert ini.urge == pytest.approx(4) and ini.last_reason == "something new on screen in Chrome"
    clock.advance(timedelta(seconds=299))
    ini.on_event(glance("Maps"))
    assert ini.urge == pytest.approx(4)  # within 300 s, whatever the app
    assert ini.last_reason == "something new on screen in Chrome"
    # the limit persists
    reopened = Initiative(InitiativeConfig(), db, clock)
    reopened.on_event(glance("Maps"))
    assert reopened.urge == pytest.approx(4)
    clock.advance(timedelta(seconds=1))
    reopened.on_event(glance("Maps"))
    assert reopened.urge == pytest.approx(8) and reopened.last_reason == "something new on screen in Maps"
    reopened.on_event(glance("Maps"))
    assert reopened.urge == pytest.approx(8)  # the window restarted at the new bump


def test_glance_and_body_not_user_activity(ini, clock):
    ini.on_event(Screen(on=True))
    stamp = ini._last_activity
    clock.advance(timedelta(hours=4))
    ini.on_event(glance("Chrome"))
    ini.on_event(BodyEvent(doing="walk", where="floor", x=0.5, y=1.0))
    ini.on_event(BodyMomentEvent(kind="fell"))
    assert ini._last_activity == stamp
    assert ini.urge == pytest.approx(4 + 6) and "came back" not in ini.last_reason
    ini.on_event(UserTap())  # the real return still counts
    assert ini.urge == pytest.approx(10 + 30) and "came back after 4 hours" in ini.last_reason


async def test_mind_activity_tracking_ignores_glance_body_moment(mind, clock):
    await mind.handle(glance("Chrome"))
    await mind.handle(BodyEvent(doing="sit", where="platform", x=0.3, y=0.4))
    await mind.handle(BodyMomentEvent(kind="shooed", target="send"))
    assert mind._last_event is None
    await mind.handle(UserTap())
    stamp = mind._last_event
    clock.advance(timedelta(minutes=11))
    await mind.handle(glance("Maps", ["a fairly long line here"]))
    await mind.handle(BodyEvent(doing="idle", where="floor", x=0.5, y=1.0))
    await mind.handle(BodyMomentEvent(kind="fell"))
    assert mind._last_event == stamp


async def test_body_stored_no_memory(mind, sent):
    before = len(mind.memory.recent_turns())
    urge = mind.initiative.urge
    b = BodyEvent(doing="perch", where="platform", target=None, x=0.2, y=0.6)
    await mind.handle(b)
    assert mind.body is b
    assert len(mind.memory.recent_turns()) == before
    assert mind.initiative.urge == urge
    assert sent == []
    b2 = BodyEvent(doing="tease", where="target", target="send", x=0.8, y=0.9)
    await mind.handle(b2)
    assert mind.body is b2


async def test_moments_memory_and_bumps(mind):
    ini = mind.initiative
    await mind.handle(BodyMomentEvent(kind="fell"))
    last = mind.memory.recent_turns()[-1]
    assert (last.role, last.content) == ("event", "you fell when the screen moved under you")
    assert ini.urge == pytest.approx(6) and ini.last_reason == "you just fell because the screen moved under you"

    await mind.handle(BodyMomentEvent(kind="shooed", target="send"))
    last = mind.memory.recent_turns()[-1]
    assert (last.role, last.content) == ("event", "the user shooed you off the send button")
    assert ini.urge == pytest.approx(16)
    assert ini.last_reason == "the user just shooed you off the send button"

    await mind.handle(BodyMomentEvent(kind="shooed"))
    last = mind.memory.recent_turns()[-1]
    assert last.content == "the user shooed you away"
    assert ini.urge == pytest.approx(26)
    assert ini.last_reason == "the user just shooed you away"


async def test_falls_count_once_per_5_min(mind, clock, db):
    from maya_brain.config import InitiativeConfig
    from maya_brain.initiative import Initiative

    ini = mind.initiative

    def falls():
        return [t for t in mind.memory.recent_turns() if t.content == "you fell when the screen moved under you"]

    await mind.handle(BodyMomentEvent(kind="fell"))
    assert ini.urge == pytest.approx(6) and len(falls()) == 1
    clock.advance(timedelta(seconds=299))
    for _ in range(5):  # a scrolling feed drops her again and again
        await mind.handle(BodyMomentEvent(kind="fell", target="like"))
    assert ini.urge == pytest.approx(6) and len(falls()) == 1
    await mind.handle(BodyMomentEvent(kind="shooed", target="send"))  # shoos are not limited
    assert ini.urge == pytest.approx(16)
    # the limit persists
    assert not Initiative(InitiativeConfig(), db, clock).fall_counts()
    clock.advance(timedelta(seconds=1))
    assert Initiative(InitiativeConfig(), db, clock).fall_counts()
    await mind.handle(BodyMomentEvent(kind="fell"))
    assert ini.urge == pytest.approx(22) and len(falls()) == 2
    await mind.handle(BodyMomentEvent(kind="fell"))
    assert ini.urge == pytest.approx(22) and len(falls()) == 2  # the window restarted


def test_sim_glance_body_moment_commands():
    kind, ev = parse_command("/glance Chrome Best budget GPUs 2026 | Add to cart targets=send,like", NOW)
    assert kind == "event" and isinstance(ev, GlanceEvent)
    assert (ev.app, ev.package, ev.lines, ev.targets) == ("Chrome", "chrome", ["Best budget GPUs 2026", "Add to cart"], ["send", "like"])
    ev = parse_command("/glance Chrome one line only", NOW)[1]
    assert ev.lines == ["one line only"] and ev.targets == []
    ev = parse_command("/glance Chrome targets=send,bogus", NOW)[1]
    assert ev.lines == [] and ev.targets == ["send"]
    assert parse_command("/glance", NOW) == ("help", None)

    kind, ev = parse_command("/body perch platform", NOW)
    assert kind == "event" and isinstance(ev, BodyEvent)
    assert (ev.doing, ev.where, ev.target) == ("perch", "platform", None)
    ev = parse_command("/body tease target send", NOW)[1]
    assert (ev.doing, ev.where, ev.target) == ("tease", "target", "send")
    ev = parse_command("/body walk", NOW)[1]
    assert (ev.doing, ev.where, ev.target) == ("walk", "floor", None)
    assert parse_command("/body", NOW) == ("help", None)

    kind, ev = parse_command("/moment fell", NOW)
    assert kind == "event" and isinstance(ev, BodyMomentEvent) and (ev.kind, ev.target) == ("fell", None)
    ev = parse_command("/moment shooed send", NOW)[1]
    assert (ev.kind, ev.target) == ("shooed", "send")
    assert parse_command("/moment", NOW) == ("help", None)
    assert parse_command("/moment exploded", NOW) == ("help", None)


async def test_sim_runs_screen_commands_and_prints_moves(mind, clock):
    lines = iter(["/glance Chrome Best budget GPUs 2026 for gaming targets=send", "/body sit platform", "/moment shooed send", "/quit"])
    out = []
    await run_sim(mind, clock, lambda: next(lines), out.append)
    assert not [o for o in out if o.startswith("[error")]
    assert mind.glance.app == "Chrome" and mind.body.doing == "sit"
    assert mind.memory.recent_turns()[-1].content == "the user shooed you off the send button"

    # a `move` from the brain reaches the sink run_sim installed
    out = []
    await run_sim(mind, clock, iter(["/quit"]).__next__, out.append)
    await mind._emit(Move(intent="sit_on", target="send"))
    await mind._emit(Move(intent="stay"))
    assert out == ["[move] sit_on send", "[move] stay"]
