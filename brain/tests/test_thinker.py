import pytest

from maya_brain.llm import LLMError
from maya_brain.thinker import Context, Thought


async def test_prompt_contains_context(thinker, memory):
    memory.set_fact("name", "Sam")
    memory.add_turn("user", "I love ramen")
    msgs = await thinker.build_messages(
        Context("initiative", "User has been on TikTok for 50 minutes", "TikTok", 50)
    )
    sys, usr = msgs[0]["content"], msgs[1]["content"]
    assert "Maya" in sys and "Sam" in sys and "stranger" in sys.lower() and "peek" in sys
    assert "I love ramen" in usr and "TikTok" in usr and "50 minutes" in usr


async def test_valid_thought_stored(thinker, db):
    t = await thinker.think(Context("user_message", 'User says: "hi"'))
    assert t.speak and t.say == "hehe"
    assert db.execute("select count(*) from thoughts").fetchone()[0] == 1


def test_thought_normalizes_bad_fields():
    t = Thought.model_validate(
        {
            "say": "x" * 999,
            "animation": "moonwalk",
            "mood": "hangry",
            "closeness_delta": 50,
            "mood_intensity": 7,
            "extra": 1,
        }
    )
    assert len(t.say) == 300 and t.animation == "idle" and t.mood is None
    assert t.closeness_delta == 3 and t.mood_intensity == 1


async def test_retry_once_then_success(thinker_with):
    th, llm = thinker_with(["garbage", '{"speak": false}'])
    t = await th.think(Context("initiative", "bored"))
    assert t is not None and t.speak is False and len(llm.calls) == 2
    assert llm.calls[1][-2]["role"] == "assistant" and llm.calls[1][-2]["content"] == "garbage"


async def test_direct_trigger_bad_json_twice_returns_none(thinker_with):
    th, _ = thinker_with(['{"speak": true, "say": ""}', "nope"])
    assert await th.think(Context("user_message", 'User says: "hi"')) is None


async def test_direct_trigger_forces_speak(thinker_with):
    th, _ = thinker_with(['{"speak": false, "say": "hm?"}'])
    assert (await th.think(Context("user_tap", "User tapped you."))).speak is True


async def test_llm_error_propagates(thinker_with):
    th, llm = thinker_with(None)
    llm.fail_next = 5
    with pytest.raises(LLMError):
        await th.think(Context("user_message", "x"))
