from datetime import datetime

import pytest

from maya_brain.clock import FakeClock


@pytest.fixture
def clock():
    return FakeClock(datetime(2026, 10, 24, 14, 0).astimezone())


@pytest.fixture
def llm():
    from maya_brain.llm import FakeLLM

    return FakeLLM()


@pytest.fixture
def db():
    from maya_brain.db import open_db

    conn = open_db(":memory:")
    yield conn
    conn.close()


@pytest.fixture
def persona(db, clock):
    from maya_brain.config import PersonaConfig
    from maya_brain.persona import Persona

    return Persona(db, PersonaConfig(), clock)


@pytest.fixture
def emo(db, clock):
    from maya_brain.emotion import Emotion

    return Emotion(db, clock)


@pytest.fixture
def memory(db, llm, clock):
    from maya_brain.memory import Memory

    return Memory(db, llm, clock)


@pytest.fixture
def thinker(llm, persona, memory, emo, clock):
    from maya_brain.thinker import Thinker

    return Thinker(llm, persona, memory, emo, clock)


@pytest.fixture
def thinker_with(persona, memory, emo, clock):
    from maya_brain.llm import FakeLLM
    from maya_brain.thinker import Thinker

    def make(replies):
        fake = FakeLLM(replies)
        return Thinker(fake, persona, memory, emo, clock), fake

    return make
