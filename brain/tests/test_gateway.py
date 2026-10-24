import warnings

import pytest

with warnings.catch_warnings():
    # Starlette's TestClient warns that it wants `httpx2`; third-party noise, not ours.
    warnings.filterwarnings("ignore", message=".*starlette.testclient.*")
    from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from maya_brain.config import Config
from maya_brain.db import open_db
from maya_brain.gateway import create_app
from maya_brain.llm import FakeLLM
from maya_brain.mind import Mind


@pytest.fixture
def client(clock):
    cfg = Config()
    cfg.server.token = "t"
    conn = open_db(":memory:")
    mind = Mind(cfg, FakeLLM(), conn, clock)
    app = create_app(cfg, mind)
    with TestClient(app) as c:
        yield c
    conn.close()


def test_health(client):
    assert client.get("/health").json() == {"ok": True}


def test_bad_token(client):
    with client.websocket_connect("/ws?token=nope") as ws:
        assert ws.receive_json()["code"] == "auth"
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_json()
        assert exc.value.code == 4401


def test_missing_token(client):
    with client.websocket_connect("/ws") as ws:
        assert ws.receive_json()["code"] == "auth"
        with pytest.raises(WebSocketDisconnect):
            ws.receive_json()


def test_chat_roundtrip(client):
    with client.websocket_connect("/ws?token=t") as ws:
        ws.send_json({"type": "user_message", "text": "hi"})
        got = [ws.receive_json() for _ in range(4)]
        assert any(m["type"] == "say" and m["text"] == "hehe" for m in got)


def test_bad_frames_do_not_kill_socket(client):
    with client.websocket_connect("/ws?token=t") as ws:
        ws.send_text("{nope")
        assert ws.receive_json()["code"] == "bad_message"
        ws.send_json({"type": "teleport"})
        ws.send_json({"type": "hello", "client_version": "1", "screen_w": 1, "screen_h": 1})
        assert ws.receive_json()["type"] == "state"


def test_bad_message_does_not_echo_input(client):
    with client.websocket_connect("/ws?token=t") as ws:
        ws.send_text("{secret-user-text")
        err = ws.receive_json()
        assert err["code"] == "bad_message"
        assert "secret" not in err["message"]


def test_new_connection_replaces_old(client):
    hello = {"type": "hello", "client_version": "1", "screen_w": 1, "screen_h": 1}
    with client.websocket_connect("/ws?token=t") as old:
        with client.websocket_connect("/ws?token=t") as new:
            with pytest.raises(WebSocketDisconnect) as exc:
                old.receive_json()
            assert exc.value.code == 4000
            new.send_json(hello)
            assert new.receive_json()["type"] == "state"
        # old closing after being replaced must not have cleared the new socket
    with client.websocket_connect("/ws?token=t") as again:
        again.send_json(hello)
        assert again.receive_json()["type"] == "state"


def test_main_refuses_default_token(tmp_path, capsys):
    from maya_brain.__main__ import main

    assert main(["--config", str(tmp_path / "none.toml")]) == 2
    assert "token" in capsys.readouterr().err


def test_connection_state_follows_socket(clock):
    cfg = Config()
    cfg.server.token = "t"
    conn = open_db(":memory:")
    mind = Mind(cfg, FakeLLM(), conn, clock)
    app = create_app(cfg, mind)
    hello = {"type": "hello", "client_version": "1", "screen_w": 1, "screen_h": 1}
    try:
        with TestClient(app) as c:
            assert mind.connected is False
            with c.websocket_connect("/ws?token=t") as ws:
                ws.send_json(hello)
                ws.receive_json()
                assert mind.connected is True
            with c.websocket_connect("/ws?token=t") as ws:  # handler of the first socket has finished
                ws.send_json(hello)
                ws.receive_json()
            assert mind.connected is False
    finally:
        conn.close()


def test_binary_frame_does_not_kill_socket(client):
    with client.websocket_connect("/ws?token=t") as ws:
        ws.send_bytes(b"\x00\x01")
        assert ws.receive_json()["code"] == "bad_message"
        ws.send_json({"type": "hello", "client_version": "1", "screen_w": 1, "screen_h": 1})
        assert ws.receive_json()["type"] == "state"


async def test_receive_runtime_error_ends_handler_quietly(clock):
    cfg = Config()
    cfg.server.token = "t"
    conn = open_db(":memory:")
    mind = Mind(cfg, FakeLLM(), conn, clock)
    app = create_app(cfg, mind)
    # A second "connect" mid-session makes Starlette's receive() raise RuntimeError, like a closed socket.
    incoming = [{"type": "websocket.connect"}, {"type": "websocket.connect"}]
    out = []

    async def receive():
        return incoming.pop(0)

    async def send(message):
        out.append(message)

    scope = {
        "type": "websocket", "path": "/ws", "raw_path": b"/ws", "query_string": b"token=t", "headers": [],
        "scheme": "ws", "server": ("testserver", 80), "client": ("phone", 1), "root_path": "", "subprotocols": [],
    }
    try:
        await app(scope, receive, send)
        assert out[0]["type"] == "websocket.accept"
        assert mind.connected is False
    finally:
        conn.close()
