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
