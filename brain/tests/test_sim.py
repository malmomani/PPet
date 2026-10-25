from datetime import datetime, timedelta

from maya_brain.protocol import (
    AppClosed,
    AppOpened,
    Charging,
    Screen,
    UserDrag,
    UserMessage,
    UserShush,
    UserTap,
)
from maya_brain.sim import main, parse_command, run_sim, start_time

NOW = datetime(2026, 10, 24, 14, 0).astimezone()


def test_parse_commands():
    assert parse_command("hello there", NOW)[1].text == "hello there"
    assert parse_command("/app TikTok", NOW)[1].label == "TikTok"
    assert parse_command("/skip 3h", NOW) == ("skip", timedelta(hours=3))
    assert parse_command("/skip 90m", NOW) == ("skip", timedelta(minutes=90))
    assert parse_command("/time 02:00", NOW)[1].hour == 2
    assert parse_command("/screen off", NOW)[1].on is False
    assert parse_command("/mood", NOW) == ("show", "mood")
    assert parse_command("/wat", NOW) == ("help", None)


def test_parse_command_details():
    kind, ev = parse_command("hello there", NOW)
    assert kind == "event" and isinstance(ev, UserMessage)
    kind, ev = parse_command("/app TikTok", NOW)
    assert isinstance(ev, AppOpened) and ev.package == "tiktok"
    assert isinstance(parse_command("/tap", NOW)[1], UserTap)
    assert parse_command("/throw", NOW)[1].thrown is True and isinstance(parse_command("/throw", NOW)[1], UserDrag)
    assert isinstance(parse_command("/shush", NOW)[1], UserShush)
    assert parse_command("/close", NOW) == ("close", None)
    assert isinstance(parse_command("/screen on", NOW)[1], Screen)
    assert parse_command("/charge on", NOW)[1].on is True
    assert isinstance(parse_command("/charge off", NOW)[1], Charging)
    assert parse_command("/skip 2d", NOW) == ("skip", timedelta(days=2))
    when = parse_command("/time 02:30", NOW)[1]  # already past today: tomorrow's 02:30
    assert (when.date(), when.hour, when.minute) == (NOW.date() + timedelta(days=1), 2, 30)
    assert when.tzinfo is not None
    for name in ("thoughts", "facts", "traits", "diary", "status"):
        assert parse_command(f"/{name}", NOW) == ("show", name)
    assert parse_command("/reflect", NOW) == ("reflect", None)
    assert parse_command("/quit", NOW) == ("quit", None)
    assert parse_command("/help", NOW) == ("help", None)


def test_parse_invalid_arguments_give_help():
    for line in ("/time 25:99", "/time", "/time abc", "/skip", "/skip 3x", "/skip -1h", "/screen maybe",
                 "/charge", "/app", "/app   ", "/skip 31d", "/skip 99999999999d", "/skip 43201m", "/skip 721h",
                 "/skip 0m"):
        assert parse_command(line, NOW) == ("help", None), line


async def test_scripted_session(mind, clock):
    lines = iter(["hi Maya", "/app TikTok", "/skip 1h", "/close", "/thoughts", "/quit"])
    out = []
    await run_sim(mind, clock, lambda: next(lines), out.append)
    text = "\n".join(out)
    assert "Maya: hehe" in text
    assert mind.initiative.current_app() is None


async def run_lines(mind, clock, *lines):
    it = iter(lines)
    out = []
    await run_sim(mind, clock, lambda: next(it), out.append)  # StopIteration is not EOFError: end with /quit
    return "\n".join(out)


async def test_close_sends_elapsed_and_clears_app(mind, clock):
    out = await run_lines(mind, clock, "/app TikTok", "/skip 10m", "/close", "/quit")
    assert mind.initiative.current_app() is None
    assert "skipped 10m" in out
    turns = [t.content for t in mind.memory.recent_turns()]
    assert any("TikTok for 10 min" in c for c in turns)


async def test_close_without_app_and_skip_advances_clock(mind, clock):
    start = clock.now()
    out = await run_lines(mind, clock, "/close", "/skip 3h", "/quit")
    assert "no app" in out.lower()
    assert clock.now() == start + timedelta(hours=3)
    assert "[skipped 3h" in out


async def test_time_sets_clock(mind, clock):
    await run_lines(mind, clock, "/time 02:00", "/quit")
    assert (clock.now().hour, clock.now().minute) == (2, 0)


async def test_actions_rendered_and_thinking_shown(mind, clock):
    out = await run_lines(mind, clock, "hi", "/quit")
    assert "Maya: hehe" in out
    assert "*happy*" in out
    assert "[state mood=" in out and "tier=" in out
    assert out.count("(Maya is thinking…)") == 1  # shown once when typing starts, not when it stops


async def test_show_commands(mind, clock):
    out = await run_lines(mind, clock, "hi", "/status", "/mood", "/facts", "/traits", "/diary", "/thoughts", "/quit")
    assert "closeness" in out
    assert out.count("(none)") >= 3
    assert "thought:" in out and "say: hehe" in out


async def test_reflect_and_help_and_eof(mind, clock):
    def read():
        if not lines:
            raise EOFError
        return lines.pop(0)

    lines = ["/reflect", "/wat", ""]
    out = []
    await run_sim(mind, clock, read, out.append)
    text = "\n".join(out)
    assert "/skip" in text  # help text listed
    assert "reflect" in text.lower()


def test_main_fake_llm_scripted(tmp_path, monkeypatch, capsys):
    import io

    monkeypatch.setattr("sys.stdin", io.StringIO("hi\n/app TikTok\n/skip 2h\n/quit\n"))
    code = main(["--fake-llm", "--db", str(tmp_path / "sim.db"), "--config", str(tmp_path / "none.toml")])
    assert code == 0
    assert "Maya: hehe" in capsys.readouterr().out


async def test_huge_skip_is_rejected_and_repl_continues(mind, clock):
    start = clock.now()
    out = await run_lines(mind, clock, "/skip 99999999999d", "/skip 31d", "/quit")
    assert out.count("Commands:") == 2
    assert clock.now() == start


def test_max_skip_is_accepted():
    assert parse_command("/skip 30d", NOW) == ("skip", timedelta(days=30))


async def test_exception_in_handle_does_not_end_session(mind, clock):
    async def boom(event):
        raise RuntimeError("kaboom")

    mind.handle = boom
    out = await run_lines(mind, clock, "hi", "/mood", "/quit")
    assert "[error RuntimeError: kaboom]" in out
    assert "mood=" in out


async def test_exception_in_dispatch_does_not_end_session(mind, clock, monkeypatch):
    def bad_status():
        raise ValueError("bad status")

    monkeypatch.setattr(mind, "status", bad_status)
    out = await run_lines(mind, clock, "/mood", "/facts", "/quit")
    assert "[error ValueError: bad status]" in out
    assert "(none)" in out


def test_time_jumps_forward_to_next_occurrence():
    assert parse_command("/time 15:30", NOW) == ("time", NOW.replace(hour=15, minute=30))
    assert parse_command("/time 14:00", NOW) == ("time", NOW + timedelta(days=1))
    assert parse_command("/time 09:00", NOW) == ("time", NOW.replace(hour=9) + timedelta(days=1))


async def test_time_never_moves_clock_backwards(mind, clock):
    start = clock.now()
    await run_lines(mind, clock, "/time 02:00", "/quit")
    assert clock.now() > start


def test_start_time_is_after_newest_turn(db):
    from maya_brain.memory import Memory
    from maya_brain.clock import FakeClock
    from maya_brain.llm import FakeLLM

    real_now = datetime.now().astimezone()
    assert start_time(db, real_now) == real_now
    future = real_now + timedelta(days=3)
    Memory(db, FakeLLM(), FakeClock(future)).add_turn("user", "from a sim session that skipped ahead")
    assert start_time(db, real_now) == future


def test_parse_say_voice_command():
    assert parse_command("/say-voice hello", NOW) == ("say_voice", "hello")
    assert parse_command("/say-voice hello there  ", NOW) == ("say_voice", "hello there")
    assert parse_command("/say-voice", NOW) == ("help", None)


def test_parse_voice_flag_marks_typed_lines():
    assert parse_command("hi", NOW)[1].voice is False
    assert parse_command("hi", NOW, voice=True)[1].voice is True
    assert parse_command("/tap", NOW, voice=True)[0] == "event"


def test_sim_voice_flag_writes_wav(tmp_path, monkeypatch, capsys):
    import io

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr("sys.stdin", io.StringIO("hi\n/quit\n"))
    code = main(["--fake-llm", "--voice", "--db", str(tmp_path / "sim.db"), "--config", str(tmp_path / "none.toml")])
    assert code == 0
    out = capsys.readouterr().out
    assert "[audio" in out and "KB]" in out
    wav = tmp_path / "maya-voice-last.wav"
    assert wav.is_file() and wav.read_bytes()[:4] == b"RIFF"


def test_sim_without_voice_flag_writes_nothing(tmp_path, monkeypatch, capsys):
    import io

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr("sys.stdin", io.StringIO("hi\n/say-voice hello\n/quit\n"))
    assert main(["--fake-llm", "--db", str(tmp_path / "sim.db"), "--config", str(tmp_path / "none.toml")]) == 0
    out = capsys.readouterr().out
    assert "[audio" not in out and "(voice unavailable)" in out
    assert not (tmp_path / "maya-voice-last.wav").exists()


async def test_say_voice_synthesizes_and_plays(mind, clock, tmp_path):
    from maya_brain.tts import FakeTTS

    mind.tts = FakeTTS()
    played = []
    wav = tmp_path / "last.wav"
    it = iter(["/say-voice hello 😊 there", "/quit"])
    out = []
    await run_sim(mind, clock, lambda: next(it), out.append, voice=True, wav_path=wav, play=played.append)
    assert mind.tts.calls == ["hello 😊 there"]
    assert wav.read_bytes()[:4] == b"RIFF" and played == [wav]
    assert any(line.startswith("[audio") for line in out)


async def test_say_voice_failure_is_reported(mind, clock, tmp_path):
    from maya_brain.tts import FakeTTS

    mind.tts = FakeTTS()
    mind.tts.fail_next = 1
    it = iter(["/say-voice hello", "/mood", "/quit"])
    out = []
    await run_sim(mind, clock, lambda: next(it), out.append, voice=True, wav_path=tmp_path / "l.wav", play=lambda p: None)
    assert any("voice error" in line for line in out) and any("mood=" in line for line in out)


async def test_voice_session_sends_hello_and_voices_replies(mind, clock, tmp_path):
    from maya_brain.tts import FakeTTS

    mind.tts = FakeTTS()
    played = []
    it = iter(["hi", "/quit"])
    out = []
    await run_sim(mind, clock, lambda: next(it), out.append, voice=True, wav_path=tmp_path / "l.wav", play=played.append)
    assert mind.voice_mode == "replies"
    assert played == [tmp_path / "l.wav"] and mind.tts.calls == ["hehe"]
    assert out.index("Maya: hehe") < next(i for i, line in enumerate(out) if line.startswith("[audio"))


def test_sim_voice_warns_once_when_kokoro_missing(tmp_path, monkeypatch, capsys):
    import io

    from maya_brain.llm import OllamaLLM

    async def no_warm_up(self):
        return None

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(OllamaLLM, "warm_up", no_warm_up)
    monkeypatch.setattr("sys.stdin", io.StringIO("/quit\n"))
    assert main(["--voice", "--db", str(tmp_path / "sim.db"), "--config", str(tmp_path / "none.toml")]) == 0
    captured = capsys.readouterr()
    text = captured.out + captured.err
    assert text.count('pip install -e ".[voice]"') == 1 and "python -m maya_brain.fetch_voice" in text
