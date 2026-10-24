"""Terminal simulator: chat with Maya and inject fake phone events on a virtual clock.

Run: python -m maya_brain.sim [--config PATH] [--fake-llm] [--db PATH]
"""

import argparse
import asyncio
import json
import re
import sys
from collections.abc import Callable
from datetime import datetime, timedelta
from pathlib import Path

from .clock import FakeClock
from .config import load_config
from .db import open_db
from .llm import FakeLLM, OllamaLLM
from .mind import Mind
from .protocol import (
    Action,
    Animate,
    AppClosed,
    AppOpened,
    Charging,
    Error,
    Event,
    Say,
    Screen,
    State,
    UserDrag,
    UserMessage,
    UserShush,
    UserTap,
)

PROMPT = "you> "
MAX_TEXT = 2000
SHOW_NAMES = ("thoughts", "mood", "facts", "traits", "diary", "status")

HELP = """Commands:
  <text>            say something to Maya
  /tap              tap Maya
  /throw            pick Maya up and throw her
  /shush            ask Maya to be quiet for a while
  /app <Label>      open an app (e.g. /app TikTok)
  /close            close the current app (reports how long it was open)
  /screen on|off    turn the phone screen on or off
  /charge on|off    plug in or unplug the charger
  /time HH:MM       set the virtual clock to that time today
  /skip <N>m|h|d    advance the virtual clock, up to 30d (e.g. /skip 90m, /skip 3h, /skip 2d)
  /reflect          run Maya's reflection now (facts, traits, diary)
  /thoughts         Maya's last 5 private thoughts
  /mood             current mood, closeness and tier
  /facts            what Maya knows about you
  /traits           traits Maya has learned
  /diary            Maya's recent diary entries
  /status           full status dump
  /help             this list
  /quit             leave"""

_SKIP_RE = re.compile(r"^(\d+)([mhd])$")
_TIME_RE = re.compile(r"^(\d{1,2}):(\d{2})$")
_UNITS = {"m": "minutes", "h": "hours", "d": "days"}
_UNIT_MINUTES = {"m": 1, "h": 60, "d": 1440}
MAX_SKIP = timedelta(days=30)


def parse_command(line: str, now: datetime) -> tuple[str, object]:
    """Turn one input line into a (kind, payload) pair for run_sim to dispatch."""
    line = line.strip()
    if not line.startswith("/"):
        return "event", UserMessage(text=line[:MAX_TEXT])
    cmd, _, arg = line[1:].partition(" ")
    cmd, arg = cmd.lower(), arg.strip()
    help_ = ("help", None)

    if cmd == "tap":
        return "event", UserTap()
    if cmd == "throw":
        return "event", UserDrag(thrown=True)
    if cmd == "shush":
        return "event", UserShush()
    if cmd == "app":
        if not arg:
            return help_
        return "event", AppOpened(package=arg.lower(), label=arg)
    if cmd == "close":
        return "close", None
    if cmd in ("screen", "charge"):
        if arg.lower() not in ("on", "off"):
            return help_
        on = arg.lower() == "on"
        return "event", (Screen(on=on) if cmd == "screen" else Charging(on=on))
    if cmd == "time":
        m = _TIME_RE.match(arg)
        if not m or int(m.group(1)) > 23 or int(m.group(2)) > 59:
            return help_
        return "time", now.replace(hour=int(m.group(1)), minute=int(m.group(2)), second=0, microsecond=0)
    if cmd == "skip":
        m = _SKIP_RE.match(arg.lower())
        if not m or int(m.group(1)) == 0:
            return help_
        if int(m.group(1)) * _UNIT_MINUTES[m.group(2)] > MAX_SKIP.total_seconds() // 60:
            return help_  # too long to simulate; also avoids timedelta/datetime overflow
        return "skip", timedelta(**{_UNITS[m.group(2)]: int(m.group(1))})
    if cmd in SHOW_NAMES:
        return "show", cmd
    if cmd == "reflect":
        return "reflect", None
    if cmd == "quit":
        return "quit", None
    return help_


def _fmt_delta(td: timedelta) -> str:
    minutes = int(td.total_seconds() // 60)
    if minutes % 1440 == 0:
        return f"{minutes // 1440}d"
    if minutes % 60 == 0:
        return f"{minutes // 60}h"
    return f"{minutes}m"


def _fmt_now(clock: FakeClock) -> str:
    return clock.now().strftime("%a %H:%M")


def _render(action: Action) -> str | None:
    if isinstance(action, Say):
        return f"Maya: {action.text}"
    if isinstance(action, Animate):
        return f"*{action.name}*"
    if isinstance(action, State):
        return f"[state mood={action.mood} tier={action.closeness_tier}]"
    if isinstance(action, Error):
        return f"[error {action.code}: {action.message}]"
    return None  # Typing is not shown


def _lines_or_none(lines: list[str]) -> list[str]:
    return lines or ["(none)"]


def _show(mind: Mind, name: str) -> list[str]:
    if name == "status":
        return [f"{k}: {v}" for k, v in mind.status().items()]
    if name == "mood":
        s = mind.status()
        return [f"mood={s['mood']} intensity={s['intensity']:.2f} closeness={s['closeness']:.1f} tier={s['tier']}"]
    if name == "facts":
        return _lines_or_none([f"{k}: {v}" for k, v in mind.memory.facts().items()])
    if name == "traits":
        return _lines_or_none([f"{t.kind}: {t.text} ({t.confidence:.2f})" for t in mind.persona.traits()])
    if name == "diary":
        return _lines_or_none([f"{date}: {entry}" for date, entry in mind.reflection.diary()])
    rows = mind.memory.db.execute("SELECT ts, trigger, json FROM thoughts ORDER BY id DESC LIMIT 5").fetchall()
    lines = []
    for row in reversed(rows):
        try:
            data = json.loads(row["json"])
        except ValueError:
            data = {}
        lines.append(f"[{row['trigger']}] thought: {data.get('thought', '')} | say: {data.get('say', '')}")
    return _lines_or_none(lines)


async def run_sim(
    mind: Mind,
    clock: FakeClock,
    read: Callable[[], str],
    write: Callable[[str], None],
    tick_s: float | None = None,
) -> None:
    """Read-eval-print loop. Ends on /quit or EOFError from `read`."""
    step = timedelta(seconds=tick_s if tick_s is not None else mind.cfg.initiative.tick_s)
    app: tuple[str, str, datetime] | None = None  # (package, label, opened_at)

    async def sink(action: Action) -> None:
        text = _render(action)
        if text is not None:
            write(text)

    mind.set_sink(sink)

    async def guarded(coro) -> None:
        try:
            await coro
        except Exception as exc:  # keep the REPL alive whatever the brain does
            write(f"[error {type(exc).__name__}: {exc}]")

    while True:
        try:
            line = read()
        except EOFError:
            return
        if not line.strip():
            continue
        try:
            kind, payload = parse_command(line, clock.now())

            if kind == "quit":
                return
            if kind == "help":
                write(HELP)
            elif kind == "event":
                if isinstance(payload, AppOpened):
                    app = (payload.package, payload.label, clock.now())
                await guarded(mind.handle(payload))
            elif kind == "close":
                if app is None:
                    write("(no app is open)")
                    continue
                package, label, opened = app
                app = None
                elapsed = max(0, int((clock.now() - opened).total_seconds()))
                await guarded(mind.handle(AppClosed(package=package, label=label, duration_s=elapsed)))
            elif kind == "time":
                clock.set(payload)
                write(f"[time set — now {_fmt_now(clock)}]")
            elif kind == "skip":
                end = clock.now() + payload
                while clock.now() < end:
                    clock.advance(min(step, end - clock.now()))
                    await guarded(mind.tick())
                write(f"[skipped {_fmt_delta(payload)} — now {_fmt_now(clock)}]")
            elif kind == "show":
                for text in _show(mind, payload):
                    write(text)
            elif kind == "reflect":
                write("[reflecting…]")
                await guarded(mind.reflection.reflect())
                write("[reflection done — see /facts /traits /diary]")

        except Exception as exc:  # one bad line must never kill the REPL
            write(f"[error {type(exc).__name__}: {exc}]")

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="maya_brain.sim", description="Chat with Maya in the terminal")
    parser.add_argument("--config", default="maya.toml", help="path to config TOML (default: maya.toml)")
    parser.add_argument("--fake-llm", action="store_true", help="use a canned fake LLM instead of Ollama")
    parser.add_argument("--db", default="maya-sim.db", help="SQLite file for the simulator (default: maya-sim.db)")
    args = parser.parse_args(argv)

    cfg = load_config(Path(args.config))
    llm = FakeLLM() if args.fake_llm else OllamaLLM(cfg.llm)
    db = open_db(args.db)
    clock = FakeClock(datetime.now().astimezone())
    mind = Mind(cfg, llm, db, clock)

    print("Maya simulator. Type /help for commands, /quit to leave.")
    try:
        asyncio.run(run_sim(mind, clock, lambda: input(PROMPT), print))
    except KeyboardInterrupt:
        print()
    finally:
        db.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
