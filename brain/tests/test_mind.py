import asyncio
from datetime import datetime, timedelta, timezone

from maya_brain.config import Config, InitiativeConfig
from maya_brain.llm import FakeLLM
from maya_brain.mind import CANNED_FOGGY, Mind, bubble_ms
from maya_brain.protocol import (
    AppClosed,
    AppOpened,
    Hello,
    OfflineSummary,
    Screen,
    UserDrag,
    UserMessage,
    UserShush,
    UserTap,
)


def test_bubble_ms_bounds():
    assert bubble_ms("hi") == 4000
    assert bubble_ms("x" * 50) == 5500
    assert bubble_ms("x" * 1000) == 15000


async def test_hello_emits_state(mind, sent):
    await mind.handle(Hello(client_version="1", screen_w=1, screen_h=1))
    assert [a.type for a in sent] == ["state"]
    assert sent[0].mood == "curious" and sent[0].closeness_tier == "stranger"


async def test_user_message_flow(mind, sent):
    await mind.handle(UserMessage(text="hi"))
    assert [a.type for a in sent] == ["typing", "typing", "animate", "say", "state"]
    assert sent[0].on is True and sent[1].on is False
    assert [t.content for t in mind.memory.recent_turns()] == ["hi", "hehe"]
    assert mind.emotion.closeness == 11


async def test_user_message_llm_down_sends_canned_reply(mind, sent):
    mind.thinker.llm.fail_next = 10
    await mind.handle(UserMessage(text="hi"))
    assert any(a.type == "say" and a.text == CANNED_FOGGY for a in sent)
    assert sent[-1].type == "typing" and sent[-1].on is False


async def test_tap_rate_limit(mind, sent):
    await mind.handle(UserTap())
    n = len(mind.thinker.llm.calls)
    await mind.handle(UserTap())
    assert len(mind.thinker.llm.calls) == n and sent[-1].type == "animate" and sent[-1].name == "happy"


async def test_drag_thrown_thinks(mind, sent):
    await mind.handle(UserDrag(thrown=True))
    assert sent[0].type == "animate" and sent[0].name == "surprised"
    assert mind.memory.recent_turns()[0].content == "User picked you up and threw you!"
    assert len(mind.thinker.llm.calls) == 1


async def test_drag_not_thrown_does_not_think(mind, sent):
    await mind.handle(UserDrag())
    assert [a.type for a in sent] == ["animate"]
    assert mind.memory.recent_turns()[0].content == "User dragged you around"
    assert mind.thinker.llm.calls == []


async def test_app_closed_long_use_recorded(mind):
    await mind.handle(AppClosed(package="y", label="YouTube", duration_s=600))
    assert mind.memory.recent_turns()[-1].content == "User used YouTube for 10 min"


async def test_app_closed_short_use_ignored(mind):
    await mind.handle(AppClosed(package="y", label="YouTube", duration_s=60))
    assert mind.memory.recent_turns() == []


async def test_initiative_fires_from_tick(mind, sent):
    mind.initiative._set_urge(59.9)
    await mind.handle(AppOpened(package="t", label="TikTok"))
    assert any(a.type == "say" for a in sent) and mind.initiative.urge == 0


async def test_initiative_can_choose_silence(mind, sent):
    mind.thinker.llm.replies = ['{"speak": false, "animation": "peek"}']
    mind.initiative._set_urge(60)
    await mind.tick()
    assert [a.type for a in sent] == ["animate"]


async def test_shush_blocks_initiative(mind, sent):
    await mind.handle(UserShush())
    mind.initiative._set_urge(100)
    await mind.tick()
    assert not any(a.type == "say" for a in sent)


async def test_shush_records_turn_and_sits(mind, sent):
    await mind.handle(UserShush())
    assert sent[0].type == "animate" and sent[0].name == "sit"
    assert mind.memory.recent_turns()[-1].content == "User asked you to be quiet for a while"


async def test_idle_conversation_gets_summarized(mind, clock):
    mind.thinker.llm.replies = ['{"speak":true,"say":"yo"}', '{"summary":"chat","importance":2}']
    await mind.handle(UserMessage(text="hi"))
    clock.advance(timedelta(minutes=11))
    await mind.tick()
    assert mind.memory.unsummarized_turns() == []


async def test_offline_summary_aggregates(mind):
    await mind.handle(
        OfflineSummary(
            since=datetime(2026, 10, 24, tzinfo=timezone.utc),
            events=[
                {"type": "app_closed", "package": "y", "label": "YouTube", "duration_s": 3600},
                {"type": "app_closed", "package": "y", "label": "YouTube", "duration_s": 4200},
                {"type": "garbage"},
            ],
        )
    )
    assert "YouTube 2h 10m" in mind.memory.recent_turns()[-1].content


async def test_offline_summary_label_order_and_short_durations(mind):
    await mind.handle(
        OfflineSummary(
            since=datetime(2026, 10, 24, tzinfo=timezone.utc),
            events=[
                {"type": "app_closed", "package": "i", "label": "Instagram", "duration_s": 1800},
                {"type": "app_closed", "package": "y", "label": "YouTube", "duration_s": 3600},
            ],
        )
    )
    assert mind.memory.recent_turns()[-1].content == (
        "While you were disconnected the user used: Instagram 30m, YouTube 1h 0m"
    )


async def test_offline_summary_nothing_to_report_adds_no_turn(mind):
    await mind.handle(
        OfflineSummary(since=datetime(2026, 10, 24, tzinfo=timezone.utc), events=[{"type": "garbage"}])
    )
    assert mind.memory.recent_turns() == []


async def test_status(mind):
    s = mind.status()
    assert set(s) == {"mood", "intensity", "closeness", "tier", "urge", "current_app"}
    assert s["current_app"] is None
    await mind.handle(AppOpened(package="t", label="TikTok"))
    assert mind.status()["current_app"] == ("TikTok", 0)


async def test_tick_waits_for_handle_lock(mind):
    started = asyncio.Event()
    async with mind._lock:
        task = asyncio.create_task(mind.tick())
        await asyncio.sleep(0.01)
        assert not task.done()
        started.set()
    await asyncio.wait_for(task, 1)


async def test_run_ticker_calls_tick_and_survives_errors(db, clock):
    cfg = Config(initiative=InitiativeConfig(tick_s=0.01))
    mind = Mind(cfg, FakeLLM(), db, clock)
    calls = 0

    async def flaky():
        nonlocal calls
        calls += 1
        if calls == 1:
            raise RuntimeError("boom")

    mind.tick = flaky
    stop = asyncio.Event()
    task = asyncio.create_task(mind.run_ticker(stop))
    for _ in range(100):
        if calls >= 3:
            break
        await asyncio.sleep(0.01)
    stop.set()
    await asyncio.wait_for(task, 1)
    assert calls >= 3


async def test_no_initiative_while_disconnected(mind, sent):
    mind.set_connected(False)
    mind.initiative._set_urge(100)
    await mind.tick()
    assert sent == [] and mind.thinker.llm.calls == []
    mind.set_connected(True)
    await mind.tick()
    assert any(a.type == "say" for a in sent)


async def test_urge_does_not_grow_while_disconnected(mind):
    mind.set_connected(False)
    await mind.tick()
    assert mind.initiative.urge == 0


async def test_no_initiative_while_screen_off(mind, sent):
    await mind.handle(Screen(on=False))
    mind.initiative._set_urge(100)
    await mind.tick()
    assert mind.thinker.llm.calls == []
    await mind.handle(Screen(on=True))
    assert any(a.type == "say" for a in sent)


async def test_hello_resets_screen_state(mind, sent):
    await mind.handle(Screen(on=False))
    mind.initiative._set_urge(100)
    await mind.handle(Hello(client_version="1", screen_w=1, screen_h=1))
    assert any(a.type == "say" for a in sent)


async def test_no_unprompted_remark_right_after_reply(mind, sent, clock):
    mind.initiative._set_urge(59)
    await mind.handle(UserMessage(text="hi"))
    assert mind.initiative.urge == 0
    mind.initiative._set_urge(100)
    clock.advance(timedelta(minutes=5))
    sent.clear()
    await mind.tick()
    assert not any(a.type == "say" for a in sent)
    clock.advance(timedelta(minutes=5))
    await mind.tick()
    assert any(a.type == "say" for a in sent)


async def test_canned_reply_also_counts_as_engaged(mind, sent):
    mind.thinker.llm.fail_next = 10
    mind.initiative._set_urge(100)
    await mind.handle(UserTap())
    assert mind.initiative.urge == 0


async def test_only_user_activity_counts_as_last_event(mind, clock):
    await mind.handle(Hello(client_version="1", screen_w=1, screen_h=1))
    assert mind._last_event is None
    await mind.handle(AppOpened(package="t", label="TikTok"))
    assert mind._last_event == clock.now()


async def test_initiative_thought_does_not_change_closeness(mind, sent):
    mind.initiative._set_urge(100)
    await mind.tick()
    assert any(a.type == "say" for a in sent)  # the fake thought proposes closeness_delta +1
    assert mind.emotion.closeness == 10


async def test_only_thrown_drag_changes_closeness(mind):
    await mind.handle(UserDrag())
    assert mind.emotion.closeness == 10
    await mind.handle(UserDrag(thrown=True))
    assert mind.emotion.closeness == 11
