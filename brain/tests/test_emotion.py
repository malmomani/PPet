from datetime import timedelta

import pytest

from maya_brain.db import open_db
from maya_brain.emotion import TIER_GUIDANCE, Emotion, closeness_tier


def test_defaults(emo):
    assert emo.mood() == ("curious", 0.3) and emo.closeness == 10 and emo.tier() == "stranger"


def test_tiers():
    assert [closeness_tier(x) for x in (0, 29.9, 30, 69.9, 70, 100)] == (
        ["stranger"] * 2 + ["friend"] * 2 + ["close"] * 2
    )


def test_tier_guidance_covers_all_tiers():
    assert set(TIER_GUIDANCE) == {"stranger", "friend", "close"}


def test_mood_decays_to_baseline(emo, clock):
    emo.set_mood("excited", 0.8)
    clock.advance(timedelta(hours=2))
    assert emo.mood() == ("excited", pytest.approx(0.4))
    clock.advance(timedelta(hours=4))
    assert emo.mood() == ("curious", 0.3)


def test_baseline_mood_intensity_moves_toward_point_three(emo, clock):
    emo.set_mood("curious", 0.9)
    clock.advance(timedelta(hours=2))
    assert emo.mood() == ("curious", pytest.approx(0.6))


def test_set_mood_clamps_intensity(emo):
    emo.set_mood("happy", 5)
    assert emo.mood() == ("happy", 1.0)


def test_unknown_mood_ignored(emo):
    emo.set_mood("hangry", 1.0)
    assert emo.mood()[0] == "curious"


def test_closeness_clamp_and_daily_loss_cap(emo, clock):
    assert emo.apply_closeness(50) == 3
    for _ in range(5):
        emo.apply_closeness(-3)
    assert emo.closeness == pytest.approx(13 - 8)
    clock.advance(timedelta(days=1))
    emo.apply_closeness(-3)
    assert emo.closeness == pytest.approx(2)


def test_closeness_bounds(emo, clock):
    assert emo.apply_closeness(-3) == -3
    assert emo.apply_closeness(-3) == -3
    assert emo.apply_closeness(-3) == -2  # daily cap of 8 hit
    for _ in range(40):
        clock.advance(timedelta(days=1))  # daily gain cap of 8
        emo.apply_closeness(3)
    assert emo.closeness == 100
    assert emo.apply_closeness(3) == 0


def test_daily_drift_toward_10(emo, clock):
    for _ in range(10):
        emo.apply_closeness(3)
        clock.advance(timedelta(days=1))  # daily gain cap of 8
    emo.note_interaction()
    clock.advance(timedelta(days=3))
    emo.daily_drift()
    emo.daily_drift()
    assert emo.closeness == pytest.approx(39)


def test_no_drift_without_interaction_or_when_recent(emo, clock):
    for _ in range(4):
        emo.apply_closeness(3)
        clock.advance(timedelta(days=1))  # daily gain cap of 8
    emo.daily_drift()
    assert emo.closeness == 22
    emo.note_interaction()
    clock.advance(timedelta(days=1))
    emo.daily_drift()
    assert emo.closeness == 22


def test_drift_upward_toward_10_and_not_past(emo, clock):
    emo.apply_closeness(-3)
    emo.note_interaction()
    clock.advance(timedelta(days=2))
    emo.daily_drift()
    assert emo.closeness == 8
    clock.advance(timedelta(days=1))
    emo.daily_drift()
    assert emo.closeness == 9
    clock.advance(timedelta(days=1))
    emo.daily_drift()
    clock.advance(timedelta(days=1))
    emo.daily_drift()
    assert emo.closeness == 10


def test_emotion_persists_across_reopen(tmp_path, clock):
    db1 = open_db(tmp_path / "e.db")
    db2 = None
    try:
        e = Emotion(db1, clock)
        e.set_mood("happy", 0.9)
        e.apply_closeness(3)
        db2 = open_db(tmp_path / "e.db")
        e2 = Emotion(db2, clock)
        assert e2.mood()[0] == "happy" and e2.closeness == 13
    finally:
        db1.close()
        if db2 is not None:
            db2.close()


def test_daily_gain_cap(emo, clock):
    for _ in range(5):
        emo.apply_closeness(3)
    assert emo.closeness == pytest.approx(10 + 8)
    assert emo.apply_closeness(3) == 0
    clock.advance(timedelta(days=1))
    assert emo.apply_closeness(3) == 3
    assert emo.closeness == pytest.approx(21)
