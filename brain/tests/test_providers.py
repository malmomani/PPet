import json

import httpx
import pytest

from maya_brain.config import Config, LLMConfig, VisionConfig, load_config
from maya_brain.llm import LLMError, OllamaLLM, OpenAILLM, make_llm

OPENAI = dict(provider="openai", base_url="http://localhost:8080/v1", chat_model="qwen3.6-35b-a3b")


def reply(content="hi"):
    return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": content}}]})


async def test_openai_chat_request_shape():
    seen = []

    def handler(req):
        seen.append((req, json.loads(req.content)))
        return reply("hello")

    msgs = [{"role": "user", "content": "x"}]
    llm = OpenAILLM(LLMConfig(**OPENAI, temperature=0.5), transport=httpx.MockTransport(handler))
    assert await llm.chat(msgs) == "hello"
    req, body = seen[0]
    assert str(req.url) == "http://localhost:8080/v1/chat/completions"
    assert body == {
        "model": "qwen3.6-35b-a3b", "messages": msgs, "temperature": 0.5, "stream": False,
        "chat_template_kwargs": {"enable_thinking": False},  # think defaults to false
    }
    assert "authorization" not in req.headers

    await llm.chat(msgs, json_mode=True)
    assert seen[1][1]["response_format"] == {"type": "json_object"}

    keyed = OpenAILLM(LLMConfig(**OPENAI, api_key="sk-test"), transport=httpx.MockTransport(handler))
    await keyed.chat(msgs)
    assert seen[2][0].headers["authorization"] == "Bearer sk-test"


async def test_openai_trailing_slash_in_base_url():
    seen = []
    cfg = LLMConfig(**{**OPENAI, "base_url": "http://localhost:8080/v1/"})
    llm = OpenAILLM(cfg, transport=httpx.MockTransport(lambda r: seen.append(str(r.url)) or reply()))
    await llm.chat([])
    assert seen == ["http://localhost:8080/v1/chat/completions"]


async def test_openai_strips_think_and_errors():
    llm = OpenAILLM(LLMConfig(**OPENAI), transport=httpx.MockTransport(lambda r: reply("<think>hmm</think>ok")))
    assert (await llm.chat([])).strip() == "ok"

    bad = [
        httpx.Response(500),
        httpx.Response(200, content=b"not json"),
        httpx.Response(200, json={"nope": 1}),
        httpx.Response(200, json={"choices": []}),
        httpx.Response(200, json={"choices": [{"message": {"content": None}}]}),
    ]
    for resp in bad:
        llm = OpenAILLM(LLMConfig(**OPENAI), transport=httpx.MockTransport(lambda r, resp=resp: resp))
        with pytest.raises(LLMError):
            await llm.chat([])

    def boom(r):
        raise httpx.ConnectError("down")

    with pytest.raises(LLMError):
        await OpenAILLM(LLMConfig(**OPENAI), transport=httpx.MockTransport(boom)).chat([])


async def test_openai_embeds_via_ollama():
    seen = {}

    def handler(req):
        seen["url"] = str(req.url)
        seen["body"] = json.loads(req.content)
        return httpx.Response(200, json={"embeddings": [[0.1, 0.2]]})

    cfg = LLMConfig(**OPENAI, embed_base_url="http://ollama:11434")
    assert await OpenAILLM(cfg, transport=httpx.MockTransport(handler)).embed("x") == [0.1, 0.2]
    assert seen["url"] == "http://ollama:11434/api/embed"
    assert seen["body"]["model"] == "nomic-embed-text" and seen["body"]["input"] == "x"

    for resp in (httpx.Response(500), httpx.Response(200, json={"nope": 1})):
        llm = OpenAILLM(cfg, transport=httpx.MockTransport(lambda r, resp=resp: resp))
        with pytest.raises(LLMError):
            await llm.embed("x")


async def test_openai_warm_up_is_one_tiny_chat_and_warms_embeddings():
    seen = []

    def handler(req):
        seen.append(req.url.path)
        if req.url.path == "/api/embed":
            return httpx.Response(200, json={"embeddings": [[0.1]]})
        return reply()

    await OpenAILLM(LLMConfig(**OPENAI), transport=httpx.MockTransport(handler)).warm_up()
    assert seen == ["/v1/chat/completions", "/api/embed"]
    with pytest.raises(LLMError):
        await OpenAILLM(LLMConfig(**OPENAI), transport=httpx.MockTransport(lambda r: httpx.Response(503))).warm_up()


async def test_openai_think_kwargs_gated_by_host():
    seen = []

    def handler(req):
        seen.append(json.loads(req.content))
        if req.url.path == "/api/embed":
            return httpx.Response(200, json={"embeddings": [[0.1]]})
        return reply()

    async def run(**kw):
        seen.clear()
        llm = OpenAILLM(LLMConfig(**{**OPENAI, **kw}), transport=httpx.MockTransport(handler))
        await llm.chat([])
        chat = seen[0]
        seen.clear()
        await llm.warm_up()
        return chat, seen[0]

    for body in await run(think=False):
        assert body["chat_template_kwargs"] == {"enable_thinking": False}
    for url in ("http://192.168.1.5:8080/v1", "http://100.101.2.3:8080/v1", "http://127.0.0.1:8080/v1",
                "http://[::1]:8080/v1", "http://10.1.2.3/v1", "http://172.20.0.4/v1", "http://gpu-box.local:8080/v1"):
        for body in await run(think=True, base_url=url):
            assert body["chat_template_kwargs"] == {"enable_thinking": True}
    for url in ("https://api.openai.com/v1", "https://openrouter.ai/api/v1", "https://api.groq.com/openai/v1",
                "http://172.32.0.1/v1", "http://100.128.0.1/v1", "http://localhost.evil.com/v1", "http://8.8.8.8/v1"):
        for body in await run(think=False, base_url=url):
            assert "chat_template_kwargs" not in body
    for body in await run(think=None):
        assert "chat_template_kwargs" not in body


async def test_openai_keeps_text_after_last_closing_think():
    llm = OpenAILLM(LLMConfig(**OPENAI), transport=httpx.MockTransport(lambda r: reply("reasoning without opener</think>\n\nfinal")))
    assert (await llm.chat([])).strip() == "final"


def test_make_llm_picks_provider():
    assert isinstance(make_llm(LLMConfig()), OllamaLLM)
    assert isinstance(make_llm(LLMConfig(provider="openai")), OpenAILLM)
    assert isinstance(make_llm(LLMConfig(provider=" OpenAI ")), OpenAILLM)
    with pytest.raises(ValueError, match="provider"):
        make_llm(LLMConfig(provider="gemini"))


def test_config_defaults_and_toml(tmp_path):
    cfg = Config()
    assert cfg.llm.provider == "ollama" and cfg.llm.api_key == ""
    assert cfg.llm.embed_base_url == "http://localhost:11434"
    assert cfg.vision == VisionConfig(enabled=True, model="", max_side=1024)

    p = tmp_path / "m.toml"
    p.write_text(
        '[llm]\nprovider="openai"\nbase_url="http://localhost:8080/v1"\napi_key="k"\n'
        'embed_base_url="http://10.0.0.2:11434"\n[vision]\nenabled=false\nmodel="m"\nmax_side=512\n'
    )
    loaded = load_config(p)
    assert (loaded.llm.provider, loaded.llm.api_key) == ("openai", "k")
    assert loaded.llm.embed_base_url == "http://10.0.0.2:11434"
    assert loaded.vision == VisionConfig(enabled=False, model="m", max_side=512)


def test_build_backends_logs_provider_and_vision(caplog):
    import logging

    from maya_brain.__main__ import build_backends
    from maya_brain.vision import make_describer

    caplog.set_level(logging.INFO, logger="maya_brain")
    cfg = Config(llm=LLMConfig(**OPENAI))
    llm, describer = build_backends(cfg)
    assert isinstance(llm, OpenAILLM) and describer is not None
    assert "openai" in caplog.text and "qwen3.6-35b-a3b" in caplog.text and "vision on" in caplog.text

    caplog.clear()
    llm, describer = build_backends(Config(vision=VisionConfig(enabled=False)))
    assert isinstance(llm, OllamaLLM) and describer is None and "vision off" in caplog.text
    assert make_describer(Config(vision=VisionConfig(enabled=False))) is None


def test_main_refuses_unknown_provider(tmp_path, capsys):
    import maya_brain.__main__ as entry

    cfg = tmp_path / "m.toml"
    cfg.write_text('[server]\ntoken = "a-long-enough-secret"\n[llm]\nprovider = "gemini"\n')
    assert entry.main(["--config", str(cfg)]) == 2
    assert "provider" in capsys.readouterr().err


def test_build_backends_warns_when_openai_points_at_ollama(caplog):
    import logging

    from maya_brain.__main__ import build_backends

    caplog.set_level(logging.WARNING, logger="maya_brain")
    build_backends(Config(llm=LLMConfig(provider="openai", base_url="http://localhost:11434/")))
    assert "11434" in caplog.text and "8080" in caplog.text
    caplog.clear()
    build_backends(Config(llm=LLMConfig(**OPENAI)))
    build_backends(Config())
    assert caplog.text == ""


async def test_openai_warm_up_names_embeddings_server_on_embed_failure():
    from maya_brain.llm import EmbedWarmUpError

    def handler(req):
        return httpx.Response(500) if req.url.path == "/api/embed" else reply()

    with pytest.raises(EmbedWarmUpError):
        await OpenAILLM(LLMConfig(**OPENAI), transport=httpx.MockTransport(handler)).warm_up()


def test_main_names_embeddings_server_when_only_embed_warm_up_fails(tmp_path, monkeypatch, capsys):
    import maya_brain.__main__ as entry
    from maya_brain.llm import EmbedWarmUpError

    async def failing(self):
        raise EmbedWarmUpError("boom")

    monkeypatch.setattr(OpenAILLM, "warm_up", failing)
    monkeypatch.setattr(entry, "Mind", lambda *a, **kw: object())
    monkeypatch.setattr(entry, "create_app", lambda cfg, mind: object())
    monkeypatch.setattr(entry.uvicorn, "run", lambda app, **kw: None)
    cfg = tmp_path / "m.toml"
    cfg.write_text(
        f'db_path = "{tmp_path / "m.db"}"\n[server]\ntoken = "a-long-enough-secret"\n[voice]\nenabled = false\n'
        '[llm]\nprovider = "openai"\nbase_url = "http://localhost:8080/v1"\nembed_base_url = "http://ollama-box:11434"\n'
    )
    assert entry.main(["--config", str(cfg)]) == 0
    err = capsys.readouterr().err
    assert "embeddings server" in err and "http://ollama-box:11434" in err and "8080" not in err
