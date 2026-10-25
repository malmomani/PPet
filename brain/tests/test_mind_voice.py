import asyncio
import base64
import logging
from datetime import timedelta

import pytest

from maya_brain.config import Config
from maya_brain.db import set_state
from maya_brain.llm import FakeLLM
from maya_brain.mind import CANNED_FOGGY, Mind
from maya_brain.protocol import Hello, UserMessage, UserTap
from maya_brain.tts import FakeTTS


def hello(mode: str) -> Hello:
    return Hello(client_version="1", screen_w=1, screen_h=1, voice_mode=mode)


def says(sent):
    return [a for a in sent if a.type == "say"]


def audios(sent):
    return [a for a in sent if a.type == "audio"]


@pytest.fixture
def cfg():
    return Config()


@pytest.fixture
async def mind_v(db, clock, sent, cfg):
    # Mark today's reflection as done so it doesn't consume scripted replies.
    set_state(db, "reflection", {"last_date": clock.now().date().isoformat(), "last_at": clock.now().isoformat()})

    async def sink(action):
        sent.append(action)

    m = Mind(cfg, FakeLLM(), db, clock, sink, tts=FakeTTS())
    yield m
    await m.wait_voice()  # never leave a TTS task pending


async def test_voice_reply_gets_audio(mind_v, sent):
    await mind_v.handle(hello("replies"))
    await mind_v.handle(UserMessage(text="hi", voice=True))
    await mind_v.wait_voice()
    say = next(a for a in sent if a.type == "say")
    audio = audios(sent)
    assert len(audio) == 1 and audio[0].ref_id == say.id and audio[0].mime == "audio/wav"
    assert base64.b64decode(audio[0].data_b64)[:4] == b"RIFF"
    assert sent.index(audio[0]) > sent.index(say)
    assert mind_v.tts.calls == ["hehe"]


async def test_typed_reply_not_voiced_in_replies_mode(mind_v, sent):
    await mind_v.handle(hello("replies"))
    await mind_v.handle(UserMessage(text="hi", voice=False))
    await mind_v.wait_voice()
    assert says(sent) and audios(sent) == [] and mind_v.tts.calls == []


async def test_always_mode_voices_typed_and_initiative(mind_v, sent, clock):
    await mind_v.handle(hello("always"))
    await mind_v.handle(UserMessage(text="hi", voice=False))
    await mind_v.wait_voice()
    assert len(audios(sent)) == 1 and audios(sent)[0].ref_id == says(sent)[0].id
    clock.advance(timedelta(minutes=15))  # past the quiet spell after a reply
    mind_v.initiative._set_urge(100)
    await mind_v.tick()  # unprompted remark
    await mind_v.wait_voice()
    assert len(says(sent)) == 2 and len(audios(sent)) == 2
    assert audios(sent)[1].ref_id == says(sent)[1].id


async def test_always_mode_voices_tap_reply(mind_v, sent):
    await mind_v.handle(hello("always"))
    await mind_v.handle(UserTap())
    await mind_v.wait_voice()
    assert len(says(sent)) == 1 and len(audios(sent)) == 1


async def test_initiative_not_voiced_in_replies_mode(mind_v, sent):
    await mind_v.handle(hello("replies"))
    mind_v.initiative._set_urge(100)
    await mind_v.tick()
    await mind_v.wait_voice()
    assert says(sent) and audios(sent) == []


async def test_voice_reply_flag_does_not_leak_to_later_say(mind_v, sent, clock):
    await mind_v.handle(hello("replies"))
    await mind_v.handle(UserMessage(text="hi", voice=True))
    await mind_v.wait_voice()
    clock.advance(timedelta(minutes=15))
    mind_v.initiative._set_urge(100)
    await mind_v.tick()
    await mind_v.wait_voice()
    assert len(says(sent)) == 2 and len(audios(sent)) == 1


async def test_canned_foggy_voice_reply_is_voiced(mind_v, sent):
    mind_v.thinker.llm.fail_next = 10
    await mind_v.handle(hello("replies"))
    await mind_v.handle(UserMessage(text="hi", voice=True))
    await mind_v.wait_voice()
    assert says(sent)[0].text == CANNED_FOGGY
    assert len(audios(sent)) == 1 and audios(sent)[0].ref_id == says(sent)[0].id


async def test_mode_off_never_voices(mind_v, sent):
    await mind_v.handle(UserMessage(text="hi", voice=True))  # no Hello at all: mode is off
    await mind_v.wait_voice()
    assert mind_v.voice_mode == "off"
    assert says(sent) and audios(sent) == [] and mind_v.tts.calls == []


async def test_hello_sets_voice_mode(mind_v):
    assert mind_v.voice_mode == "off"
    await mind_v.handle(hello("always"))
    assert mind_v.voice_mode == "always"
    await mind_v.handle(Hello(client_version="1", screen_w=1, screen_h=1))  # old client: back to off
    assert mind_v.voice_mode == "off"


async def test_voice_unavailable_still_sends_text(mind_v, sent, caplog):
    mind_v.tts.fail_next = 1  # TTSError
    await mind_v.handle(hello("replies"))
    with caplog.at_level(logging.WARNING, logger="maya_brain.mind"):
        await mind_v.handle(UserMessage(text="hi", voice=True))
        await mind_v.wait_voice()
    assert says(sent) and audios(sent) == []
    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert len(warnings) == 1 and "\n" not in warnings[0].getMessage()


async def test_newer_say_cancels_pending_tts(mind_v, sent):
    mind_v.tts.delay_s = 0.2
    await mind_v.handle(hello("replies"))
    await mind_v.handle(UserMessage(text="one", voice=True))
    await mind_v.handle(UserMessage(text="two", voice=True))
    await mind_v.wait_voice()
    assert len(says(sent)) == 2
    assert len(audios(sent)) == 1 and audios(sent)[0].ref_id == says(sent)[1].id


async def test_tts_runs_outside_the_lock(mind_v, sent):
    mind_v.tts.delay_s = 0.2
    await mind_v.handle(hello("replies"))
    await mind_v.handle(UserMessage(text="hi", voice=True))
    await asyncio.wait_for(mind_v.handle(UserTap()), 0.1)  # not blocked behind the pending synthesis
    await mind_v.wait_voice()


async def test_disabled_in_config_never_voices(db, clock, sent, cfg):
    cfg.voice.enabled = False
    set_state(db, "reflection", {"last_date": clock.now().date().isoformat(), "last_at": clock.now().isoformat()})

    async def sink(action):
        sent.append(action)

    tts = FakeTTS()
    m = Mind(cfg, FakeLLM(), db, clock, sink, tts=tts)
    await m.handle(hello("always"))
    await m.handle(UserMessage(text="hi", voice=True))
    await m.wait_voice()
    assert says(sent) and audios(sent) == [] and tts.calls == []


async def test_no_tts_never_voices(db, clock, sent, cfg):
    set_state(db, "reflection", {"last_date": clock.now().date().isoformat(), "last_at": clock.now().isoformat()})

    async def sink(action):
        sent.append(action)

    m = Mind(cfg, FakeLLM(), db, clock, sink)  # tts=None
    await m.handle(hello("always"))
    await m.handle(UserMessage(text="hi", voice=True))
    await m.wait_voice()
    assert says(sent) and audios(sent) == []


async def test_emoji_only_say_is_skipped(mind_v, sent):
    mind_v.thinker.llm.replies = ['{"speak":true,"say":"😊"}']
    await mind_v.handle(hello("replies"))
    await mind_v.handle(UserMessage(text="hi", voice=True))
    await mind_v.wait_voice()
    assert says(sent) and audios(sent) == [] and mind_v.tts.calls == []


async def test_wait_voice_without_task_returns(mind_v):
    await mind_v.wait_voice()
