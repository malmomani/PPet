"""WebSocket gateway: exposes Mind to the phone over a token-protected socket."""

import asyncio
import hmac
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from .config import Config
from .mind import Mind
from .protocol import Action, Error, SightEvent, SightFailedEvent, parse_event

log = logging.getLogger(__name__)

CLOSE_AUTH = 4401
CLOSE_REPLACED = 4000


def create_app(cfg: Config, mind: Mind) -> FastAPI:
    active: WebSocket | None = None
    tasks: set[asyncio.Task] = set()
    # The screenshot being described (one at a time), whether the user asked for it, and whose socket sent it.
    sight_task: asyncio.Task | None = None
    sight_asked = False
    sight_from: WebSocket | None = None

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
            if sight_task is not None:
                sight_task.cancel()
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
        nonlocal active, sight_task, sight_asked, sight_from
        await ws.accept()
        peer = ws.client.host if ws.client else "unknown"  # never log the URL: its query has the token
        token = ws.query_params.get("token", "")
        if not hmac.compare_digest(token.encode(), cfg.server.token.encode()):
            log.warning("rejected connection from %s: bad credentials", peer)
            await ws.send_text(Error(code="auth", message="invalid token").model_dump_json())
            await ws.close(code=CLOSE_AUTH)
            return

        previous = active
        active = ws
        mind.set_connected(True)
        log.info("phone connected from %s", peer)
        if previous is not None:
            try:
                await previous.close(code=CLOSE_REPLACED)
            except Exception:
                log.debug("previous socket already closed", exc_info=True)

        try:
            while True:
                try:
                    message = await ws.receive()
                except RuntimeError:  # socket was closed under us (e.g. replaced by a newer connection)
                    break
                if message["type"] == "websocket.disconnect":
                    break
                raw = message.get("text")
                if raw is None:  # binary frame: not part of the protocol
                    await sink_to(ws, Error(code="bad_message", message="invalid message"))
                    continue
                try:
                    event = parse_event(raw)
                except Exception:
                    await sink_to(ws, Error(code="bad_message", message="invalid message"))
                    continue
                finally:
                    message = raw = None  # a screenshot's bytes live on only in the event
                if event is None:
                    continue
                if isinstance(event, SightEvent):
                    asked = event.reason == "asked"
                    busy = sight_task is not None and not sight_task.done()
                    if busy and not asked:
                        log.info("dropping a %s: still describing the last one", event.type)
                        event = None  # let its picture go now
                        continue
                    if busy and sight_asked:  # two asked looks at once: this one gets "couldn't see"
                        log.info("can't describe an asked %s now: still describing the last one", event.type)
                        event = SightFailedEvent(reason="error")
                    else:
                        if busy:
                            log.info("cancelling an automatic sight for an asked one")
                            sight_task.cancel()
                        task = sight_task = asyncio.create_task(mind.handle_sight(event))
                        sight_asked, sight_from = asked, ws
                if not isinstance(event, SightEvent):
                    task = asyncio.create_task(mind.handle(event))
                tasks.add(task)
                task.add_done_callback(_handled)
        except WebSocketDisconnect:
            pass
        finally:
            if sight_from is ws and sight_task is not None:
                sight_task.cancel()  # nobody to show it to
            if active is ws:
                active = None
                mind.set_connected(False)
                log.info("phone disconnected")

    async def sink_to(ws: WebSocket, action: Action) -> None:
        try:
            await ws.send_text(action.model_dump_json())
        except Exception:
            log.debug("could not send to socket", exc_info=True)

    return app
