import json
import logging

import httpx

from maya_brain.config import Config, LLMConfig, VisionConfig
from maya_brain.vision import VISION_PROMPT, FakeDescriber, make_describer

IMG = "QUJD"


def cfg_for(provider, **vision):
    llm = LLMConfig(provider=provider, base_url="http://localhost:8080/v1" if provider == "openai" else "http://localhost:11434",
                    chat_model="qwen3.6-35b-a3b" if provider == "openai" else "qwen3:8b", timeout_s=42.0)
    return Config(llm=llm, vision=VisionConfig(**vision))


def test_prompt_is_exact():
    assert VISION_PROMPT == (
        "This is a screenshot of the user's phone. In 1-3 short sentences, describe what the user is looking at "
        "(the pictures, not the menus). The small pixel girl is Maya herself; don't describe her."
    )


def test_disabled_gives_none():
    assert make_describer(cfg_for("openai", enabled=False)) is None


async def test_describe_openai_shape():
    seen = {}

    def handler(req):
        seen["req"], seen["body"] = req, json.loads(req.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": "<think>x</think> A cat photo. "}}]})

    d = make_describer(cfg_for("openai"), transport=httpx.MockTransport(handler))
    assert await d.describe(IMG) == "A cat photo."
    assert str(seen["req"].url) == "http://localhost:8080/v1/chat/completions"
    assert seen["body"]["model"] == "qwen3.6-35b-a3b" and seen["body"]["stream"] is False
    assert seen["body"]["messages"] == [{"role": "user", "content": [
        {"type": "text", "text": VISION_PROMPT},
        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{IMG}"}},
    ]}]
    assert "authorization" not in seen["req"].headers

    cfg = cfg_for("openai", model="vis")
    cfg.llm.api_key = "sk-1"
    d = make_describer(cfg, transport=httpx.MockTransport(handler))
    await d.describe(IMG)
    assert seen["body"]["model"] == "vis" and seen["req"].headers["authorization"] == "Bearer sk-1"


async def test_describe_ollama_shape():
    seen = {}

    def handler(req):
        seen["url"], seen["body"] = str(req.url), json.loads(req.content)
        return httpx.Response(200, json={"message": {"content": "A map."}})

    d = make_describer(cfg_for("ollama"), transport=httpx.MockTransport(handler))
    assert await d.describe(IMG) == "A map."
    assert seen["url"] == "http://localhost:11434/api/chat"
    assert seen["body"]["model"] == "gemma3:4b" and seen["body"]["stream"] is False
    assert seen["body"]["messages"] == [{"role": "user", "content": VISION_PROMPT, "images": [IMG]}]

    d = make_describer(cfg_for("ollama", model="llava"), transport=httpx.MockTransport(handler))
    await d.describe(IMG)
    assert seen["body"]["model"] == "llava"


async def test_describe_timeout_comes_from_llm_config():
    seen = {}

    class T(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request):
            seen["timeout"] = request.extensions["timeout"]
            return httpx.Response(200, json={"message": {"content": "ok"}})

    await make_describer(cfg_for("ollama"), transport=T()).describe(IMG)
    assert seen["timeout"]["read"] == 42.0


async def test_describe_trims_and_fails_quietly(caplog):
    long = "word " * 200
    d = make_describer(cfg_for("openai"), transport=httpx.MockTransport(
        lambda r: httpx.Response(200, json={"choices": [{"message": {"content": long}}]})))
    out = await d.describe(IMG)
    assert out is not None and 0 < len(out) <= 300

    secret = "SECRET-DESCRIPTION"
    cases = [
        httpx.Response(500, text=secret),
        httpx.Response(200, content=b"not json"),
        httpx.Response(200, json={"choices": []}),
        httpx.Response(200, json={"choices": [{"message": {"content": "   "}}]}),
        httpx.Response(200, json={"choices": [{"message": {"content": "<think>only</think>"}}]}),
    ]
    caplog.set_level(logging.DEBUG)
    for resp in cases:
        d = make_describer(cfg_for("openai"), transport=httpx.MockTransport(lambda r, resp=resp: resp))
        assert await d.describe(IMG) is None

    def boom(r):
        raise httpx.ConnectError("down")

    assert await make_describer(cfg_for("ollama"), transport=httpx.MockTransport(boom)).describe(IMG) is None
    assert secret not in caplog.text and IMG not in caplog.text


async def test_fake_describer():
    d = FakeDescriber(["one", "two"])
    assert await d.describe(IMG) == "one" and await d.describe(IMG) == "two" and await d.describe(IMG) == "two"
    assert d.calls == [IMG, IMG, IMG]
    assert await FakeDescriber(None).describe(IMG) is None


async def test_describe_openai_think_kwargs_and_stray_closing_think():
    bodies = []

    def handler(req):
        bodies.append(json.loads(req.content))
        return httpx.Response(200, json={"choices": [{"message": {"content": "thoughts</think> A dog."}}]})

    cfg = cfg_for("openai")
    cfg.llm.think = False
    assert await make_describer(cfg, transport=httpx.MockTransport(handler)).describe(IMG) == "A dog."
    assert bodies[0]["chat_template_kwargs"] == {"enable_thinking": False}

    cfg.llm.base_url = "https://api.openai.com/v1"
    await make_describer(cfg, transport=httpx.MockTransport(handler)).describe(IMG)
    assert "chat_template_kwargs" not in bodies[1]
