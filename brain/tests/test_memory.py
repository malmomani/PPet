from datetime import datetime, timedelta, timezone

import pytest

from maya_brain.clock import FakeClock
from maya_brain.db import get_state, open_db, set_state
from maya_brain.llm import FakeLLM
from maya_brain.memory import Memory


@pytest.fixture
def mem():
    clock = FakeClock(datetime(2026, 10, 24, 12, tzinfo=timezone.utc))
    m = Memory(open_db(":memory:"), FakeLLM(), clock)
    yield m, clock
    m.db.close()


def test_buffer_keeps_last_20_in_order(mem):
    m, _ = mem
    for i in range(25):
        m.add_turn("user", f"msg {i}")
    t = m.recent_turns()
    assert len(t) == 20 and t[0].content == "msg 5" and t[-1].content == "msg 24"


def test_unsummarized_and_mark(mem):
    m, _ = mem
    m.add_turn("user", "a")
    m.add_turn("maya", "b")
    assert [t.content for t in m.unsummarized_turns()] == ["a", "b"]
    m.mark_summarized()
    assert m.unsummarized_turns() == []
    assert len(m.recent_turns()) == 2


def test_buffer_persists_across_reopen(tmp_path):
    clock = FakeClock(datetime(2026, 10, 24, tzinfo=timezone.utc))
    first = Memory(open_db(tmp_path / "m.db"), FakeLLM(), clock)
    first.add_turn("user", "remember me")
    first.db.close()
    second = Memory(open_db(tmp_path / "m.db"), FakeLLM(), clock)
    assert second.recent_turns()[0].content == "remember me"
    second.db.close()


async def test_recall_prefers_similar_then_recent(mem):
    m, clock = mem
    await m.add_episode("We talked about ramen and noodles", 3)
    await m.add_episode("User filed tax forms", 3)
    got = await m.recall("favorite ramen place", k=1)
    assert got[0].summary.startswith("We talked about ramen")


async def test_recall_empty_and_embed_failure(mem):
    m, _ = mem
    assert await m.recall("anything") == []
    await m.add_episode("x", 9)  # clamped
    m.llm.fail_next = 1
    assert await m.recall("x") == []


def test_facts_upsert_order_limit(mem):
    m, clock = mem
    m.set_fact("name", "Sam")
    clock.advance(timedelta(seconds=1))
    m.set_fact("food", "ramen")
    clock.advance(timedelta(seconds=1))
    m.set_fact("name", "Sammy")
    assert list(m.facts().items()) == [("name", "Sammy"), ("food", "ramen")]
    m.delete_fact("food")
    assert m.facts() == {"name": "Sammy"}


def test_same_timestamp_turns_ordered_by_id(mem):
    m, _ = mem
    for i in range(5):
        m.add_turn("user", f"m{i}")
    assert [t.content for t in m.recent_turns()] == [f"m{i}" for i in range(5)]


def test_last_turn_time_filters_roles(mem):
    m, clock = mem
    assert m.last_turn_time() is None
    m.add_turn("user", "hi")
    clock.advance(timedelta(minutes=5))
    m.add_turn("event", "poked")
    assert m.last_turn_time() == datetime(2026, 10, 24, 12, tzinfo=timezone.utc)
    assert m.last_turn_time(roles=("event",)) == datetime(2026, 10, 24, 12, 5, tzinfo=timezone.utc)


async def test_importance_clamped_and_episodes_since(mem):
    m, clock = mem
    a = await m.add_episode("old", 9)
    clock.advance(timedelta(days=2))
    b = await m.add_episode("new", -3)
    got = m.episodes_since(datetime(2026, 10, 25, tzinfo=timezone.utc))
    assert [e.id for e in got] == [b]
    assert got[0].importance == 1
    assert m.db.execute("SELECT importance FROM episodes WHERE id=?", (a,)).fetchone()[0] == 5


async def test_recall_recency_and_importance_weighting(mem):
    m, clock = mem
    await m.add_episode("ramen night", 3)
    clock.advance(timedelta(days=30))
    await m.add_episode("ramen night", 3)
    got = await m.recall("ramen night", k=2)
    assert got[0].created_at > got[1].created_at
    assert got[0].score == pytest.approx(0.8, abs=1e-6)
    assert got[1].score == pytest.approx(0.4, abs=1e-6)


def test_state_roundtrip(db):
    assert get_state(db, "k") is None
    set_state(db, "k", {"a": 1})
    set_state(db, "k", {"a": 2, "b": [1]})
    assert get_state(db, "k") == {"a": 2, "b": [1]}


def test_open_db_creates_all_tables(db):
    names = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert {"turns", "episodes", "facts", "traits", "thoughts", "diary", "state"} <= names


def test_turns_ordered_by_id_even_if_clock_went_back(mem):
    m, clock = mem
    m.add_turn("user", "first")
    clock.advance(timedelta(hours=-1))  # e.g. a simulator time jump or an NTP correction
    m.add_turn("maya", "second")
    assert [t.content for t in m.recent_turns()] == ["first", "second"]
    assert [t.content for t in m.unsummarized_turns()] == ["first", "second"]
    assert m.last_turn_time() == datetime(2026, 10, 24, 11, tzinfo=timezone.utc)


async def test_episode_kept_without_embedding_and_failure_logged_once(mem, caplog):
    m, _ = mem
    m.llm.fail_next = 2
    await m.add_episode("We talked about ramen", 3)  # stored, just not searchable
    assert await m.recall("ramen") == []
    assert [e.summary for e in m.episodes_since(datetime(2000, 1, 1).astimezone())] == ["We talked about ramen"]
    assert sum("memory search is off" in r.message for r in caplog.records) == 1
