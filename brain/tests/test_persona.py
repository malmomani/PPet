import pytest

from maya_brain.persona import TraitUpdate


def test_render_core_only(persona):
    r = persona.render()
    assert "Maya" in r and "curious" in r and "playful" in r and "like:" not in r.lower()


def test_add_reinforce_weaken_prune(persona):
    persona.apply_updates([TraitUpdate("like", "ramen", "add")])
    assert persona.traits()[0].confidence == pytest.approx(0.4)
    persona.apply_updates([TraitUpdate("like", "ramen", "reinforce")])
    assert persona.traits()[0].confidence == pytest.approx(0.6)
    persona.apply_updates([TraitUpdate("like", "ramen", "weaken")] * 2)
    assert persona.traits() == []  # 0.6 -0.3 -0.3 → pruned


def test_untouched_decay_and_ignores_garbage(persona):
    persona.apply_updates([TraitUpdate("joke", "the potato incident", "add")])
    persona.apply_updates([TraitUpdate("superpower", "x", "add"), TraitUpdate("like", "  ", "add")])
    assert [(t.kind, round(t.confidence, 2)) for t in persona.traits()] == [("joke", 0.38)]


def test_render_includes_traits(persona):
    persona.apply_updates([TraitUpdate("nickname", "calls the user 'boss'", "add")])
    assert "boss" in persona.render()


def test_add_existing_acts_as_reinforce_and_caps_at_one(persona):
    persona.apply_updates([TraitUpdate("like", "ramen", "add")])
    persona.apply_updates([TraitUpdate("like", " ramen ", "add")])
    assert persona.traits()[0].confidence == pytest.approx(0.6)
    persona.apply_updates([TraitUpdate("like", "ramen", "reinforce")] * 5)
    assert persona.traits()[0].confidence == pytest.approx(1.0)


def test_reinforce_new_and_weaken_missing(persona):
    persona.apply_updates([TraitUpdate("habit", "hums", "reinforce"), TraitUpdate("habit", "nope", "weaken")])
    assert [(t.text, t.confidence) for t in persona.traits()] == [("hums", pytest.approx(0.4))]


def test_traits_ordered_by_confidence_desc(persona):
    persona.apply_updates([TraitUpdate("like", "a", "add")])
    persona.apply_updates([TraitUpdate("like", "b", "add"), TraitUpdate("like", "b", "reinforce")])
    assert [t.text for t in persona.traits()] == ["b", "a"]


def test_render_caps_at_25_and_groups_by_kind(persona):
    persona.apply_updates([TraitUpdate("like", f"thing{i}", "add") for i in range(30)])
    persona.apply_updates([TraitUpdate("dislike", "mondays", "add")])
    r = persona.render()
    assert r.count("thing") <= 25
    assert "mondays" in r
