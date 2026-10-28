"""Terminal simulator: chat with Maya and inject fake phone events on a virtual clock.

Run: python -m maya_brain.sim [--config PATH] [--fake-llm] [--db PATH] [--voice]
"""

import argparse
import asyncio
import base64
import json
import re
import sqlite3
import sys
from collections.abc import Callable
from datetime import datetime, timedelta
from pathlib import Path

from .clock import FakeClock
from .config import load_config
from .db import open_db
from .llm import EmbedWarmUpError, FakeLLM, LLMError, make_llm
from .mind import Mind
from .protocol import (
    Action,
    Animate,
    AppClosed,
    AppOpened,
    Audio,
    BodyEvent,
    BodyMomentEvent,
    Charging,
    Error,
    Event,
    GlanceEvent,
    Hello,
    Move,
    NotificationEvent,
    Say,
    Screen,
    State,
    Typing,
    UserDrag,
    UserMessage,
    UserShush,
    UserTap,
)
from .tts import TTS, FakeTTS, TTSError, load_kokoro

PROMPT = "you> "
MAX_TEXT = 2000
VOICE_WAV = "maya-voice-last.wav"
SHOW_NAMES = ("thoughts", "mood", "facts", "traits", "diary", "status")

HELP = """Commands:
  <text>            say something to Maya
  /tap              tap Maya
  /throw            pick Maya up and throw her
  /shush            ask Maya to be quiet for a while
  /app <Label>      open an app (e.g. /app TikTok)
  /close            close the current app (reports how long it was open)
  /notify <sender> [app] [group]  someone messaged the user (app defaults to WhatsApp)
  /glance <app> <line>[ | <line>...] [targets=send,like]  what is on the user's screen (targets: tease tags)
  /body <doing> [where] [target]  report her body (doing: idle walk sit perch jump fall nap peek tease dragged;
                    where: floor platform target)
  /moment fell|shooed [target]  her body: she fell, or the user shooed her off a target
  /screen on|off    turn the phone screen on or off
  /charge on|off    plug in or unplug the charger
  /time HH:MM       jump the virtual clock forward to the next HH:MM (today or tomorrow)
  /skip <N>m|h|d    advance the virtual clock, up to 30d (e.g. /skip 90m, /skip 3h, /skip 2d)
  /reflect          run Maya's reflection now (facts, traits, diary)
  /say-voice <text> speak text aloud with Maya's voice (needs --voice)
  /thoughts         Maya's last 5 private thoughts
  /mood             current mood, closeness and tier
  /facts            what Maya knows about you
  /traits           traits Maya has learned
  /diary            Maya's recent diary entries
  /status           full status dump
  /help             this list
  /quit             leave"""

_TARGETS_RE = re.compile(r"(?:^|\s)targets=(\S*)\s*$")
_SKIP_RE = re.compile(r"^(\d+)([mhd])$")
_TIME_RE = re.compile(r"^(\d{1,2}):(\d{2})$")
_UNITS = {"m": "minutes", "h": "hours", "d": "days"}
_UNIT_MINUTES = {"m": 1, "h": 60, "d": 1440}
MAX_SKIP = timedelta(days=30)


def parse_command(line: str, now: datetime, voice: bool = False) -> tuple[str, object]:
    """Turn one input line into a (kind, payload) pair for run_sim to dispatch.

    With `voice`, typed lines count as spoken (like a hold-to-talk message on the phone).
    """
    line = line.strip()
    if not line.startswith("/"):
        return "event", UserMessage(text=line[:MAX_TEXT], voice=voice)
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
    if cmd == "notify":
        sender, app, group = (arg.split(None, 2) + [None, None, None])[:3]
        if not sender:
            return help_
        app = app or "WhatsApp"
        return "event", NotificationEvent(package=app.lower(), app=app[:100], sender=sender[:100], group=group and group[:100])
    if cmd == "glance":
        app, _, rest = arg.partition(" ")
        if not app:
            return help_
        m = _TARGETS_RE.search(rest)
        targets = m.group(1).split(",") if m else []
        if m:
            rest = rest[: m.start()]
        lines = [part.strip() for part in rest.split("|")]
        return "event", GlanceEvent(package=app.lower(), app=app[:100], lines=lines, targets=targets)
    if cmd == "body":
        doing, where, target = (arg.split() + [None, None, None])[:3]
        if not doing:
            return help_
        return "event", BodyEvent(doing=doing, where=where, target=target)
    if cmd == "moment":
        kind, target, *_ = (arg.split() + [None, None])
        if kind not in ("fell", "shooed"):
            return help_
        return "event", BodyMomentEvent(kind=kind, target=target)
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
        target = now.replace(hour=int(m.group(1)), minute=int(m.group(2)), second=0, microsecond=0)
        if target <= now:
            target += timedelta(days=1)  # the virtual clock only moves forward
        return "time", target
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
    if cmd == "say-voice":
        return ("say_voice", arg[:MAX_TEXT]) if arg else help_
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
    if isinstance(action, Move):
        return f"[move] {action.intent} {action.target}" if action.target else f"[move] {action.intent}"
    if isinstance(action, Typing) and action.on:
        return "(Maya is thinking…)"
    return None


def _play_wav(path: Path) -> None:
    """Play a WAV file on Windows (stdlib winsound); elsewhere the file is only saved."""
    if sys.platform != "win32":
        return
    import winsound

    winsound.PlaySound(str(path), winsound.SND_FILENAME)


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
    *,
    voice: bool = False,
    wav_path: Path | None = None,
    play: Callable[[Path], None] = _play_wav,
) -> None:
    """Read-eval-print loop. Ends on /quit or EOFError from `read`.

    With `voice`, typed lines are sent as spoken messages and Maya's `audio` actions are saved to
    `wav_path` (default maya-voice-last.wav in the current directory) and played via `play`.
    """
    wav_path = wav_path if wav_path is not None else Path(VOICE_WAV)
    fresh_audio = False
    step = timedelta(seconds=tick_s if tick_s is not None else mind.cfg.initiative.tick_s)
    app: tuple[str, str, datetime] | None = None  # (package, label, opened_at)

    def keep_audio(wav: bytes) -> None:
        nonlocal fresh_audio
        try:
            wav_path.write_bytes(wav)
        except OSError as exc:
            write(f"[error could not save {wav_path}: {exc}]")
            return
        write(f"[audio {max(1, round(len(wav) / 1024))} KB]")
        fresh_audio = True

    async def sink(action: Action) -> None:
        if isinstance(action, Audio):
            keep_audio(base64.b64decode(action.data_b64))
            return
        text = _render(action)
        if text is not None:
            write(text)

    async def play_pending() -> None:
        """Wait for Maya's voice (it is synthesized in the background), then play what arrived."""
        nonlocal fresh_audio
        await mind.wait_voice()
        if fresh_audio:
            fresh_audio = False
            try:
                play(wav_path)
            except Exception as exc:  # no sound device etc.: the file is still saved
                write(f"[error could not play audio: {type(exc).__name__}: {exc}]")

    mind.set_sink(sink)

    async def guarded(coro) -> None:
        try:
            await coro
        except Exception as exc:  # keep the REPL alive whatever the brain does
            write(f"[error {type(exc).__name__}: {exc}]")

    if voice:
        await guarded(mind.handle(Hello(client_version="sim", screen_w=1, screen_h=1, voice_mode="replies")))

    while True:
        try:
            line = read()
        except EOFError:
            return
        if not line.strip():
            continue
        try:
            kind, payload = parse_command(line, clock.now(), voice)

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
            elif kind == "say_voice":
                if mind.tts is None:
                    write("(voice unavailable)")
                else:
                    try:
                        keep_audio(await mind.tts.synth(payload))
                    except TTSError as exc:
                        write(f"[voice error: {exc}]")
            await play_pending()

        except Exception as exc:  # one bad line must never kill the REPL
            write(f"[error {type(exc).__name__}: {exc}]")

def start_time(db: sqlite3.Connection, now: datetime) -> datetime:
    """Start the virtual clock no earlier than the newest stored turn, so time never runs backwards."""
    row = db.execute("SELECT ts FROM turns ORDER BY id DESC LIMIT 1").fetchone()
    if row is None:
        return now
    return max(now, datetime.fromisoformat(row["ts"]).astimezone(now.tzinfo))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="maya_brain.sim", description="Chat with Maya in the terminal")
    parser.add_argument("--config", default="maya.toml", help="path to config TOML (default: maya.toml)")
    parser.add_argument("--fake-llm", action="store_true", help="use a canned fake LLM instead of Ollama")
    parser.add_argument("--voice", action="store_true", help="speak Maya's replies (typed lines count as spoken)")
    parser.add_argument("--db", default="maya-sim.db", help="SQLite file for the simulator (default: maya-sim.db)")
    args = parser.parse_args(argv)

    cfg = load_config(Path(args.config))
    try:
        llm = FakeLLM() if args.fake_llm else make_llm(cfg.llm)
    except ValueError as e:
        print(f"Can't start: {e}", file=sys.stderr)
        return 1
    if hasattr(llm, "warm_up"):
        print("Waking Maya up (loading the model; the first time can take a minute)...")
        try:
            asyncio.run(llm.warm_up())
        except EmbedWarmUpError as e:
            print(f"Can't reach the embeddings server (Ollama) at {cfg.llm.embed_base_url}: {e}", file=sys.stderr)
            print("Memory search needs Ollama with nomic-embed-text (ollama pull nomic-embed-text).", file=sys.stderr)
            return 1
        except LLMError as e:
            if cfg.llm.provider.strip().lower() == "ollama":
                print(f"Can't reach Ollama at {cfg.llm.base_url}: {e}", file=sys.stderr)
                print("Is Ollama running, and are the models pulled? Try: ollama list", file=sys.stderr)
            else:
                print(f"Can't reach the language model server at {cfg.llm.base_url}: {e}", file=sys.stderr)
                print("Is llama-server still running, and does chat_model match? See brain/README.md.", file=sys.stderr)
            return 1
    db = open_db(args.db)
    clock = FakeClock(start_time(db, datetime.now().astimezone()))
    tts: TTS | None = None
    if args.voice:
        if args.fake_llm:
            tts = FakeTTS()
        elif cfg.voice.enabled:
            tts = load_kokoro(cfg.voice, base_dir=Path(args.config).resolve().parent)
        else:
            print("Voice is switched off in the config ([voice] enabled = false).", file=sys.stderr)
    mind = Mind(cfg, llm, db, clock, tts=tts)

    print("Maya simulator. Type /help for commands, /quit to leave.")
    try:
        asyncio.run(run_sim(mind, clock, lambda: input(PROMPT), print, voice=args.voice))
    except KeyboardInterrupt:
        print()
    finally:
        db.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
