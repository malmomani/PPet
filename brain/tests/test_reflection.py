from datetime import timedelta

from maya_brain.db import get_state


def _at_4am_next_day(clock):
    clock.set(clock.now().replace(hour=4, minute=0) + timedelta(days=1))  # Sun 2026-10-25 04:00


async def test_summarize_pending(refl, memory, llm):
    llm.replies = ['{"summary": "The user said they love ramen.", "importance": 4}']
    memory.add_turn("user", "I love ramen")
    eid = await refl.summarize_pending()
    assert eid and memory.unsummarized_turns() == []
    assert (await memory.recall("ramen"))[0].importance == 4
    assert await refl.summarize_pending() is None


async def test_summarize_failure_keeps_turns(refl, memory, llm):
    memory.add_turn("user", "x")
    llm.fail_next = 1
    assert await refl.summarize_pending() is None and len(memory.unsummarized_turns()) == 1


async def test_summarize_bad_json_keeps_turns(refl, memory, llm):
    memory.add_turn("user", "x")
    llm.replies = ["not json"]
    assert await refl.summarize_pending() is None and len(memory.unsummarized_turns()) == 1
    llm.replies = ['{"summary": "", "importance": 3}']
    assert await refl.summarize_pending() is None and len(memory.unsummarized_turns()) == 1


async def test_summarize_embed_failure_keeps_turns(refl, memory, llm):
    memory.add_turn("user", "x")
    llm.replies = ['{"summary": "The user said x.", "importance": 2}']

    async def boom(text):
        from maya_brain.llm import LLMError

        raise LLMError("embed down")

    llm.embed = boom
    assert await refl.summarize_pending() is None and len(memory.unsummarized_turns()) == 1


async def test_summarize_prompt_has_transcript(refl, memory, llm):
    llm.replies = ['{"summary": "The user said hi.", "importance": 2}']
    memory.add_turn("user", "hello there")
    await refl.summarize_pending()
    assert "[14:00] user: hello there" in llm.calls[0][1]["content"]


async def test_reflect_full(refl, memory, persona, llm):
    llm.replies = [
        '{"facts":[{"key":"food","value":"ramen"}],"remove":[]}',
        '{"traits":[{"kind":"like","text":"talking about food","action":"add"}]}',
        '{"entry":"Today we talked about ramen!"}',
    ]
    assert await refl.reflect() is True
    assert memory.facts() == {"food": "ramen"} and persona.traits()[0].text == "talking about food"
    assert refl.diary()[0][1] == "Today we talked about ramen!"


async def test_reflect_summarizes_then_feeds_episodes(refl, memory, llm):
    memory.add_turn("user", "I love ramen")
    llm.replies = [
        '{"summary": "The user loves ramen.", "importance": 4}',
        '{"facts":[],"remove":[]}',
        '{"traits":[]}',
        '{"entry":"ramen day"}',
    ]
    assert await refl.reflect() is True
    assert memory.unsummarized_turns() == []
    assert "The user loves ramen." in llm.calls[1][1]["content"]
    assert "The user loves ramen." in llm.calls[3][1]["content"]


async def test_reflect_step_failure_isolated(refl, llm):
    llm.replies = ["bad", '{"traits":[]}', '{"entry":"ok"}']
    assert await refl.reflect() is True


async def test_reflect_diary_failure_returns_false_and_is_not_recorded(refl, llm, clock, db):
    llm.replies = ['{"facts":[],"remove":[]}', '{"traits":[]}', "bad"]
    assert await refl.reflect() is False
    assert refl.diary() == []
    assert "last_date" not in get_state(db, "reflection")
    assert refl.due(None) is False


async def test_reflect_wrong_shapes_skip_step(refl, memory, persona, llm):
    memory.set_fact("old", "v")
    llm.replies = ['{"facts":"nope","remove":["old"]}', '{"traits":{"a":1}}', '{"entry":42}']
    assert await refl.reflect() is False
    assert memory.facts() == {"old": "v"} and persona.traits() == []


async def test_reflect_tolerates_bad_items(refl, memory, persona, llm):
    memory.set_fact("old", "v")
    llm.replies = [
        '{"facts":[{"key":"a","value":"1"}, 5, {"key":"b"}, {"key":"c","value":3}],"remove":["old", 7]}',
        '{"traits":[{"kind":"like","text":"tea","action":"add"}, "x", {"kind":"like"}, '
        '{"kind":"bogus","text":"z","action":"add"}]}',
        '{"entry":"ok"}',
    ]
    assert await refl.reflect() is True
    assert memory.facts() == {"a": "1"}
    assert [t.text for t in persona.traits()] == ["tea"]


async def test_reflect_remove_fact(refl, memory, llm):
    memory.set_fact("pet", "cat")
    llm.replies = ['{"facts":[],"remove":["pet"]}', '{"traits":[]}', '{"entry":"ok"}']
    await refl.reflect()
    assert memory.facts() == {}


async def test_reflect_llm_error_isolated(refl, llm):
    llm.replies = ['{"traits":[]}', '{"entry":"ok"}']
    llm.fail_next = 1  # the facts call raises before consuming a reply
    assert await refl.reflect() is True


async def test_diary_upsert_and_order(refl, clock, llm):
    llm.replies = ['{"facts":[],"remove":[]}', '{"traits":[]}', '{"entry":"first"}']
    await refl.reflect()
    llm.replies = ['{"facts":[],"remove":[]}', '{"traits":[]}', '{"entry":"second"}']
    await refl.reflect()
    assert refl.diary() == [("2026-10-24", "second")]
    clock.advance(timedelta(days=1))
    llm.replies = ['{"facts":[],"remove":[]}', '{"traits":[]}', '{"entry":"third"}']
    await refl.reflect()
    assert refl.diary() == [("2026-10-25", "third"), ("2026-10-24", "second")]
    assert refl.diary(limit=1) == [("2026-10-25", "third")]


def test_due(refl, clock):  # clock at Sat 14:00 local
    assert refl.due(None) is False  # Saturday's reflection is scheduled for Sunday 04:00
    clock.set(clock.now().replace(hour=2))
    assert refl.due(clock.now()) is False
    assert refl.due(clock.now() - timedelta(hours=7)) is True


async def test_once_per_day(refl, clock, llm):
    llm.replies = ['{"facts":[],"remove":[]}', '{"traits":[]}', '{"entry":"a"}']
    await refl.reflect()  # Sat 14:00: reflects (and uses up) Saturday
    assert refl.due(None) is False
    _at_4am_next_day(clock)
    assert refl.due(None) is False  # still Saturday's reflection day
    clock.advance(timedelta(days=1))
    assert refl.due(None) is True  # Mon 04:00: Sunday's turn


async def test_episodes_only_since_last_reflection(refl, memory, llm, clock):
    memory.add_turn("user", "one")
    llm.replies = [
        '{"summary": "Old episode.", "importance": 2}',
        '{"facts":[],"remove":[]}',
        '{"traits":[]}',
        '{"entry":"a"}',
    ]
    await refl.reflect()
    clock.advance(timedelta(hours=2))
    memory.add_turn("user", "two")
    llm.calls.clear()
    llm.replies = [
        '{"summary": "New episode.", "importance": 2}',
        '{"facts":[],"remove":[]}',
        '{"traits":[]}',
        '{"entry":"b"}',
    ]
    await refl.reflect()
    facts_prompt = llm.calls[1][1]["content"]
    assert "New episode." in facts_prompt and "Old episode." not in facts_prompt


def test_due_at_scheduled_time_after_midnight(refl, clock):
    clock.set(clock.now().replace(hour=3, minute=59) + timedelta(days=1))
    assert refl.due(None) is False
    _at_4am_next_day(clock)
    assert refl.due(None) is True


async def test_diary_keyed_to_reflection_day(refl, llm, clock):
    _at_4am_next_day(clock)
    llm.replies = ['{"facts":[],"remove":[]}', '{"traits":[]}', '{"entry":"what a saturday"}']
    assert await refl.reflect() is True
    assert refl.diary() == [("2026-10-24", "what a saturday")]
    assert "Saturday, 2026-10-24" in llm.calls[-1][1]["content"]
    assert refl.due(None) is False


async def test_llm_down_at_4am_retried_an_hour_later(refl, memory, llm, clock):
    await memory.add_episode("Talked about ramen.", 3)  # Sat 14:00
    _at_4am_next_day(clock)
    assert refl.due(None) is True
    llm.fail_next = 100
    assert await refl.reflect() is False
    clock.advance(timedelta(minutes=59))
    assert refl.due(None) is False  # backing off
    clock.advance(timedelta(minutes=1))
    assert refl.due(None) is True  # same reflection day, retried
    llm.fail_next = 0
    llm.calls.clear()
    llm.replies = ['{"facts":[],"remove":[]}', '{"traits":[]}', '{"entry":"ok"}']
    assert await refl.reflect() is True
    assert "Talked about ramen." in llm.calls[0][1]["content"]  # episode window was not advanced
    assert refl.diary() == [("2026-10-24", "ok")]
    assert refl.due(None) is False
