"""WebSocket gateway: exposes Mind to the phone over a token-protected socket."""

import asyncio
import hmac
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from .config import Config
from .mind import Mind
from .protocol import Action, Error, parse_event

log = logging.getLogger(__name__)

CLOSE_AUTH = 4401
CLOSE_REPLACED = 4000


def create_app(cfg: Config, mind: Mind) -> FastAPI:
    active: WebSocket | None = None
    tasks: set[asyncio.Task] = set()

    async def sink(action: Action) -> None:
        ws = active
        if ws is None:
            return
        try:
            await ws.send_text(action.model_dump_json())
        except Exception:
            log.debug("dropping action: socket closed", exc_info=True)

    mind.set_sink(sink)
    mind.set_connected(False)  # nobody is listening until a phone connects

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        stop = asyncio.Event()
        ticker = asyncio.create_task(mind.run_ticker(stop))
        try:
            yield
        finally:
            stop.set()
            await ticker

    app = FastAPI(lifespan=lifespan)

    @app.get("/health")
    async def health() -> dict:
        return {"ok": True}

    def _handled(task: asyncio.Task) -> None:
        tasks.discard(task)
        if not task.cancelled() and task.exception() is not None:
            log.error("event handling failed", exc_info=task.exception())

    @app.websocket("/ws")
    async def ws_endpoint(ws: WebSocket) -> None:
        nonlocal active
        await ws.accept()
        token = ws.query_params.get("token", "")
        if not hmac.compare_digest(token.encode(), cfg.server.token.encode()):
            await ws.send_text(Error(code="auth", message="invalid token").model_dump_json())
            await ws.close(code=CLOSE_AUTH)
            return

        previous = active
        active = ws
        mind.set_connected(True)
        if previous is not None:
            try:
                await previous.close(code=CLOSE_REPLACED)
            except Exception:
                log.debug("previous socket already closed", exc_info=True)

        try:
            while True:
                raw = await ws.receive_text()
                try:
                    event = parse_event(raw)
                except Exception:
                    await sink_to(ws, Error(code="bad_message", message="invalid message"))
                    continue
                if event is None:
                    continue
                task = asyncio.create_task(mind.handle(event))
                tasks.add(task)
                task.add_done_callback(_handled)
        except WebSocketDisconnect:
            pass
        finally:
            if active is ws:
                active = None
                mind.set_connected(False)

    async def sink_to(ws: WebSocket, action: Action) -> None:
        try:
            await ws.send_text(action.model_dump_json())
        except Exception:
            log.debug("could not send to socket", exc_info=True)

    return app
