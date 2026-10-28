"""Describe a phone screenshot with a vision model. Images live in RAM only; nothing here logs content."""

import logging
from typing import Protocol

import httpx

from maya_brain.config import Config
from maya_brain.llm import _strip_think

log = logging.getLogger(__name__)

VISION_PROMPT = (
    "This is a screenshot of the user's phone. In 1-3 short sentences, describe what the user is looking at "
    "(the pictures, not the menus). The small pixel girl is Maya herself; don't describe her."
)
MAX_DESCRIPTION_CHARS = 300
OLLAMA_DEFAULT_VISION_MODEL = "gemma3:4b"


class Describer(Protocol):
    async def describe(self, image_b64: str) -> str | None: ...


def vision_model(cfg: Config) -> str:
    """The model that looks at screenshots: [vision] model, else the chat model (openai) or gemma3:4b (ollama)."""
    if cfg.vision.model:
        return cfg.vision.model
    return cfg.llm.chat_model if cfg.llm.provider.strip().lower() == "openai" else OLLAMA_DEFAULT_VISION_MODEL


def _trim(text: str) -> str | None:
    text = " ".join(_strip_think(text).split())
    if not text:
        return None
    if len(text) > MAX_DESCRIPTION_CHARS:
        text = text[: MAX_DESCRIPTION_CHARS - 1].rstrip() + "…"
    return text


class _HTTPDescriber:
    def __init__(self, cfg: Config, transport: httpx.AsyncBaseTransport | None):
        self.cfg = cfg
        self._transport = transport

    def _request(self, image_b64: str) -> tuple[str, dict, dict]:
        raise NotImplementedError

    @staticmethod
    def _extract(data: dict) -> str:
        raise NotImplementedError

    async def describe(self, image_b64: str) -> str | None:
        try:
            url, payload, headers = self._request(image_b64)
            async with httpx.AsyncClient(timeout=self.cfg.llm.timeout_s, transport=self._transport) as client:
                resp = await client.post(url, json=payload, headers=headers)
                resp.raise_for_status()
                content = self._extract(resp.json())
            return _trim(content)
        except Exception as e:  # any failure just means "no description"; never log the content
            log.warning("vision describe failed: %s", type(e).__name__)
            return None


class OpenAIDescriber(_HTTPDescriber):
    def _request(self, image_b64: str) -> tuple[str, dict, dict]:
        llm = self.cfg.llm
        payload = {
            "model": vision_model(self.cfg),
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": VISION_PROMPT},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}},
            ]}],
            "temperature": llm.temperature,
            "stream": False,
        }
        headers = {"Authorization": f"Bearer {llm.api_key}"} if llm.api_key else {}
        return f"{llm.base_url.rstrip('/')}/chat/completions", payload, headers

    @staticmethod
    def _extract(data: dict) -> str:
        return data["choices"][0]["message"]["content"]


class OllamaDescriber(_HTTPDescriber):
    def _request(self, image_b64: str) -> tuple[str, dict, dict]:
        llm = self.cfg.llm
        payload = {
            "model": vision_model(self.cfg),
            "messages": [{"role": "user", "content": VISION_PROMPT, "images": [image_b64]}],
            "stream": False,
            "options": {"temperature": llm.temperature},
            "keep_alive": llm.keep_alive,
        }
        return f"{llm.base_url.rstrip('/')}/api/chat", payload, {}

    @staticmethod
    def _extract(data: dict) -> str:
        return data["message"]["content"]


def make_describer(cfg: Config, transport: httpx.AsyncBaseTransport | None = None) -> Describer | None:
    if not cfg.vision.enabled:
        return None
    provider = cfg.llm.provider.strip().lower()
    if provider == "openai":
        return OpenAIDescriber(cfg, transport)
    if provider == "ollama":
        return OllamaDescriber(cfg, transport)
    raise ValueError(f"unknown [llm] provider {cfg.llm.provider!r} (use \"ollama\" or \"openai\")")


class FakeDescriber:
    """Test stand-in: returns the given replies in order (the last one repeats); None means always fail."""

    def __init__(self, replies: list[str] | None = None):
        self.replies = list(replies) if replies else []
        self.calls: list[str] = []

    async def describe(self, image_b64: str) -> str | None:
        self.calls.append(image_b64)
        if not self.replies:
            return None
        return self.replies.pop(0) if len(self.replies) > 1 else self.replies[0]
