import json
from datetime import datetime, timedelta

import pytest

from maya_brain.config import InitiativeConfig
from maya_brain.initiative import Initiative
from maya_brain.protocol import BodyEvent, GlanceEvent, Move, Screen, UserMessage, UserShush
from maya_brain.thinker import SELF_MODEL, Context, Thought, describe_body

TEASE_LINE = '- sit_on/poke tease the user and need "target": one of send, like.'


def glance(app="WhatsApp", lines=("hello there, how are you",), targets=("send", "like")):
    return GlanceEvent(package=app.lower(), app=app, lines=list(lines), targets=list(targets))


def reply(move=None, speak=True, say="hehe", **extra):
    return json.dumps({"thought": "", "speak": speak, "say": say, "animation": "idle", "move": move, **extra})


def moves(sent):
    return [a for a in sent if isinstance(a, Move)]


async def prime(mind, tier="friend", mood="happy", g=None):
    """Everything a tease needs: a fresh glance with targets, friend tier, a good mood, a connected phone."""
    mind.emotion._closeness = {"stranger": 10.0, "friend": 50.0, "close": 80.0}[tier]
    mind.emotion.set_mood(mood, 0.8)
    await mind.handle(g or glance())


def script(mind, *replies):
    mind.thinker.llm.replies = list(replies)


def last_user_prompt(mind):
    return mind.thinker.llm.calls[-1][-1]["content"]


def last_system_prompt(mind):
    return mind.thinker.llm.calls[-1][0]["content"]


# ---- thinker ----


async def test_self_model_in_prompt(thinker):
    assert SELF_MODEL == (
        "You have a small body: you live on the user's phone screen as a little pixel girl. "
        "You can walk, jump onto buttons and boxes, sit, nap and peek from the screen edges; "
        "the user can tap you, pick you up and throw you. You feel it when things move under you. "
        "Your body is yours: use it to show what you feel and want."
    )
    sys = (await thinker.build_messages(Context("initiative", "bored")))[0]["content"]
    persona = thinker.persona.render()
    assert sys.startswith(persona + "\n" + SELF_MODEL + "\n")


def test_describe_body_words_and_regions():
    def b(doing="sit", where="target", target="send", x=0.9, y=0.9):
        return BodyEvent(doing=doing, where=where, target=target, x=x, y=y)

    assert describe_body(b(), "WhatsApp") == "Your body: sitting on the send button, bottom right of the screen, in WhatsApp."
    assert describe_body(b(), None) == "Your body: sitting on the send button, bottom right of the screen."
    words = {
        "idle": "standing", "walk": "walking", "sit": "sitting", "perch": "perched", "jump": "jumping",
        "fall": "falling", "nap": "napping", "peek": "peeking from the edge", "tease": "teasing the user",
        "dragged": "being carried by the user",
    }  # fmt: skip
    for doing, word in words.items():
        assert describe_body(b(doing, "floor", None), None).startswith(f"Your body: {word} at the bottom,")
    assert describe_body(b("perch", "platform", None), None).startswith("Your body: perched on a button,")
    assert describe_body(b("idle", "floor", None, 0.1, 0.1), None).endswith("top left of the screen.")
    assert describe_body(b("idle", "floor", None, 0.5, 0.1), None).endswith("top centre of the screen.")
    assert describe_body(b("idle", "floor", None, 0.1, 0.5), None).endswith("middle left of the screen.")
    assert describe_body(b("idle", "floor", None, 0.5, 0.5), None).endswith("centre of the screen.")
    assert describe_body(b("idle", "floor", None, 0.9, 0.5), None).endswith("middle right of the screen.")
    assert describe_body(b("idle", "floor", None, 0.5, 0.9), None).endswith("bottom centre of the screen.")
    # the cuts are < 0.34 / < 0.67
    assert describe_body(b("idle", "floor", None, 0.33, 0.33), None).endswith("top left of the screen.")
    assert describe_body(b("idle", "floor", None, 0.34, 0.34), None).endswith("centre of the screen.")
    assert describe_body(b("idle", "floor", None, 0.67, 0.67), None).endswith("bottom right of the screen.")


async def test_body_and_screen_in_prompt(mind):
    await mind.handle(glance("WhatsApp", [f"line {i}" for i in range(30)], targets=()))
    await mind.handle(BodyEvent(doing="sit", where="target", target="send", x=0.9, y=0.9))
    await mind.handle(UserMessage(text="hi"))
    usr = last_user_prompt(mind)
    assert "Your body: sitting on the send button, bottom right of the screen." in usr
    assert "On screen now (WhatsApp):" in usr
    assert "line 24" in usr and "line 25" not in usr  # 30 lines -> 25

    # the app the user is in is added to the body line
    from maya_brain.protocol import AppOpened

    await mind.handle(AppOpened(package="wa", label="WhatsApp"))
    mind.thinker.llm.calls.clear()
    mind.thinker.llm.replies = None
    mind._last_tap_think = None
    await mind.handle(UserMessage(text="again"))
    assert "bottom right of the screen, in WhatsApp." in last_user_prompt(mind)

    # a stale glance (> 10 min) is left out; a 10-minute-old one is still in
    mind.clock.advance(timedelta(minutes=10))
    await mind.handle(UserMessage(text="later"))
    assert "On screen now" in last_user_prompt(mind)
    mind.clock.advance(timedelta(seconds=1))
    await mind.handle(UserMessage(text="much later"))
    assert "On screen now" not in last_user_prompt(mind)


async def test_screen_lines_char_cap(thinker):
    long = ["x" * 200 for _ in range(10)]
    msgs = await thinker.build_messages(Context("initiative", "d", screen_app="Reader", screen_lines=tuple(long)))
    block = msgs[1]["content"].split("On screen now (Reader):\n")[1].split("\n\n")[0]
    assert len(block.split("\n")) == 7 and len(block.replace("\n", "")) <= 1500


async def test_move_rules_in_prompt(thinker):
    plain = (await thinker.build_messages(Context("initiative", "d")))[0]["content"]
    base = '"move" (object or null): {"intent": one of stay, wander, come_closer, go_left, go_right, go_up, go_down, sit, nap, peek'
    assert base + ', "target": null}' in plain
    assert "sit_on" not in plain and "tease" not in plain.lower().replace("teasing grows", "")
    teasing = (await thinker.build_messages(Context("initiative", "d", tease_targets=("send", "like"))))[0]["content"]
    assert base + ', sit_on, poke, "target": null}' in teasing
    assert TEASE_LINE in teasing


def test_thought_move_validator():
    def mv(move):
        return Thought.model_validate({"move": move}).move

    assert mv({"intent": "wander", "target": None}).intent == "wander"
    assert mv({"intent": "sit_on", "target": "send"}).target == "send"
    assert mv({"intent": "sit"}).target is None
    assert mv({"intent": "sit", "target": "send"}).target is None  # a target means nothing here
    for bad in (None, "sit", 5, [], {}, {"intent": "fly"}, {"intent": 3}, {"intent": "sit_on"},
                {"intent": "poke", "target": "rocket"}, {"intent": "poke", "target": 4}):  # fmt: skip
        assert mv(bad) is None
    # a bad move never costs the rest of the thought
    t = Thought.model_validate({"say": "hi", "speak": True, "move": {"intent": "fly"}})
    assert t.say == "hi" and t.speak and t.move is None


async def test_body_trigger_is_silent(thinker_with):
    th, llm = thinker_with([reply({"intent": "wander"}, speak=True, say="loud")])
    t = await th.think(Context("body", "A quiet moment. Decide what your body does next; you won't speak."))
    assert t.speak is False and t.say == "" and t.move.intent == "wander"
    assert llm.calls[0][-1]["content"].endswith("Now: A quiet moment. Decide what your body does next; you won't speak.")
    # a body thought needs no words, so empty `say` is fine
    th, _ = thinker_with(['{"thought": "hm", "speak": true}'])
    assert (await th.think(Context("body", "x"))).speak is False


# ---- moves ----


async def test_move_sent_after_say(mind, sent):
    script(mind, reply({"intent": "come_closer"}))
    await mind.handle(UserMessage(text="hi"))
    types = [a.type for a in sent if a.type in ("say", "move")]
    assert types == ["say", "move"]
    assert moves(sent)[0].intent == "come_closer" and moves(sent)[0].target is None


async def test_move_without_say(mind, sent):
    await prime(mind)
    mind.initiative._set_urge(100)
    script(mind, reply({"intent": "nap"}, speak=False, say=""))
    await mind.handle(Screen(on=True))
    assert [a.type for a in sent] == ["move"]


async def test_invalid_moves_dropped(mind, sent):
    await prime(mind)
    cases = [
        {"intent": "fly"},
        {"intent": "sit_on"},
        {"intent": "sit_on", "target": "follow"},  # a real tag, but not on this screen
        {"intent": "poke", "target": "rocket"},
    ]
    for move in cases:
        sent.clear()
        mind.clock.advance(timedelta(minutes=1))
        mind._last_tap_think = None
        script(mind, reply(move))
        await mind.handle(UserMessage(text="hi"))
        assert moves(sent) == [], move
        assert any(a.type == "say" for a in sent), move  # the rest of the thought survives
    assert mind.initiative.tease_ready()  # nothing was teased

    # tease in cooldown: no Move, say still sent
    script(mind, reply({"intent": "sit_on", "target": "send"}))
    sent.clear()
    await mind.handle(UserMessage(text="tease me"))
    assert [(m.intent, m.target) for m in moves(sent)] == [("sit_on", "send")]
    sent.clear()
    mind.clock.advance(timedelta(seconds=599))
    await mind.handle(glance())
    script(mind, reply({"intent": "poke", "target": "like"}))
    await mind.handle(UserMessage(text="again"))
    assert moves(sent) == [] and any(a.type == "say" for a in sent)
    # the cooldown ends at 600 s
    sent.clear()
    mind.clock.advance(timedelta(seconds=1))
    await mind.handle(glance())
    script(mind, reply({"intent": "poke", "target": "like"}))
    await mind.handle(UserMessage(text="and again"))
    assert [(m.intent, m.target) for m in moves(sent)] == [("poke", "like")]


async def test_tease_recorded(mind, sent):
    await prime(mind)
    script(mind, reply({"intent": "poke", "target": "like"}))
    await mind.handle(UserMessage(text="hi"))
    assert mind.memory.recent_turns()[-1].content == "you teased the user: poke like"
    assert (mind.memory.recent_turns()[-1].role) == "event"
    # a non-tease move leaves no tease turn and starts no cooldown
    mind.initiative._last_tease = None
    n = len(mind.memory.recent_turns())
    mind._last_tap_think = None
    script(mind, reply({"intent": "peek"}))
    await mind.handle(UserMessage(text="x"))
    assert [t.content for t in mind.memory.recent_turns()[n:]] == ["x", "hehe"]
    assert mind.initiative.tease_ready()


async def _offered(mind):
    mind.thinker.llm.replies = None
    await mind.handle(UserMessage(text="hi"))
    return TEASE_LINE in last_system_prompt(mind), "sit_on" in last_system_prompt(mind)


async def test_tease_guardrails(mind, sent, clock):
    # baseline: everything lined up -> offered
    await prime(mind)
    assert await _offered(mind) == (True, True)

    async def check(setup, name):
        mind._last_tap_think = None
        sent.clear()
        mind.initiative._last_tease = None
        mind.initiative._save()
        await setup()
        script(mind, reply({"intent": "sit_on", "target": "send"}))
        await mind.handle(UserMessage(text="hi"))
        offered = "sit_on" in last_system_prompt(mind)
        assert not offered, name
        assert moves(sent) == [], name
        assert any(a.type == "say" for a in sent), name

    async def stranger():
        await prime(mind, tier="stranger")

    async def sulky():
        await prime(mind, mood="sulky")

    async def no_glance():
        await prime(mind)
        mind.glance, mind.glance_at = None, None

    async def stale():
        await prime(mind)
        clock.advance(timedelta(minutes=10, seconds=1))

    async def no_targets():
        await prime(mind, g=glance(targets=()))

    async def shushed():
        await prime(mind)
        await mind.handle(UserShush())

    async def disconnected():
        await prime(mind)
        mind.set_connected(False)

    async def screen_off():
        await prime(mind)
        await mind.handle(Screen(on=False))

    for setup, name in [(stranger, "stranger"), (sulky, "sulky"), (no_glance, "no glance"), (stale, "stale"),
                        (no_targets, "no targets"), (shushed, "shushed"), (disconnected, "disconnected"),
                        (screen_off, "screen off")]:  # fmt: skip
        await check(setup, name)
        mind.set_connected(True)
        await mind.handle(Screen(on=True))
        clock.set(datetime(2026, 10, 24, 14, 0).astimezone())
        mind.initiative._silent_until = None

    # quiet hours
    clock.set(datetime(2026, 10, 24, 23, 45).astimezone())
    mind.initiative._silent_until = None

    async def quiet():
        await prime(mind)

    await check(quiet, "quiet hours")


def test_tease_cooldown_persisted(ini, clock, db):
    assert ini.tease_ready()
    ini.teased()
    assert not ini.tease_ready()
    reopened = Initiative(InitiativeConfig(), db, clock)
    assert not reopened.tease_ready()
    clock.advance(timedelta(seconds=599))
    assert not reopened.tease_ready()
    clock.advance(timedelta(seconds=1))
    assert reopened.tease_ready()
    assert Initiative(InitiativeConfig(), db, clock).tease_ready()


def test_tease_ready_and_body_allowed_gates(ini, clock):
    clock.set(datetime(2026, 10, 24, 14, 0).astimezone())
    assert not ini.body_allowed()  # nobody has been around
    ini.on_event(Screen(on=True))
    assert ini.body_allowed()
    clock.advance(timedelta(seconds=300))
    assert ini.body_allowed()
    clock.advance(timedelta(seconds=1))
    assert not ini.body_allowed()
    ini.on_event(Screen(on=True))
    assert ini.body_allowed()
    ini.shush()
    assert not ini.body_allowed() and not ini.tease_ready()
    clock.advance(timedelta(minutes=61))
    ini.on_event(Screen(on=True))
    assert ini.body_allowed() and ini.tease_ready()
    clock.set(datetime(2026, 10, 24, 23, 40).astimezone())
    ini.on_event(Screen(on=True))
    assert not ini.body_allowed() and not ini.tease_ready()


# ---- silent body thoughts ----


async def active(mind):
    await mind.handle(Screen(on=True))


async def test_body_thought_timer(mind, sent, clock):
    await active(mind)
    llm = mind.thinker.llm
    llm.replies = [reply({"intent": "wander"}, speak=True, say="loud")]
    drawn = []
    mind._rng.uniform = lambda lo, hi: drawn.append((lo, hi)) or 200.0
    assert mind._next_body_at is None

    await mind.tick()  # first tick only schedules
    assert llm.calls == [] and mind._next_body_at == clock.now() + timedelta(seconds=200)
    assert drawn == [(120, 240)]

    clock.advance(timedelta(seconds=199))
    await mind.tick()
    assert llm.calls == []  # not yet

    clock.advance(timedelta(seconds=41))  # 240 s after the first tick, the longest it can be
    await mind.tick()
    assert len(llm.calls) == 1
    assert llm.calls[0][-1]["content"].endswith("Now: A quiet moment. Decide what your body does next; you won't speak.")
    assert [a.type for a in sent if a.type in ("say", "move")] == ["move"]  # speak forced false
    assert mind.memory.recent_turns() == []  # a silent thought is not a conversation turn
    # stored like other thoughts
    trig = [r[0] for r in mind.memory.db.execute("select trigger from thoughts")]
    assert trig == ["body"]
    # and rescheduled 120-240 s ahead
    assert mind._next_body_at == clock.now() + timedelta(seconds=200) and len(drawn) == 2


async def test_body_thought_urge_unchanged(mind, clock):
    await active(mind)
    await mind.tick()
    mind._next_body_at = clock.now()
    mind.initiative._set_urge(10)
    before = mind.initiative.urge
    reason = mind.initiative.last_reason
    clock.advance(timedelta(seconds=1))
    await mind.tick()
    assert len(mind.thinker.llm.calls) == 1
    # the tick adds only its own 0.5 (stranger x curious); the thought neither resets nor bumps it
    assert mind.initiative.urge == pytest.approx(before + 0.5)
    assert mind.initiative.last_reason == reason


async def _due(mind, clock):
    await active(mind)
    await mind.tick()
    mind._next_body_at = clock.now() - timedelta(seconds=1)
    mind.thinker.llm.calls.clear()


async def test_body_thought_gates(mind, clock):
    llm = mind.thinker.llm

    # the user has been away more than 300 s
    await _due(mind, clock)
    clock.advance(timedelta(seconds=301))
    await mind.tick()
    assert llm.calls == []
    await active(mind)
    await mind.tick()
    assert len(llm.calls) == 1  # back and due
    llm.calls.clear()

    # shushed
    await _due(mind, clock)
    await mind.handle(UserShush())
    await mind.tick()
    assert llm.calls == []
    clock.advance(timedelta(minutes=61))

    # disconnected
    await _due(mind, clock)
    mind.set_connected(False)
    await mind.tick()
    assert llm.calls == []
    mind.set_connected(True)

    # screen off
    await _due(mind, clock)
    await mind.handle(Screen(on=False))
    await mind.tick()
    assert llm.calls == []

    # quiet hours
    clock.set(datetime(2026, 10, 24, 23, 45).astimezone())
    await mind.handle(Screen(on=True))
    mind._next_body_at = clock.now() - timedelta(seconds=1)
    llm.calls.clear()
    await mind.tick()
    assert llm.calls == []


async def test_body_thought_can_tease_under_the_same_rules(mind, sent, clock):
    await prime(mind)
    await active(mind)
    await mind.tick()
    mind._next_body_at = clock.now()
    script(mind, reply({"intent": "sit_on", "target": "send"}, speak=False, say=""))
    await mind.tick()
    assert [(m.intent, m.target) for m in moves(sent)] == [("sit_on", "send")]
    assert not mind.initiative.tease_ready()
    assert mind.memory.recent_turns()[-1].content == "you teased the user: sit_on send"


async def test_every_thought_sees_the_body(mind):
    await mind.handle(BodyEvent(doing="nap", where="floor", x=0.5, y=1.0))
    await mind.handle(glance("Maps", ["a long enough line about somewhere"], targets=()))
    from maya_brain.protocol import UserDrag, UserTap

    mind.initiative._set_urge(100)
    await mind.handle(Screen(on=True))  # initiative thought
    await mind.handle(UserDrag(thrown=True))  # event thought
    await mind.handle(UserTap())  # tap thought
    calls = mind.thinker.llm.calls
    assert len(calls) == 3
    for c in calls:
        assert "Your body: napping at the bottom, bottom centre of the screen." in c[-1]["content"]
        assert "On screen now (Maps):" in c[-1]["content"]
