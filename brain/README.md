# Maya brain

## What it is

The brain server for Maya, an AI companion who lives on your phone. The phone app is only the body: it sends what happens (messages, taps, which app you opened) to this server over a WebSocket, and the server decides how Maya feels, what she remembers and when she speaks up. The language model runs locally through [Ollama](https://ollama.com).

This package also ships a terminal simulator, so you can chat with Maya and fake phone events before the Android app exists.

## Requirements

- Python 3.11 or newer
- [Ollama](https://ollama.com/download) (Windows, Linux or macOS). A GPU such as an RTX 3070 (8 GB) runs `qwen3:8b` comfortably.

## Setup

Run these from the `brain/` folder.

Create and activate a virtual environment:

```
python -m venv .venv
```

- Linux / macOS: `source .venv/bin/activate`
- Windows (PowerShell): `.venv\Scripts\Activate.ps1`
- Windows (cmd): `.venv\Scripts\activate.bat`

Install the package (add `[dev]` to also get the test tools):

```
pip install -e .
```

Pull the models:

```
ollama pull qwen3:8b
ollama pull nomic-embed-text
```

Create your config and set a secret token:

- Linux / macOS: `cp maya.example.toml maya.toml`
- Windows: `copy maya.example.toml maya.toml`

Open `maya.toml` and replace `token = "change-me"` under `[server]` with a long random string, for example the output of `python -c "import secrets; print(secrets.token_urlsafe(32))"`. The server refuses to start while the token is still `change-me` or shorter than 16 characters. `maya.toml` is git-ignored.

## Run the simulator

Without Ollama (canned replies, handy for trying the commands):

```
python -m maya_brain.sim --fake-llm
```

With Ollama (make sure `ollama serve` is running):

```
python -m maya_brain.sim
```

Options: `--config PATH` (default `maya.toml`), `--db PATH` (default `maya-sim.db`; delete it to start fresh), `--fake-llm`.

Type plain text to talk to Maya. Slash commands inject events on a virtual clock:

| Command | What it does |
| --- | --- |
| `/tap`, `/throw`, `/shush` | Tap, throw, or ask Maya to be quiet for a while |
| `/app <Label>`, `/close` | Open an app (e.g. `/app TikTok`); close it and report how long it was open |
| `/screen on\|off`, `/charge on\|off` | Screen and charger events |
| `/time HH:MM` | Jump the virtual clock forward to the next HH:MM (today or tomorrow) |
| `/skip 90m\|3h\|2d` | Advance the clock, letting Maya's periodic thinking run along the way |
| `/reflect` | Run the daily reflection now (facts, traits, diary) |
| `/thoughts`, `/mood`, `/facts`, `/traits`, `/diary`, `/status` | Look inside Maya |
| `/help`, `/quit` | List commands, leave |

## Run the server

```
python -m maya_brain --config maya.toml
```

It prints the `ws://<ip>:8765/ws` addresses the phone can use, and serves `/health` for a quick check. The host defaults to `0.0.0.0`, so anyone who can reach the port and knows the token can talk to Maya.

- Only expose the port on your home LAN or over [Tailscale](https://tailscale.com); never forward it through your router to the internet.
- Allow inbound TCP 8765 in your firewall for the private network only (on Windows, a Defender Firewall inbound rule set to the Private profile).
- To reach your PC away from home, install Tailscale on the PC and phone and use the PC's Tailscale address (or set `host` to that address in `maya.toml`).

## Phone app

The Android app that shows Maya on your phone has its own install and pairing guide: see [`../android/README.md`](../android/README.md).

## Privacy

Everything stays on your machine. Conversations, memories, facts, traits and the diary are stored in the SQLite file `maya.db` (the simulator uses `maya-sim.db`), and the language model runs locally through Ollama. Nothing is sent to a cloud service. Delete the file to make Maya forget everything.

## Tests

```
pip install -e ".[dev]"
pytest -q
```
