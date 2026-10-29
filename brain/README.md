# Maya brain

## What it is

The brain server for Maya, an AI companion who lives on your phone. The phone app is only the body: it sends what happens (messages, taps, which app you opened) to this server over a WebSocket, and the server decides how Maya feels, what she remembers and when she speaks up. The language model runs locally through [Ollama](https://ollama.com).

This package also ships a terminal simulator, so you can chat with Maya and fake phone events before the Android app exists.

## Requirements

- Python 3.11 or newer (3.11–3.13 if you want her [voice](#voice))
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

Options: `--config PATH` (default `maya.toml`), `--db PATH` (default `maya-sim.db`; delete it to start fresh), `--fake-llm`, `--voice` (see [Voice](#voice)).

Type plain text to talk to Maya. Slash commands inject events on a virtual clock:

| Command | What it does |
| --- | --- |
| `/tap`, `/throw`, `/shush` | Tap, throw, or ask Maya to be quiet for a while |
| `/app <Label>`, `/close` | Open an app (e.g. `/app TikTok`); close it and report how long it was open |
| `/notify <sender> [app] [group]` | Pretend someone messaged the user (app defaults to WhatsApp); Maya sees who, never the text |
| `/glance <app> <line>[ \| <line>...] [targets=send,like]` | Pretend the user's screen shows these lines (and these buttons: `send like play search share comment follow`) |
| `/body <doing> [where] [target]` | Report where Maya's body is (`/body perch platform`, `/body tease target send`) |
| `/moment fell\|shooed [target]` | Her body: she fell, or the user shooed her off a button; `move` actions from the brain print as `[move] <intent> <target>` |
| `/sight <app> <description>` | Pretend Maya sees this on the screen of the app opened with `/app` (no picture or vision model needed). It answers her last `[look]`, else it counts as a look of her own |
| `/act_result <status> [detail]` | Answer her last `[act] <kind> <fields>` with `done`, `declined`, `timeout`, `failed` or `blocked` |
| `/screen on\|off`, `/charge on\|off` | Screen and charger events |
| `/time HH:MM` | Jump the virtual clock forward to the next HH:MM (today or tomorrow) |
| `/skip 90m\|3h\|2d` | Advance the clock, letting Maya's periodic thinking run along the way |
| `/reflect` | Run the daily reflection now (facts, traits, diary) |
| `/say-voice <text>` | Speak the text in Maya's voice (needs `--voice`) |
| `/thoughts`, `/mood`, `/facts`, `/traits`, `/diary`, `/status` | Look inside Maya |
| `/help`, `/quit` | List commands, leave |

## Voice

Maya can answer out loud with [Kokoro](https://github.com/thewh1teagle/kokoro-onnx), a small text-to-speech model that runs locally on the CPU. It is optional: without it she simply stays text-only.

Voice needs **Python 3.11–3.13**: `kokoro-onnx` doesn't install on Python 3.14 or newer yet. Check with `python --version` inside the activated venv. If it says 3.14, make the venv with Python 3.13 instead (install it from [python.org](https://www.python.org/downloads/) first if needed), from the `brain/` folder:

- Windows: delete the `.venv` folder, then `py -3.13 -m venv .venv`
- Linux / macOS: `rm -rf .venv`, then `python3.13 -m venv .venv`

Activate it again and reinstall (`pip install -e .`) as in [Setup](#setup).

Then, from the `brain/` folder, install the extra and download the model files (a few hundred MB, saved to `models/`, which is git-ignored):

```
pip install -e ".[voice]"
python -m maya_brain.fetch_voice
```

Voice is on by default once the models are there. If `[voice]` is enabled but the models or the package are missing, the server prints one warning at startup saying why (the missing files, or the import error such as "DLL load failed") and carries on without voice; on Python 3.14+ it also reminds you of the version limit. A broken `onnxruntime` install also shows up as "unavailable". When voice is ready, the server loads the model in the background at startup, so her first spoken reply isn't late. Tune it in `maya.toml`:

```toml
[voice]
enabled = true            # master switch
voice = "af_heart"        # a voice, or a blend: "af_sky:0.6,af_nicole:0.4"
speed = 1.0
pitch = 0.0               # semitones; +2 to +4 sounds younger, speed is kept
model_path = "models/kokoro-v1.0.onnx"    # relative to the folder holding maya.toml
voices_path = "models/voices-v1.0.bin"
```

### Pick Maya's voice

The stock voices are adult; raise the pitch a little and/or blend two voices to find hers. The voice lab renders one line in several voices at pitch +0, +2 and +3.5 into `voice-lab/` (run from `brain/`):

```
python -m maya_brain.voice_lab --play
python -m maya_brain.voice_lab --voices af_sky af_nicole "af_sky:0.6,af_nicole:0.4" --pitches 2,3,4 --play
```

Each file is named after its settings (`af_sky_p+2.wav` = `voice = "af_sky"`, `pitch = 2.0`). Put the winner in `maya.toml` and restart.

The phone decides when she speaks (its voice mode: off, replies or always). Try it in the simulator, where typed lines count as spoken and each reply is saved to `maya-voice-last.wav` (and played on Windows):

```
python -m maya_brain.sim --voice
```

Use `/say-voice <text>` to hear any sentence. With `--fake-llm --voice` the simulator uses a silent stand-in voice, which is handy for checking the plumbing. Audio is generated on your PC and sent to your phone; nothing goes to a cloud service.

## Bigger brain with llama-server (optional)

Ollama and `qwen3:8b` work out of the box. If you have a strong PC, you can give Maya a much bigger model that also **sees pictures**: the Mixture-of-Experts model Qwen3.6-35B-A3B served by [llama.cpp](https://github.com/ggml-org/llama.cpp)'s `llama-server`. Only about 3 billion of its 35 billion parameters are active per word, and `--n-cpu-moe` keeps the experts in system RAM, so it can run on an 8 GB graphics card with plenty of RAM (the reference setup is an RTX 3070 8 GB with 32 GB DDR5). Maya talks to it through the OpenAI-style API (`provider = "openai"`).

1. Download the llama.cpp Windows release zip that matches your graphics card (the CUDA build for NVIDIA) from the [releases page](https://github.com/ggml-org/llama.cpp/releases) and unzip it, for example to `C:\llama.cpp`.
2. Open PowerShell in that folder and start the server:

   ```
   .\llama-server -hf unsloth/Qwen3.6-35B-A3B-GGUF:UD-IQ4_XS --jinja -ngl 99 --n-cpu-moe 28 -c 16384 --reasoning-budget 0 -b 2048 -ub 2048 --cache-reuse 256 --port 8080
   ```

   `-hf` downloads the model **and its vision projector** on the first run (it is a big download, so be patient). Repository and quantisation names change often, so check them on [Hugging Face](https://huggingface.co/unsloth) and adjust the part after `-hf` if needed. Leave this window open while Maya runs.

   Thinking: Maya sends `think = false` from `maya.toml` with every request, which switches the model's thinking off. To make the server do the same for every client, add `--reasoning-budget 0` (or `--chat-template-kwargs '{"enable_thinking":false}'`) to the command. Either way, verify on your PC that replies come back quickly (see the speed check below).
   Speed flags: `-b 2048 -ub 2048` read Maya's prompt much faster when experts live in RAM, and `--cache-reuse 256` lets the server reuse the unchanged start of her prompt (Maya puts the fixed part first), so each reply only reads what's new. In the server log, `prompt eval time` is the reading and `eval time` the writing.
3. Tune `--n-cpu-moe`: it is the number of layers whose experts stay in system RAM. A **lower** number is faster, until the graphics card runs out of memory (then the server fails to start or slows to a crawl); raise it by a few and retry. Lower `-c` if you are still short of VRAM.
4. Keep Ollama running too: embeddings (her memory search) still come from `nomic-embed-text` there (`ollama pull nomic-embed-text`).
5. In `maya.toml`:

   ```toml
   [llm]
   provider = "openai"
   base_url = "http://localhost:8080/v1"
   chat_model = "qwen3.6-35b-a3b"
   embed_base_url = "http://localhost:11434"
   embed_model = "nomic-embed-text"
   ```

   With this provider screenshots are described by the same model (`[vision] model` is empty). Set `[vision] enabled = false` to switch picture descriptions off.
6. Check the speed: run `python -m maya_brain.sim`, send a test message and see how long the reply takes. Speeds quoted online for this kind of setup are community reports; verify them on your own PC and adjust `--n-cpu-moe` accordingly.

**Privacy:** `provider = "openai"` is meant for a server on your own PC. If you point `base_url` at a cloud API instead, Maya's prompts (chats, memories, screen text and picture descriptions) go to that provider.

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

## Seeing the screen and using the phone

When the phone shows mostly pictures, or when you ask Maya to look ("look at this"), the app sends her a screenshot. The brain has the vision model (`[vision]` in `maya.toml`) describe it in a few words, without holding up the chat, and then drops the picture: it is never written to disk or logged. Maya keeps the description in mind for about 10 minutes and remembers "you looked at …" in her conversation history. If vision is off or fails, she simply doesn't see it (when you asked, she tells you).

With Ollama (the default) the vision model is `gemma3:4b`; pull it once:

```
ollama pull gemma3:4b
```

On an 8 GB GPU it doesn't fit next to `qwen3:8b`, so Ollama swaps the two models in and out: each look then takes a few extra seconds, while chat goes on. If the vision model isn't there (HTTP 404), the brain logs a warning naming the model and the command above, and skips her automatic looks for 30 minutes (looks you ask for still try).

She can also do small things on the phone: open an app, play/pause or skip music, set a timer or an alarm, change the volume, search the web, scroll, press a button and type text. The phone's "Let Maya use my phone" switch is off by default, and the phone asks you first before anything risky (pressing send, buy, delete…, or typing). When you ask her in a message (typed or spoken), she just does it (the phone still asks about risky ones). On her own (also when you only tap her, or when she answers a look) she only opens apps, controls music and volume, sets timers and alarms, or scrolls: never presses, types or searches the web. She does that only once she is your friend, never in quiet hours or after you shushed her, at most 10 times an hour, and not at all after the phone said its "Let Maya use my phone" switch is off (until the phone reconnects or something you asked for works). She remembers how each one went, and when something you asked for didn't happen, she tells you.

## Privacy

Everything stays on your machine. Conversations, memories, facts, traits and the diary are stored in the SQLite file `maya.db` (the simulator uses `maya-sim.db`), and the language model runs locally through Ollama (or llama-server, if you set it up). Nothing is sent to a cloud service unless you point `base_url` at one (see above). Delete the file to make Maya forget everything.

## Tests

```
pip install -e ".[dev]"
pytest -q
```
