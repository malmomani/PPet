"""Run the Maya brain server: python -m maya_brain [--config PATH]."""

import argparse
import logging
import socket
import sys
from pathlib import Path

import uvicorn

from .clock import SystemClock
from .config import load_config
from .db import open_db
from .gateway import create_app
from .llm import OllamaLLM
from .mind import Mind


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
    if cfg.server.token == "change-me":
        print(
            f"Refusing to start: server.token is still the default 'change-me'. "
            f"Set a secret token under [server] in {args.config}.",
            file=sys.stderr,
        )
        return 2

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    llm = OllamaLLM(cfg.llm)
    db = open_db(cfg.db_path)
    mind = Mind(cfg, llm, db, SystemClock())
    app = create_app(cfg, mind)

    for ip in _local_ipv4s():
        print(f"ws://{ip}:{cfg.server.port}/ws")
    uvicorn.run(app, host=cfg.server.host, port=cfg.server.port)
    return 0


if __name__ == "__main__":
    sys.exit(main())
