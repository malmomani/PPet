import json

import httpx
import numpy as np
import pytest

from maya_brain.config import LLMConfig
from maya_brain.llm import FakeLLM, LLMError, OllamaLLM, extract_json


async def test_chat_sends_json_format_and_strips_think():
    seen = {}

    def handler(req):
        seen.update(json.loads(req.content))
        return httpx.Response(200, json={"message": {"content": "<think>hmm</think>{\"a\":1}"}})

    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(handler))
    out = await llm.chat([{"role": "user", "content": "x"}], json_mode=True)
    assert out.strip() == '{"a":1}'
    assert seen["format"] == "json" and seen["stream"] is False and seen["model"] == "qwen3:8b" and seen["think"] is False


async def test_chat_omits_think_when_none_and_format_when_not_json():
    seen = {}

    def handler(req):
        seen.update(json.loads(req.content))
        return httpx.Response(200, json={"message": {"content": "hi"}})

    llm = OllamaLLM(LLMConfig(think=None), transport=httpx.MockTransport(handler))
    assert await llm.chat([{"role": "user", "content": "x"}]) == "hi"
    assert "think" not in seen and "format" not in seen
    assert seen["options"] == {"temperature": 0.8, "num_ctx": 8192}


async def test_embed():
    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"embeddings": [[0.1, 0.2]]})))
    assert await llm.embed("x") == [0.1, 0.2]


async def test_errors_become_llmerror():
    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(lambda r: httpx.Response(500)))
    with pytest.raises(LLMError):
        await llm.chat([])

    def boom(r):
        raise httpx.ConnectError("down")

    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(boom))
    with pytest.raises(LLMError):
        await llm.embed("x")


async def test_bad_shape_becomes_llmerror():
    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"nope": 1})))
    with pytest.raises(LLMError):
        await llm.chat([])
    with pytest.raises(LLMError):
        await llm.embed("x")
    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(lambda r: httpx.Response(200, content=b"not json")))
    with pytest.raises(LLMError):
        await llm.chat([])


def test_strips_think_and_fences():
    assert extract_json('<think>x</think>```json\n{"a": {"b": 1}}\n```') == {"a": {"b": 1}}
    with pytest.raises(ValueError):
        extract_json("no json here")


async def test_fake_llm_replies_fail_and_embed_similarity():
    f = FakeLLM(["one", "two"])
    assert [await f.chat([]), await f.chat([]), await f.chat([])] == ["one", "two", "two"]
    f.fail_next = 1
    with pytest.raises(LLMError):
        await f.chat([])
    a, b, c = [np.array(await f.embed(t)) for t in ["ramen is great", "I love ramen", "tax forms"]]
    assert a @ b > a @ c


async def test_fake_llm_default_callable_calls_and_reassign():
    f = FakeLLM()
    assert json.loads(await f.chat([{"role": "user", "content": "x"}]))["say"] == "hehe"
    msgs = [{"role": "user", "content": "y"}]
    assert await FakeLLM(lambda m: f"n={len(m)}").chat(msgs) == "n=1"
    f.replies = ["a", "b"]
    assert [await f.chat(msgs), await f.chat(msgs)] == ["a", "b"]
    f.replies = ["c", "d"]
    assert await f.chat(msgs) == "c"
    assert len(f.calls) == 4 and f.calls[1] == msgs and f.calls[1] is not msgs
    f.fail_next = 1
    with pytest.raises(LLMError):
        await f.embed("x")
    v = await f.embed("hello world")
    assert len(v) == 64 and abs(np.linalg.norm(v) - 1) < 1e-9


async def test_chat_sends_num_ctx():
    seen = {}

    def handler(req):
        seen.update(json.loads(req.content))
        return httpx.Response(200, json={"message": {"content": "hi"}})

    await OllamaLLM(LLMConfig(), transport=httpx.MockTransport(handler)).chat([])
    assert seen["options"]["num_ctx"] == 8192
    await OllamaLLM(LLMConfig(num_ctx=4096), transport=httpx.MockTransport(handler)).chat([])
    assert seen["options"]["num_ctx"] == 4096


async def test_keep_alive_sent_and_timeout_default():
    seen = []

    def handler(req):
        seen.append(json.loads(req.content))
        if req.url.path == "/api/embed":
            return httpx.Response(200, json={"embeddings": [[0.1]]})
        return httpx.Response(200, json={"message": {"content": "hi"}})

    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(handler))
    await llm.chat([])
    await llm.embed("x")
    assert [p["keep_alive"] for p in seen] == ["30m", "30m"]
    assert LLMConfig().timeout_s == 120.0


async def test_warm_up_loads_both_models():
    paths = []

    def handler(req):
        paths.append((req.url.path, json.loads(req.content)["model"]))
        if req.url.path == "/api/embed":
            return httpx.Response(200, json={"embeddings": [[0.1]]})
        return httpx.Response(200, json={"message": {"content": ""}})

    await OllamaLLM(LLMConfig(), transport=httpx.MockTransport(handler)).warm_up()
    assert paths == [("/api/chat", "qwen3:8b"), ("/api/embed", "nomic-embed-text")]


async def test_warm_up_raises_when_ollama_down():
    def boom(req):
        raise httpx.ConnectError("refused")

    with pytest.raises(LLMError):
        await OllamaLLM(LLMConfig(), transport=httpx.MockTransport(boom)).warm_up()


async def test_http_error_names_the_server_message():
    import httpx

    from maya_brain.config import LLMConfig
    from maya_brain.llm import LLMError, OllamaLLM

    transport = httpx.MockTransport(lambda req: httpx.Response(400, json={"error": "input length exceeds the context length"}))
    llm = OllamaLLM(LLMConfig(), transport=transport)
    with pytest.raises(LLMError, match="HTTP 400: input length exceeds the context length"):
        await llm.embed("hi")


def test_keep_alive_bare_numbers_become_numbers():
    from maya_brain.llm import keep_alive

    assert keep_alive("-1") == -1 and keep_alive(" 300 ") == 300 and keep_alive("1.5") == 1.5
    assert keep_alive("30m") == "30m" and keep_alive("-1h") == "-1h" and keep_alive(-1) == -1


async def test_embed_sends_minus_one_as_a_number():
    import httpx

    from maya_brain.config import LLMConfig
    from maya_brain.llm import OllamaLLM

    seen = []

    def handler(req):
        seen.append(json.loads(req.content))
        return httpx.Response(200, json={"embeddings": [[0.1]]})

    llm = OllamaLLM(LLMConfig(keep_alive="-1"), transport=httpx.MockTransport(handler))
    await llm.embed("hi")
    assert seen[0]["keep_alive"] == -1
