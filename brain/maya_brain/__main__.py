"""Run the Maya brain server: python -m maya_brain [--config PATH]."""

import argparse
import asyncio
import logging
import re
import socket
import sys
import threading
from pathlib import Path

import uvicorn

from .clock import SystemClock
from .config import Config, load_config
from .db import open_db
from .gateway import create_app
from .llm import LLM, LLMError, make_llm
from .mind import Mind
from .tts import load_kokoro
from .vision import Describer, make_describer, vision_model


log = logging.getLogger("maya_brain")

MIN_TOKEN_LEN = 16
_TOKEN_RE = re.compile(r"(token=)[^&\s\"']*")


class RedactTokens(logging.Filter):
    """Blank out `token=...` (uvicorn logs the full websocket URL, query string included)."""

    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        redacted = _TOKEN_RE.sub(r"\1***", message)
        if redacted != message:
            record.msg, record.args = redacted, None
        return True


REDACT_TOKENS = RedactTokens()


def _local_ipv4s() -> list[str]:
    ips = ["127.0.0.1"]
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.append(info[4][0])
    except OSError:
        pass
    return list(dict.fromkeys(ips))


def build_backends(cfg: Config) -> tuple[LLM, Describer | None]:
    """Pick the chat/embedding backend and the (optional) screenshot describer from the config."""
    llm = make_llm(cfg.llm)
    if cfg.llm.provider.strip().lower() == "openai" and cfg.llm.base_url.rstrip("/") == "http://localhost:11434":
        log.warning(
            "[llm] provider is \"openai\" but base_url is still Ollama's address (%s); "
            "llama-server normally listens on http://localhost:8080/v1",
            cfg.llm.base_url,
        )
    describer = make_describer(cfg)
    vision = "off" if describer is None else f"on (model {vision_model(cfg)})"
    log.info(
        "LLM provider %s, chat model %s at %s; vision %s",
        cfg.llm.provider, cfg.llm.chat_model, cfg.llm.base_url, vision,
    )
    return llm, describer


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="maya_brain", description="Maya brain server")
    parser.add_argument("--config", default="maya.toml", help="path to config TOML (default: maya.toml)")
    args = parser.parse_args(argv)

    cfg = load_config(Path(args.config))
    if cfg.server.token == "change-me" or len(cfg.server.token) < MIN_TOKEN_LEN:
        print(
            f"Refusing to start: server.token is the default 'change-me' or shorter than "
            f"{MIN_TOKEN_LEN} characters. Set a long secret token under [server] in {args.config}.",
            file=sys.stderr,
        )
        return 2

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    for handler in logging.getLogger().handlers:
        handler.addFilter(REDACT_TOKENS)  # covers uvicorn's records too: they propagate to root

    try:
        llm, describer = build_backends(cfg)  # describer: Task 2 hands it to Mind
    except ValueError as e:
        print(f"Refusing to start: {e}", file=sys.stderr)
        return 2
    print("Loading Maya's models (the first time can take a minute)...")
    try:
        asyncio.run(llm.warm_up())
    except LLMError as e:
        server = "Ollama" if cfg.llm.provider.strip().lower() == "ollama" else "the language model server"
        print(f"Warning: can't reach {server} at {cfg.llm.base_url} yet ({e}); starting anyway.", file=sys.stderr)
    db = open_db(cfg.db_path)
    tts = load_kokoro(cfg.voice, base_dir=Path(args.config).resolve().parent)
    if tts is not None:
        # Load the voice model while the server starts, so her first spoken reply isn't late.
        threading.Thread(target=tts.warm_up, name="kokoro-warm-up", daemon=True).start()
    mind = Mind(cfg, llm, db, SystemClock(), tts=tts)
    app = create_app(cfg, mind)

    for ip in _local_ipv4s():
        print(f"ws://{ip}:{cfg.server.port}/ws")
    try:
        # log_config=None: uvicorn uses our root handlers (and their token filter) instead of its own.
        uvicorn.run(app, host=cfg.server.host, port=cfg.server.port, access_log=False, log_config=None)
    finally:
        db.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
