import json
import math
import re
import zlib
from collections.abc import Callable
from typing import Protocol
from urllib.parse import urlparse

import httpx

from maya_brain.config import LLMConfig

_THINK_RE = re.compile(r"<think>.*?</think>", re.DOTALL)
_FENCE_RE = re.compile(r"```(?:json)?", re.IGNORECASE)
_WORD_RE = re.compile(r"[a-z0-9]+")

DEFAULT_THOUGHT = (
    '{"thought":"","speak":true,"say":"hehe","animation":"happy",'
    '"mood":"happy","mood_intensity":0.6,"closeness_delta":1}'
)
EMBED_DIM = 64


class LLMError(Exception):
    pass


class LLM(Protocol):
    async def chat(self, messages: list[dict], json_mode: bool = False) -> str: ...

    async def embed(self, text: str) -> list[float]: ...


def _strip_think(text: str) -> str:
    return _THINK_RE.sub("", text)


def strip_reasoning(text: str) -> str:
    """Drop <think> blocks; if only a closing tag shows up (the opener was eaten), keep what follows the last one."""
    if "</think>" in text:
        text = text.rsplit("</think>", 1)[1]
    return _strip_think(text)


_HOSTED_OPENAI_SUFFIXES = ("openai.com", "openai.azure.com")


def openai_think_fields(cfg: LLMConfig) -> dict:
    """llama-server style `chat_template_kwargs` for `think`; hosted OpenAI/Azure endpoints would reject it."""
    if cfg.think is None:
        return {}
    host = (urlparse(cfg.base_url).hostname or "").lower()
    if any(host == suffix or host.endswith("." + suffix) for suffix in _HOSTED_OPENAI_SUFFIXES):
        return {}
    return {"chat_template_kwargs": {"enable_thinking": cfg.think}}


def extract_json(text: str) -> dict:
    text = _FENCE_RE.sub("", _strip_think(text))
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end < start:
        raise ValueError("no JSON object found")
    try:
        value = json.loads(text[start : end + 1])
    except json.JSONDecodeError as e:
        raise ValueError(f"invalid JSON: {e}") from e
    if not isinstance(value, dict):
        raise ValueError("JSON is not an object")
    return value


class OllamaLLM:
    def __init__(self, cfg: LLMConfig, transport: httpx.AsyncBaseTransport | None = None):
        self.cfg = cfg
        self._transport = transport

    async def _post(self, path: str, payload: dict, timeout_s: float | None = None) -> dict:
        try:
            async with httpx.AsyncClient(
                base_url=self.cfg.base_url, timeout=timeout_s or self.cfg.timeout_s, transport=self._transport
            ) as client:
                resp = await client.post(path, json=payload)
                resp.raise_for_status()
                data = resp.json()
        except (httpx.HTTPError, ValueError) as e:
            raise LLMError(f"{path} failed: {e!r}") from e
        if not isinstance(data, dict):
            raise LLMError(f"{path} returned unexpected shape")
        return data

    async def chat(self, messages: list[dict], json_mode: bool = False) -> str:
        payload: dict = {
            "model": self.cfg.chat_model,
            "messages": messages,
            "stream": False,
            "options": {"temperature": self.cfg.temperature, "num_ctx": self.cfg.num_ctx},
            "keep_alive": self.cfg.keep_alive,
        }
        if json_mode:
            payload["format"] = "json"
        if self.cfg.think is not None:
            payload["think"] = self.cfg.think
        data = await self._post("/api/chat", payload)
        try:
            content = data["message"]["content"]
        except (KeyError, TypeError) as e:
            raise LLMError("/api/chat returned unexpected shape") from e
        if not isinstance(content, str):
            raise LLMError("/api/chat returned unexpected shape")
        return _strip_think(content)

    async def embed(self, text: str) -> list[float]:
        data = await self._post(
            "/api/embed", {"model": self.cfg.embed_model, "input": text, "keep_alive": self.cfg.keep_alive}
        )
        try:
            vec = data["embeddings"][0]
            return [float(x) for x in vec]
        except (KeyError, IndexError, TypeError, ValueError) as e:
            raise LLMError("/api/embed returned unexpected shape") from e

    async def warm_up(self) -> None:
        """Load both models into memory so the first real message isn't stuck behind a cold load."""
        timeout = self.cfg.warmup_timeout_s
        chat = {"model": self.cfg.chat_model, "messages": [], "keep_alive": self.cfg.keep_alive,
                "options": {"num_ctx": self.cfg.num_ctx}}
        await self._post("/api/chat", chat, timeout)
        embed = {"model": self.cfg.embed_model, "input": "hello", "keep_alive": self.cfg.keep_alive}
        await self._post("/api/embed", embed, timeout)


class OpenAILLM:
    """Chat through any OpenAI-compatible server (llama-server, LM Studio, ...). Embeddings stay on Ollama."""

    def __init__(self, cfg: LLMConfig, transport: httpx.AsyncBaseTransport | None = None):
        self.cfg = cfg
        self._transport = transport

    async def _post(self, url: str, payload: dict, headers: dict | None = None, timeout_s: float | None = None) -> dict:
        try:
            async with httpx.AsyncClient(timeout=timeout_s or self.cfg.timeout_s, transport=self._transport) as client:
                resp = await client.post(url, json=payload, headers=headers)
                resp.raise_for_status()
                data = resp.json()
        except (httpx.HTTPError, ValueError) as e:
            raise LLMError(f"{url} failed: {e!r}") from e
        if not isinstance(data, dict):
            raise LLMError(f"{url} returned unexpected shape")
        return data

    async def _chat(self, payload: dict, timeout_s: float | None = None) -> str:
        headers = {"Authorization": f"Bearer {self.cfg.api_key}"} if self.cfg.api_key else None
        url = f"{self.cfg.base_url.rstrip('/')}/chat/completions"
        data = await self._post(url, payload, headers, timeout_s)
        try:
            content = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as e:
            raise LLMError("/chat/completions returned unexpected shape") from e
        if not isinstance(content, str):
            raise LLMError("/chat/completions returned unexpected shape")
        return strip_reasoning(content)

    async def chat(self, messages: list[dict], json_mode: bool = False) -> str:
        payload: dict = {
            "model": self.cfg.chat_model,
            "messages": messages,
            "temperature": self.cfg.temperature,
            "stream": False,
            **openai_think_fields(self.cfg),
        }
        if json_mode:
            payload["response_format"] = {"type": "json_object"}
        return await self._chat(payload)

    async def embed(self, text: str) -> list[float]:
        url = f"{self.cfg.embed_base_url.rstrip('/')}/api/embed"
        data = await self._post(
            url, {"model": self.cfg.embed_model, "input": text, "keep_alive": self.cfg.keep_alive}
        )
        try:
            vec = data["embeddings"][0]
            return [float(x) for x in vec]
        except (KeyError, IndexError, TypeError, ValueError) as e:
            raise LLMError("/api/embed returned unexpected shape") from e

    async def warm_up(self) -> None:
        """One tiny chat request, then the Ollama embedding model, so the first real message isn't stuck."""
        payload = {
            "model": self.cfg.chat_model,
            "messages": [{"role": "user", "content": "hi"}],
            "max_tokens": 1,
            "stream": False,
            **openai_think_fields(self.cfg),
        }
        await self._chat(payload, self.cfg.warmup_timeout_s)
        embed = {"model": self.cfg.embed_model, "input": "hello", "keep_alive": self.cfg.keep_alive}
        await self._post(f"{self.cfg.embed_base_url.rstrip('/')}/api/embed", embed, timeout_s=self.cfg.warmup_timeout_s)


def make_llm(cfg: LLMConfig, transport: httpx.AsyncBaseTransport | None = None) -> "OllamaLLM | OpenAILLM":
    provider = cfg.provider.strip().lower()
    if provider == "ollama":
        return OllamaLLM(cfg, transport)
    if provider == "openai":
        return OpenAILLM(cfg, transport)
    raise ValueError(f"unknown [llm] provider {cfg.provider!r} (use \"ollama\" or \"openai\")")


class FakeLLM:
    def __init__(self, replies: list[str] | Callable[[list[dict]], str] | None = None):
        self.replies = replies
        self.calls: list[list[dict]] = []
        self.fail_next = 0

    def _maybe_fail(self) -> None:
        if self.fail_next > 0:
            self.fail_next -= 1
            raise LLMError("FakeLLM forced failure")

    async def chat(self, messages: list[dict], json_mode: bool = False) -> str:
        self._maybe_fail()
        self.calls.append(list(messages))
        replies = self.replies
        if replies is None:
            return DEFAULT_THOUGHT
        if callable(replies):
            return replies(messages)
        if len(replies) > 1:
            return replies.pop(0)
        return replies[0] if replies else DEFAULT_THOUGHT

    async def embed(self, text: str) -> list[float]:
        self._maybe_fail()
        vec = [0.0] * EMBED_DIM
        for word in _WORD_RE.findall(text.lower()):
            vec[zlib.crc32(word.encode()) % EMBED_DIM] += 1.0
        norm = math.sqrt(sum(x * x for x in vec))
        return [x / norm for x in vec] if norm else vec
