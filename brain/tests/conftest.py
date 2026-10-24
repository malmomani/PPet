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
