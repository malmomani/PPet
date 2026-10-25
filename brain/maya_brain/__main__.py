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
from .config import load_config
from .db import open_db
from .gateway import create_app
from .llm import LLMError, OllamaLLM
from .mind import Mind
from .tts import load_kokoro


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

    llm = OllamaLLM(cfg.llm)
    print("Loading Maya's models (the first time can take a minute)...")
    try:
        asyncio.run(llm.warm_up())
    except LLMError as e:
        print(f"Warning: can't reach Ollama at {cfg.llm.base_url} yet ({e}); starting anyway.", file=sys.stderr)
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
