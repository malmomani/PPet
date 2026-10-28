import asyncio
import json
import logging
import time
from datetime import timedelta

import pytest

from maya_brain.config import Config, InitiativeConfig
from maya_brain.initiative import Initiative
from maya_brain.llm import FakeLLM
from maya_brain.mind import Mind, summarize
from maya_brain.protocol import (
    ACT_KINDS,
    RISKY_TAGS,
    RISKY_WORDS,
    Act,
    ActResultEvent,
    AppClosed,
    AppOpened,
    GlanceEvent,
    Look,
    ProtocolError,
    Say,
    Screen,
    SightEvent,
    SightFailedEvent,
    UserDrag,
    UserMessage,
    UserShush,
    is_risky,
    parse_act,
    parse_event,
)
from maya_brain.sim import parse_command, run_sim
from maya_brain.thinker import Thought
from maya_brain.vision import FakeDescriber

IMG = "aGVsbG8="  # any base64; never looked at by the brain itself


def reply(say="hehe", speak=True, **extra):
    return json.dumps({"thought": "", "speak": speak, "say": say, "animation": "idle", **extra})


def script(mind, *replies):
    mind.thinker.llm.replies = list(replies)


def says(sent):
    return [a.text for a in sent if isinstance(a, Say)]


def acts(sent):
    return [a for a in sent if isinstance(a, Act)]


def looks(sent):
    return [a for a in sent if isinstance(a, Look)]


def turns(mind):
    return [t.content for t in mind.memory.recent_turns()]


def last_user_prompt(mind):
    return mind.thinker.llm.calls[-1][-1]["content"]


def sight(app="Instagram", reason="auto", image=IMG):
    return SightEvent(package=app.lower(), app=app, reason=reason, image_b64=image)


@pytest.fixture
def seer(db, clock, sent):
    """A Mind with a fake describer (`seer.describer.replies`) and a fake LLM."""
    from maya_brain.db import set_state

    set_state(db, "reflection", {"last_date": clock.now().date().isoformat(), "last_at": clock.now().isoformat()})

    async def sink(action):
        sent.append(action)

    return Mind(Config(), FakeLLM(), db, clock, sink, describer=FakeDescriber(["a cat sleeping on a red sofa"]))


def friend(mind, tier="friend"):
    mind.emotion._closeness = {"stranger": 10.0, "friend": 50.0, "close": 80.0}[tier]


# ---- protocol ----


def test_parse_sight_caps():
    base = {"type": "sight", "package": "com.instagram.android", "app": "Instagram", "reason": "auto"}
    e = parse_event({**base, "image_b64": "A" * 600_000})
    assert isinstance(e, SightEvent) and e.type == "sight" and e.reason == "auto" and e.id and e.ts.tzinfo
    assert parse_event({**base, "reason": "asked", "image_b64": IMG}).reason == "asked"
    for bad in ({"image_b64": "A" * 600_001}, {"reason": "bored", "image_b64": IMG}, {}, {"image_b64": 5}):
        with pytest.raises(ProtocolError):
            parse_event({**base, **bad})
    with pytest.raises(ProtocolError) as exc:
        parse_event({**base, "image_b64": "SECRET" * 100_001})
    assert str(exc.value) == "sight image too large"  # never echoes the picture
    with pytest.raises(ProtocolError):
        parse_event({**base, "app": "", "image_b64": IMG})

    f = parse_event({"type": "sight_failed", "reason": "blocked"})
    assert isinstance(f, SightFailedEvent) and f.reason == "blocked"
    assert parse_event({"type": "sight_failed", "reason": "error"}).reason == "error"
    assert parse_event({"type": "sight_failed", "reason": "weird"}).reason == "error"
    assert parse_event({"type": "sight_failed"}).reason == "error"

    r = parse_event({"type": "act_result", "act_id": "a1", "status": "done"})
    assert isinstance(r, ActResultEvent) and (r.act_id, r.status, r.detail) == ("a1", "done", None)
    for status in ("done", "declined", "timeout", "failed", "blocked"):
        assert parse_event({"type": "act_result", "act_id": "a", "status": status}).status == status
    assert parse_event({"type": "act_result", "act_id": "a", "status": "failed", "detail": "x" * 250}).detail == "x" * 200
    assert parse_event({"type": "act_result", "act_id": "a", "status": "failed", "detail": 5}).detail is None
    with pytest.raises(ProtocolError):
        parse_event({"type": "act_result", "act_id": "a", "status": "maybe"})
    with pytest.raises(ProtocolError):
        parse_event({"type": "act_result", "status": "done"})


def test_parse_act_per_kind():
    assert ACT_KINDS == ("open_app", "media", "timer", "alarm", "volume", "web_search", "scroll", "press", "type")

    def ok(**kw):
        act = parse_act({"kind": kw.pop("kind"), **kw})
        assert isinstance(act, Act) and act.type == "act" and act.id
        return act

    def bad(**kw):
        with pytest.raises(ProtocolError):
            parse_act(kw)

    assert ok(kind="open_app", app="YouTube").app == "YouTube"
    ok(kind="open_app", app="x" * 100)
    bad(kind="open_app", app="")
    bad(kind="open_app", app="x" * 101)
    bad(kind="open_app")

    for key in ("play_pause", "next", "previous"):
        assert ok(kind="media", key=key).key == key
    bad(kind="media", key="stop")

    t = ok(kind="timer", seconds=600, label="tea")
    assert (t.seconds, t.label) == (600, "tea")
    ok(kind="timer", seconds=5)
    ok(kind="timer", seconds=86_400, label="x" * 60)
    bad(kind="timer", seconds=4)
    bad(kind="timer", seconds=86_401)
    bad(kind="timer", seconds=60, label="x" * 61)
    bad(kind="timer", seconds=True)
    bad(kind="timer")

    a = ok(kind="alarm", hour=7, minute=30)
    assert (a.hour, a.minute, a.label) == (7, 30, None)
    ok(kind="alarm", hour=0, minute=0, label="x" * 60)
    ok(kind="alarm", hour=23, minute=59)
    bad(kind="alarm", hour=24, minute=0)
    bad(kind="alarm", hour=7, minute=60)
    bad(kind="alarm", hour=7)
    bad(kind="alarm", hour=7, minute=0, label="x" * 61)

    for d in ("up", "down", "mute"):
        assert ok(kind="volume", dir=d).dir == d
    bad(kind="volume", dir="left")
    for d in ("up", "down"):
        assert ok(kind="scroll", dir=d).dir == d
    bad(kind="scroll", dir="mute")

    assert ok(kind="web_search", query="cat videos").query == "cat videos"
    ok(kind="web_search", query="q" * 200)
    bad(kind="web_search", query="")
    bad(kind="web_search", query="q" * 201)

    assert ok(kind="press", target="Send").target == "Send"
    ok(kind="press", target="x" * 40)
    bad(kind="press")
    bad(kind="press", target="")
    bad(kind="press", target="x" * 41)

    ty = ok(kind="type", text="hello", into="Search")
    assert (ty.text, ty.into) == ("hello", "Search")
    ok(kind="type", text="x" * 500, into="x" * 40)
    bad(kind="type", text="")
    bad(kind="type", text="x" * 501)
    bad(kind="type", text="hi", into="x" * 41)

    bad(kind="teleport")
    bad()

    # blank is not a value; nothing is trimmed
    for kind, field in (("open_app", "app"), ("web_search", "query"), ("press", "target"), ("type", "text")):
        bad(kind=kind, **{field: "   "})
        assert getattr(ok(kind=kind, **{field: " x "}), field) == " x "
    # lengths count UTF-16 units, like the phone: an emoji is two
    ok(kind="press", target="😀" * 20)
    bad(kind="press", target="😀" * 20 + "x")
    ok(kind="type", text="😀" * 250)
    bad(kind="type", text="😀" * 250 + "x")
    bad(kind="open_app", app="😀" * 50 + "x")
    bad(kind="web_search", query="😀" * 100 + "x")
    bad(kind="timer", seconds=60, label="😀" * 30 + "x")
    bad(kind="type", text="hi", into="😀" * 20 + "x")

    # fields of other kinds are ignored, so they never reach the phone
    o = ok(kind="open_app", app="Maps", seconds=3, text="x" * 900, dir="sideways")
    assert (o.seconds, o.text, o.dir) == (None, None, None)
    d = o.model_dump(mode="json")
    assert d["type"] == "act" and d["kind"] == "open_app" and d["app"] == "Maps"


def test_is_risky_en_ar():
    assert RISKY_TAGS == ("send", "share", "comment", "follow")
    assert {"send", "reply", "uninstall", "إرسال", "ارسال", "تأكيد"} <= set(RISKY_WORDS)

    def press(target):
        return Act(kind="press", target=target)

    for tag in ("send", "Share", " comment ", "FOLLOW"):
        assert is_risky(press(tag)), tag
    for target in ("like", "play", "search", "Next", "Sender info", "Play all"):
        assert not is_risky(press(target)), target
    for label in ("Buy now", "Pay", "Delete chat", "Post!", "subscribe", "Confirm order", "Reply all"):
        assert is_risky(press(label)), label
    assert not is_risky(press("Payment info"))  # whole words only
    assert not is_risky(press("Deleted items"))
    # Arabic: whole words, marks stripped, the leading article "ال" counts as no article
    assert is_risky(press("إرسال"))
    assert is_risky(press("الإرسال"))
    assert is_risky(press("زر حذف الرسالة"))
    assert is_risky(press("تأكيد"))
    assert is_risky(press("تَأْكِيد"))  # with harakat
    assert is_risky(press("المتابعة"))
    assert not is_risky(press("الرسائل"))
    # tatweel is ignored; "and" (و), bi- (ب) and li- (ل, لل) before the article count too
    for target in ("إرسـال", "تأكـيـد", "والإرسال", "بالدفع", "للدفع", "وللدفع", "وبالشراء", "لالحذف"):
        assert is_risky(press(target)), target
    for target in ("للقراءة", "والزر", "وحذفت", "بحذف", "ال", "وال", "لل"):
        assert not is_risky(press(target)), target
    # any type is risky; other kinds never are
    assert is_risky(Act(kind="type", text="hi"))
    for act in (
        Act(kind="open_app", app="Send Anywhere"),
        Act(kind="web_search", query="buy shoes"),
        Act(kind="timer", seconds=60, label="send"),
        Act(kind="media", key="next"),
        Act(kind="scroll", dir="down"),
    ):
        assert not is_risky(act), act.kind


def test_old_types_still_parse():
    assert parse_event({"type": "screen", "on": True}).on is True
    assert parse_event({"type": "user_message", "text": "hi"}).text == "hi"
    g = parse_event({"type": "glance", "package": "p", "app": "A", "lines": ["x"], "targets": ["send"]})
    assert g.targets == ["send"]
    assert parse_event({"type": "body", "doing": "sit"}).doing == "sit"
    assert parse_event({"type": "teleport"}) is None
    assert '"type":"look"' in Look().model_dump_json()


# ---- sight ----


async def test_sight_described_outside_lock(seer, sent):
    gate = asyncio.Event()

    class SlowDescriber:
        async def describe(self, image_b64):
            await gate.wait()
            return "a mountain lake at sunset"

    seer.describer = SlowDescriber()
    event = sight()
    task = asyncio.create_task(seer.handle_sight(event))
    await asyncio.sleep(0)
    await asyncio.wait_for(seer.handle(UserMessage(text="hi")), timeout=1)  # answered while vision is busy
    assert says(sent) == ["hehe"]
    assert seer.sight is None
    gate.set()
    await task
    assert seer.sight.app == "Instagram" and seer.sight.description == "a mountain lake at sunset"
    assert event.image_b64 == ""  # the picture is dropped right after describing


async def test_vision_failure_is_silent(seer, sent, caplog):
    seer.describer = FakeDescriber(None)
    before = seer.initiative.urge
    with caplog.at_level(logging.DEBUG):
        await seer.handle_sight(sight(image="SECRETPIXELS"))
    assert seer.sight is None and sent == [] and turns(seer) == [] and seer.initiative.urge == before

    class Broken:
        async def describe(self, image_b64):
            raise RuntimeError("SECRETPIXELS")

    seer.describer = Broken()
    await seer.handle_sight(sight(image="SECRETPIXELS"))
    seer.describer = None  # vision switched off
    await seer.handle_sight(sight(image="SECRETPIXELS"))
    assert seer.sight is None and sent == [] and turns(seer) == []
    assert "SECRETPIXELS" not in caplog.text


async def test_sight_memory_and_urge_once_per_5_min(seer, sent, clock):
    seer.describer = FakeDescriber(["word " * 80])
    await seer.handle_sight(sight("Instagram"))
    line = turns(seer)[-1]
    assert line.startswith("you looked at Instagram: word word") and len(line) <= 160
    assert seer.initiative.urge == 5 and seer.initiative.last_reason == "you just saw something on screen in Instagram"
    assert sent == []  # an auto look is no reason to answer

    clock.advance(timedelta(seconds=299))
    await seer.handle_sight(sight("Pinterest"))
    assert seer.initiative.urge == 5 and turns(seer)[-1].startswith("you looked at Pinterest:")
    clock.advance(timedelta(seconds=1))
    await seer.handle_sight(sight("Pinterest"))
    assert seer.initiative.urge == 10
    # the gap is persisted
    again = Initiative(InitiativeConfig(), seer.initiative.db, clock)
    assert again.sighted("X") is False
    clock.advance(timedelta(seconds=300))
    assert again.sighted("X") is True and again.urge == 15


async def test_asked_sight_direct_response(seer, sent):
    script(seer, reply(say="what a sleepy cat!"))
    await seer.handle_sight(sight(reason="asked"))
    assert says(sent) == ["what a sleepy cat!"]
    prompt = last_user_prompt(seer)
    assert "Now: You looked at the screen as the user asked. You saw: a cat sleeping on a red sofa" in prompt
    assert seer.initiative.urge == 0
    # a silent reply is not allowed for an asked look: she always answers
    sent.clear()
    script(seer, reply(say="", speak=False), reply(say="cute"))
    await seer.handle_sight(sight(reason="asked"))
    assert says(sent) == ["cute"]
    # vision failed on an asked look: she says she couldn't see it
    sent.clear()
    seer.describer = FakeDescriber(None)
    script(seer, reply(say="hmm, it's blurry"))
    await seer.handle_sight(sight(reason="asked"))
    assert says(sent) == ["hmm, it's blurry"]
    assert "Now: You tried to look at the screen but couldn't see it." in last_user_prompt(seer)


async def test_sight_failed_response(seer, sent):
    script(seer, reply(say="it won't let me look"))
    await seer.handle(SightFailedEvent(reason="blocked"))
    assert says(sent) == ["it won't let me look"]
    assert "Now: You tried to look at the screen but couldn't see it." in last_user_prompt(seer)


async def test_sight_in_prompt_age_and_app(seer, clock):
    await seer.handle_sight(sight("Instagram"))
    line = "You saw on screen (Instagram): a cat sleeping on a red sofa"
    await seer.handle(UserMessage(text="hi"))
    assert line in last_user_prompt(seer)
    # same app as the latest glance: still shown
    await seer.handle(GlanceEvent(package="instagram", app="Instagram", lines=["nice"]))
    await seer.handle(UserMessage(text="hi"))
    assert line in last_user_prompt(seer)
    # another app's glance: not shown (packages are compared, not labels)
    await seer.handle(GlanceEvent(package="reddit", app="Instagram", lines=["text"]))
    await seer.handle(UserMessage(text="hi"))
    assert "You saw on screen" not in last_user_prompt(seer)
    # older than 10 minutes: not shown
    await seer.handle(GlanceEvent(package="instagram", app="Instagram", lines=["nice"]))
    clock.advance(timedelta(minutes=10, seconds=1))
    await seer.handle(UserMessage(text="hi"))
    assert "You saw on screen" not in last_user_prompt(seer)
    # a fresh sight; then the user leaves for another app or turns the screen off: forgotten
    await seer.handle_sight(sight("Instagram"))
    await seer.handle(AppOpened(package="com.whatsapp", label="WhatsApp"))
    assert seer.sight is None
    await seer.handle_sight(sight("Instagram"))
    await seer.handle(Screen(on=False))
    assert seer.sight is None


async def test_sight_compares_current_app_package_without_glance(seer):
    await seer.handle(AppOpened(package="instagram", label="Instagram"))
    await seer.handle_sight(sight("Instagram"))
    await seer.handle(UserMessage(text="hi"))
    assert "You saw on screen (Instagram)" in last_user_prompt(seer)
    seer.initiative._app["package"] = "com.other"  # the tracked app no longer matches
    await seer.handle(UserMessage(text="hi"))
    assert "You saw on screen" not in last_user_prompt(seer)


async def test_screen_change_while_describing_drops_the_sight(seer, sent):
    gate = asyncio.Event()

    class SlowDescriber:
        async def describe(self, image_b64):
            await gate.wait()
            return "a cat on a sofa"

    seer.describer = SlowDescriber()
    await seer.handle(AppOpened(package="instagram", label="Instagram"))
    task = asyncio.create_task(seer.handle_sight(sight("Instagram")))
    await asyncio.sleep(0)
    await seer.handle(AppOpened(package="com.whatsapp", label="WhatsApp"))
    gate.set()
    await task
    assert seer.sight is None  # stale: the user moved on
    assert turns(seer)[-1] == "you looked at Instagram: a cat on a sofa"  # still remembered
    await seer.handle(UserMessage(text="hi"))
    assert "You saw on screen" not in last_user_prompt(seer)

    # an asked look is still answered even when the screen changed meanwhile
    for change in (AppClosed(package="com.whatsapp", label="WhatsApp", duration_s=5), Screen(on=False)):
        gate.clear()
        sent.clear()
        script(seer, reply(say="cute!"))
        task = asyncio.create_task(seer.handle_sight(sight("Instagram", reason="asked")))
        await asyncio.sleep(0)
        await seer.handle(change)
        gate.set()
        await task
        assert seer.sight is None and says(sent) == ["cute!"]
        await seer.handle(Screen(on=True))

    # the same app opening again is no change
    await seer.handle(AppOpened(package="instagram", label="Instagram"))
    gate.clear()
    task = asyncio.create_task(seer.handle_sight(sight("Instagram")))
    await asyncio.sleep(0)
    await seer.handle(AppOpened(package="instagram", label="Instagram"))
    gate.set()
    await task
    assert seer.sight is not None


async def test_describe_times_out(seer, sent):
    class Stuck:
        async def describe(self, image_b64):
            await asyncio.sleep(3600)

    seer.cfg.llm.timeout_s = 0.01
    seer.describer = Stuck()
    script(seer, reply(say="it's all blurry"))
    await asyncio.wait_for(seer.handle_sight(sight(reason="asked")), timeout=2)
    assert says(sent) == ["it's all blurry"] and seer.sight is None


# ---- look and act from thoughts ----


async def test_thought_look_emits_look(seer, sent):
    script(seer, reply(say="show me!", look=True))
    await seer.handle(UserMessage(text="look at this"))
    assert says(sent) == ["show me!"]
    assert len(looks(sent)) == 1
    assert sent.index(looks(sent)[0]) > max(i for i, a in enumerate(sent) if isinstance(a, Say))
    # the answer to the look (an asked sight) never looks again: no loop
    sent.clear()
    script(seer, reply(say="cute cat", look=True))
    await seer.handle_sight(sight(reason="asked"))
    assert says(sent) == ["cute cat"] and looks(sent) == []
    # her own thoughts don't look either: the asked sight would make her talk unprompted
    script(seer, reply(say="whee", look=True))
    await seer.handle(UserDrag(thrown=True))
    assert says(sent) == ["cute cat", "whee"] and looks(sent) == []
    # junk is false
    assert Thought.model_validate({"look": "yes"}).look is False
    assert Thought.model_validate({"look": True}).look is True
    assert Thought.model_validate({}).look is False


async def own_act(mind, act):
    """One own-initiative thought carrying `act` (she was thrown: not a reply); returns the acts it emitted."""
    script(mind, reply(say="", speak=False, act=act))
    out = []
    mind.set_sink(lambda a: _collect(out, a))
    await mind.handle(UserDrag(thrown=True))
    return acts(out)


async def _collect(out, action):
    out.append(action)


TIMER = {"kind": "timer", "seconds": 600, "label": "tea"}


async def test_own_initiative_act_limits(seer, clock):
    assert await own_act(seer, TIMER) == []  # a stranger never acts on her own
    friend(seer)
    got = await own_act(seer, TIMER)
    assert len(got) == 1 and (got[0].kind, got[0].seconds, got[0].label) == ("timer", 600, "tea")
    friend(seer, "close")
    assert len(await own_act(seer, {"kind": "media", "key": "next"})) == 1

    await seer.handle(UserShush())
    assert await own_act(seer, TIMER) == []
    clock.advance(timedelta(minutes=61))  # shush over
    clock.set(clock.now().replace(hour=23, minute=45))  # quiet hours
    assert await own_act(seer, TIMER) == []
    clock.set(clock.now() + timedelta(hours=10))  # 09:45 the next day: fine again
    start = clock.now()
    for i in range(10):
        assert len(await own_act(seer, TIMER)) == 1, i
        clock.advance(timedelta(minutes=1))
    assert await own_act(seer, TIMER) == []  # 10 in the rolling hour
    # persisted
    assert Initiative(InitiativeConfig(), seer.initiative.db, clock).act_ready() is False
    clock.set(start + timedelta(hours=1, seconds=1))  # the first one drops out of the hour
    assert len(await own_act(seer, TIMER)) == 1
    assert len(seer.initiative._acts) == 10  # only the last hour is kept


async def test_own_act_needs_someone_to_see_it(seer, clock):
    friend(seer)
    seer.set_connected(False)
    assert await own_act(seer, TIMER) == []
    seer.set_connected(True)
    await seer.handle(Screen(on=False))
    assert await own_act(seer, TIMER) == []
    await seer.handle(Screen(on=True))
    assert len(await own_act(seer, TIMER)) == 1


async def test_own_initiative_never_risky(seer):
    friend(seer, "close")
    assert await own_act(seer, {"kind": "press", "target": "Send"}) == []
    assert await own_act(seer, {"kind": "press", "target": "Buy now"}) == []
    assert await own_act(seer, {"kind": "type", "text": "hello"}) == []
    assert len(await own_act(seer, {"kind": "press", "target": "like"})) == 1
    assert len(await own_act(seer, {"kind": "scroll", "dir": "down"})) == 1
    # the rest of the thought is kept
    seer.initiative._set_urge(100)
    script(seer, reply(say="sending it!", act={"kind": "press", "target": "Send"}))
    out = []
    seer.set_sink(lambda a: _collect(out, a))
    await seer._maybe_initiate()
    assert says(out) == ["sending it!"] and acts(out) == []
    # invalid act: dropped, thought kept
    t = Thought.model_validate({"say": "ok", "act": {"kind": "timer", "seconds": 1}})
    assert t.act is None and t.say == "ok"
    assert Thought.model_validate({"act": "open youtube"}).act is None
    assert Thought.model_validate({"act": {"kind": ["open_app"], "app": "x"}}).act is None
    assert Thought.model_validate({"act": {"kind": None}}).act is None
    assert Thought.model_validate({"act": {"kind": "open_app", "app": "YouTube"}}).act.app == "YouTube"


async def test_user_asked_act_passes_and_risky_allowed(seer, sent, clock):
    # a stranger, in quiet hours, shushed, over the hourly limit: a reply to the user still acts
    await seer.handle(UserShush())
    clock.set(clock.now().replace(hour=23, minute=45))
    for _ in range(10):
        seer.initiative.acted()
    script(seer, reply(say="okay!", act={"kind": "press", "target": "Send"}))
    await seer.handle(UserMessage(text="press send for me"))
    script(seer, reply(say="typing", act={"kind": "type", "text": "on my way"}))
    await seer.handle(UserMessage(text="tell him I'm on my way"))
    got = acts(sent)
    assert [(a.kind, a.target, a.text) for a in got] == [("press", "Send", None), ("type", None, "on my way")]
    assert len(seer.initiative._acts) == 10  # replies to the user don't count
    # an asked look's answer counts as a reply to the user too
    script(seer, reply(say="playing", act={"kind": "media", "key": "play_pause"}))
    await seer.handle_sight(sight(reason="asked"))
    assert acts(sent)[-1].key == "play_pause"
    # the act goes out after the say
    assert isinstance(sent[sent.index(got[0]) - 1], Say)


async def test_act_result_memory_wording(seer, sent):
    assert summarize(Act(kind="open_app", app="YouTube")) == "open YouTube"
    assert summarize(Act(kind="timer", seconds=600)) == "set a 10-minute timer"
    assert summarize(Act(kind="timer", seconds=7200, label="pizza")) == 'set a 2-hour timer for "pizza"'
    assert summarize(Act(kind="timer", seconds=45)) == "set a 45-second timer"
    assert summarize(Act(kind="alarm", hour=7, minute=5)) == "set an alarm for 07:05"
    assert summarize(Act(kind="media", key="next")) == "skip to the next song"
    assert summarize(Act(kind="media", key="play_pause")) == "play or pause the music"
    assert summarize(Act(kind="media", key="previous")) == "go back to the previous song"
    assert summarize(Act(kind="volume", dir="up")) == "turn the volume up"
    assert summarize(Act(kind="volume", dir="mute")) == "mute the sound"
    assert summarize(Act(kind="web_search", query="cats")) == 'search the web for "cats"'
    assert summarize(Act(kind="scroll", dir="down")) == "scroll down"
    assert summarize(Act(kind="press", target="Send")) == "press Send"
    assert summarize(Act(kind="type", text="x" * 50)) == 'type "' + "x" * 39 + '…"'
    assert summarize(Act(kind="type", text="hi", into="Search")) == 'type "hi" into Search'

    async def answer(act, status, detail=None):
        script(seer, reply(say="ok", act=act))
        await seer.handle(UserMessage(text="do it"))
        act_id = acts(sent)[-1].id
        await seer.handle(ActResultEvent(act_id=act_id, status=status, detail=detail))
        return turns(seer)[-1]

    assert await answer({"kind": "open_app", "app": "YouTube"}, "done") == "you open YouTube"
    assert await answer({"kind": "press", "target": "Send"}, "declined") == "the user said no to press Send"
    assert (
        await answer({"kind": "type", "text": "hi"}, "timeout")
        == "the user didn't answer when you asked to type \"hi\""
    )
    assert await answer({"kind": "press", "target": "Like"}, "failed", "not on screen") == (
        "you couldn't press Like (not on screen)"
    )
    assert await answer({"kind": "timer", "seconds": 600}, "blocked", "off") == "you couldn't set a 10-minute timer (off)"
    assert await answer({"kind": "scroll", "dir": "up"}, "failed") == "you couldn't scroll up"
    urge = seer.initiative.urge
    n = len(turns(seer))
    await seer.handle(ActResultEvent(act_id="unknown", status="done"))  # not hers: ignored
    assert len(turns(seer)) == n and seer.initiative.urge == urge


def test_main_passes_describer_to_mind(tmp_path, monkeypatch):
    import maya_brain.__main__ as entry
    from maya_brain.llm import OllamaLLM
    from maya_brain.vision import OllamaDescriber

    async def no_warm_up(self):
        return None

    seen = {}
    monkeypatch.setattr(OllamaLLM, "warm_up", no_warm_up)
    monkeypatch.setattr(entry, "Mind", lambda *a, **kw: seen.update(kw) or object())
    monkeypatch.setattr(entry, "create_app", lambda cfg, mind: object())
    monkeypatch.setattr(entry.uvicorn, "run", lambda app, **kw: None)
    cfg = tmp_path / "m.toml"
    cfg.write_text(f'db_path = "{tmp_path / "m.db"}"\n[server]\ntoken = "a-long-enough-secret"\n[voice]\nenabled = false\n')
    assert entry.main(["--config", str(cfg)]) == 0
    assert isinstance(seen["describer"], OllamaDescriber)


class Gated:
    """A describer that waits until the test opens its gate; records starts and cancels (by image marker)."""

    def __init__(self):
        self.started, self.cancelled, self.gates = [], [], {}

    async def describe(self, image_b64):
        self.started.append(image_b64)
        gate = self.gates[image_b64] = asyncio.Event()
        try:
            await gate.wait()
        except asyncio.CancelledError:
            self.cancelled.append(image_b64)
            raise
        return f"seen {image_b64}"


def eventually(cond, what):
    for _ in range(300):
        if cond():
            return
        time.sleep(0.01)
    raise AssertionError(f"timed out waiting for {what}")


@pytest.fixture
def gw(clock):
    """A gateway around a Mind with a Gated describer; yields (TestClient, mind, describer, llm)."""
    import warnings

    with warnings.catch_warnings():
        warnings.filterwarnings("ignore", message=".*starlette.testclient.*")
        from fastapi.testclient import TestClient

    from maya_brain.db import open_db
    from maya_brain.gateway import create_app

    cfg = Config()
    cfg.server.token = "t"
    conn = open_db(":memory:")
    describer = Gated()
    llm = FakeLLM()
    mind = Mind(cfg, llm, conn, clock, describer=describer)
    with TestClient(create_app(cfg, mind)) as c:
        yield c, mind, describer, llm
    conn.close()


def raw_sight(marker, reason="auto"):
    return {"type": "sight", "package": "p", "app": "Gallery", "reason": reason, "image_b64": marker}


def test_gateway_runs_one_sight_at_a_time(gw, caplog):
    c, mind, describer, _ = gw
    with caplog.at_level(logging.DEBUG), c.websocket_connect("/ws?token=t") as ws:
        ws.send_json(raw_sight("SECRETPIXELS"))
        ws.send_json(raw_sight("SECRETPIXELS2"))  # still describing the first: dropped
        ws.send_json({"type": "user_message", "text": "hi"})  # chat is not blocked by vision
        got = [ws.receive_json() for _ in range(4)]
        assert any(m["type"] == "say" for m in got)
        assert describer.started == ["SECRETPIXELS"]
        c.portal.call(describer.gates["SECRETPIXELS"].set)
        eventually(lambda: mind.sight is not None, "the sight")
    assert mind.sight.description == "seen SECRETPIXELS"
    assert "SECRETPIXELS" not in caplog.text
    assert any("sight" in r.getMessage() and "dropp" in r.getMessage() for r in caplog.records)


def test_gateway_asked_sight_replaces_auto_and_is_always_answered(gw):
    c, mind, describer, llm = gw
    with c.websocket_connect("/ws?token=t") as ws:
        ws.send_json(raw_sight("AUTO"))
        eventually(lambda: describer.started == ["AUTO"], "the auto describe")
        ws.send_json(raw_sight("ASKED", "asked"))  # the user is waiting: the auto one makes way
        eventually(lambda: describer.started == ["AUTO", "ASKED"], "the asked describe")
        assert describer.cancelled == ["AUTO"]
        ws.send_json(raw_sight("ASKED2", "asked"))  # can't describe two asked looks at once
        eventually(lambda: llm.calls, "an answer")
        assert "Now: You tried to look at the screen but couldn't see it." in llm.calls[-1][-1]["content"]
        assert describer.started == ["AUTO", "ASKED"]  # not described, but answered
        c.portal.call(describer.gates["ASKED"].set)
        eventually(lambda: len(llm.calls) == 2, "the asked look's answer")
        assert "You saw: seen ASKED" in llm.calls[-1][-1]["content"]
    assert mind.sight.description == "seen ASKED"


def test_gateway_cancels_sight_when_phone_goes(gw):
    c, mind, describer, _ = gw
    with c.websocket_connect("/ws?token=t") as ws:
        ws.send_json(raw_sight("BYE"))
        eventually(lambda: describer.started == ["BYE"], "the describe")
    eventually(lambda: describer.cancelled == ["BYE"], "the cancel")
    assert mind.sight is None


# ---- simulator ----


def test_parse_sim_sight_and_act_result(clock):
    now = clock.now()
    assert parse_command("/sight Instagram a cat on a sofa", now) == ("sight", ("Instagram", "a cat on a sofa"))
    assert parse_command("/act_result done", now) == ("act_result", ("done", None))
    assert parse_command("/act_result failed not on screen", now) == ("act_result", ("failed", "not on screen"))
    for line in ("/sight", "/sight Instagram", "/act_result", "/act_result maybe"):
        assert parse_command(line, now) == ("help", None), line


async def test_sim_sight_act_result(seer, clock):
    friend(seer)
    lines = iter([
        "look at this",
        "/sight Instagram a cat on a sofa",
        "set a tea timer",
        "/act_result done",
        "/act_result failed",
        "/quit",
    ])
    script(
        seer,
        reply(say="show me!", look=True),
        reply(say="cute cat!"),
        reply(say="on it", act=TIMER),
        reply(say="ok"),
    )
    out = []
    await run_sim(seer, clock, lambda: next(lines), out.append)
    text = "\n".join(out)
    assert "[look]" in text
    assert "Maya: cute cat!" in text
    assert "[act] timer seconds=600 label=tea" in text
    assert seer.describer.calls == []  # /sight bypasses vision
    t = turns(seer)
    assert "you looked at Instagram: a cat on a sofa" in t
    assert 'you set a 10-minute timer for "tea"' in t
    assert "(no act to answer)" in text  # each act is answered once


async def test_sim_sight_uses_the_open_app(seer, clock):
    lines = iter(["/app Insta", "/sight Instagram a cat", "hi", "/quit"])
    await run_sim(seer, clock, lambda: next(lines), [].append)
    assert seer.sight.package == "insta"  # the package /app opened
    assert "You saw on screen (Instagram): a cat" in last_user_prompt(seer)


async def test_look_and_act_offered_in_prompt(thinker):
    from maya_brain.thinker import ACT_RULE, LOOK_RULE, Context

    for trigger in ("user_message", "initiative", "body", "look"):
        system = (await thinker.build_messages(Context(trigger, "d")))[0]["content"]
        assert LOOK_RULE in system and ACT_RULE in system
    assert ACT_RULE.count("\n") == 0 and "confirmed by the user" in ACT_RULE
    assert "only when the user asks you to look" in LOOK_RULE
    for kind in ACT_KINDS:
        assert f"{kind} {{" in ACT_RULE
