# Maya — Design Spec (v1)

Date: 2026-10-24
Status: Draft, awaiting review

## 1. Vision

Maya is a personal AI companion that lives on the user's Android phone (Samsung
S23 Ultra) as a small animated character roaming over other apps — in the
spirit of Yui from Sword Art Online. She has her own personality, thinks before
she acts, decides on her own when to speak, remembers the user over time, and
grows closer or more distant depending on how she is treated.

This is a personal project for a single user. Not intended for the Play Store.

### Success criteria for v1

- Maya is visible over any app, walks/sits/naps on screen edges, can be dragged,
  and never blocks touches outside her sprite.
- Tapping Maya opens a chat; she replies in character within a few seconds.
- She notices which app is in use and occasionally comments unprompted, without
  being annoying (cooldowns, caps, quiet hours).
- She remembers facts and past conversations across days and references them.
- Her mood and closeness visibly change her behavior over time.
- She writes a nightly diary entry the user can read.

## 2. Decisions (agreed)

| Topic | Decision |
|---|---|
| Brain location | PC (Python server + Ollama). Phone is body + senses. |
| PC hardware | RTX 3070 (8 GB VRAM). Default model: 8B-class (e.g. `qwen3:8b`), configurable. Embeddings: `nomic-embed-text`. |
| Connectivity | WebSocket over home LAN, or Tailscale when away. |
| Avatar | 2D sprite sheets in v1. Body is swappable (Live2D / VRM later). |
| Personality | Fixed core (name **Maya**, **curious**, **playful**) + self-developed learned traits. |
| Senses | v1: level A (foreground app + duration, device state). Roadmap to D (vision). |
| Architecture | Approach 1 — mind lives entirely in the PC brain server. |

## 3. Architecture

```
PHONE (Kotlin APK)                         PC (Python)
┌─────────────────────────┐   WebSocket   ┌──────────────────────────────┐
│ Body: overlay service   │◄────────────► │ Gateway (FastAPI)            │
│  - sprite animator      │  (Tailscale   │ Mind                         │
│  - speech bubble + chat │   or LAN)     │  - Persona (core + traits)   │
│ Senses (plug-ins)       │               │  - Memory (SQLite + vectors) │
│  - AppUsageSense (v1)   │──events──►    │  - Emotion (mood, closeness) │
│  - DeviceSense (v1)     │               │  - Initiative (urge meter)   │
│ Settings / pairing      │◄──actions──   │  - Reflection (nightly)      │
└─────────────────────────┘               │ LLM adapter → Ollama         │
                                          └──────────────────────────────┘
```

The phone sends **events**; the brain sends back **actions**. The phone holds
no personality logic, so the brain can be changed without rebuilding the APK,
and the body can be replaced without touching the brain.

### Repository layout

```
brain/      Python package `maya_brain` (server, mind, simulator CLI, tests)
android/    Kotlin Android app (overlay body, senses, settings)
docs/       Specs, plans, protocol notes
.github/    CI: brain tests, Android build producing a debug APK artifact
```

## 4. Protocol (phone ⇄ brain)

Transport: one WebSocket at `ws://<pc>:8765/ws?token=<secret>`. JSON text
frames. Every message has `type`, `id` (UUID), and `ts` (ISO-8601 UTC).

### Events (phone → brain)

| type | payload fields | meaning |
|---|---|---|
| `hello` | `client_version`, `screen_w`, `screen_h` | sent on connect |
| `user_message` | `text` | user typed to Maya |
| `user_tap` | — | user tapped Maya (no text) |
| `user_drag` | `thrown: bool` | user dragged/threw Maya |
| `user_shush` | — | "shh" quick action |
| `app_opened` | `package`, `label` | foreground app changed to this |
| `app_closed` | `package`, `label`, `duration_s` | left this app after N seconds |
| `screen` | `on: bool` | screen on/off |
| `charging` | `on: bool` | charger connected/disconnected |
| `offline_summary` | `events: [...]`, `since` | queued events sent after reconnect |

Apps on the do-not-watch list never produce `app_*` events.

### Actions (brain → phone)

| type | payload fields | meaning |
|---|---|---|
| `say` | `text`, `bubble_ms` | show a speech bubble |
| `animate` | `name` (`idle`,`walk`,`sit`,`nap`,`wave`,`peek`,`happy`,`sulk`,`surprised`) | play animation |
| `state` | `mood`, `closeness_tier` | body may adjust idle style |
| `typing` | `on: bool` | show "thinking…" indicator |
| `error` | `code`, `message` | protocol/auth error (not shown in-character) |

Unknown `type`s are ignored by both sides (forward compatibility).

## 5. Brain (PC) components

Each component is a module with a narrow interface and its own tests.

### 5.1 Gateway (`gateway.py`)
FastAPI app. Authenticates the token, validates messages with Pydantic models,
forwards events to the Mind, pushes actions back. Binds to a configurable host
(default `0.0.0.0`; user is told to restrict via firewall/Tailscale). One active
phone connection; a new connection replaces the old one.

### 5.2 LLM adapter (`llm.py`)
Interface: `chat(messages, json_schema=None) -> str` and `embed(text) -> list[float]`.
Implementation: Ollama HTTP API. A `FakeLLM` implementation exists for tests and
the simulator's offline mode. Model names come from config.

### 5.3 Persona (`persona.py`)
- **Core** (from config, immutable at runtime): name, core traits, speaking style
  notes.
- **Learned traits** (DB table `traits`): `kind` (like/dislike/opinion/joke/
  nickname/habit), `text`, `confidence` (0–1), `updated_at`. Reflection adds,
  reinforces, or decays them; traits below 0.15 confidence are pruned.
- Renders the persona section of the system prompt.

### 5.4 Memory (`memory.py`), SQLite (`maya.db`)
1. **Short-term buffer:** last 20 conversation turns + events, in memory and
   persisted for restarts.
2. **Episodes** (`episodes`): `summary`, `embedding` (BLOB), `importance` (1–5),
   `created_at`. Written when a conversation goes idle for 10 min, or for notable
   events (long app sessions, late-night use, return after long absence).
3. **Facts** (`facts`): `key`, `value`, `updated_at` — durable facts about the
   user. Upserted by key.

Retrieval: cosine similarity over episode embeddings (numpy, brute force — data
volume is small), score = similarity × recency decay × importance weight; top 5.
All facts are included in the prompt (capped at 40, most recent first).

### 5.5 Emotion (`emotion.py`)
- **Mood:** one of `happy, curious, bored, excited, sulky, worried, sleepy`
  plus `intensity` (0–1). Intensity decays toward baseline (`curious`, 0.3)
  with a 2-hour half-life. The LLM proposes the new mood each thought; the
  Emotion module applies it.
- **Closeness:** float 0–100, starts at 10. Changes:
  - LLM-proposed `closeness_delta` clamped to [-3, +3] per thought.
  - Daily decay toward 10 of 1 point per day without interaction after 2 days.
  - Rudeness is judged by the LLM; per-day net loss capped at -8.
- **Tiers:** stranger (0–29), friend (30–69), close (70–100). Tier is included in
  the prompt with behavior guidance and scales the urge rate (0.5×, 1×, 1.5×).

### 5.6 Thinker (`thinker.py`)
Builds the prompt (persona + tier guidance + mood + facts + retrieved episodes +
short-term buffer + current context: time, current app & duration, triggering
event) and asks the LLM for a JSON **thought**:

```json
{"thought": "string (private)",
 "speak": true,
 "say": "string or empty",
 "animation": "wave",
 "mood": "curious", "mood_intensity": 0.6,
 "closeness_delta": 1}
```

Validated with Pydantic. On parse failure: one retry with a "fix your JSON"
message; second failure → no action, logged. Thoughts are stored in a `thoughts`
table (viewable in the simulator / debug log). Direct user messages
(`user_message`, `user_tap`) always produce `speak: true` (enforced after
parsing).

### 5.7 Initiative (`initiative.py`)
Cheap, rule-based **urge meter** (0–100), ticked every 30 s and on each event.

- Base gain per tick: +1 × tier multiplier × mood multiplier (bored 2×,
  excited 1.5×, sleepy 0.3×, others 1×).
- Event bumps: app switch +5, same app > 45 min +15 (once per session),
  late night (00:00–05:00) usage +10 (once per hour), user returns after > 3 h +30,
  charging connected +3.
- When urge ≥ 60 and guardrails allow → call Thinker with trigger
  `initiative`; reset urge to 0 regardless of whether she speaks.
- **Guardrails:** min 10 min between unprompted remarks; max 15 unprompted
  remarks/day; quiet hours (config, default 23:30–08:00, except late-night
  usage triggers which may fire at most twice); `user_shush` sets urge to 0
  and enforces 60 min silence without closeness penalty.

### 5.8 Reflection (`reflection.py`)
Runs daily at a configured time (default 04:00) or after 6 h without events,
at most once per day. Steps (each a separate LLM call with a JSON schema):
1. Summarize the day's un-summarized buffer into episodes.
2. Extract/update facts about the user.
3. Update learned traits (add/reinforce/decay).
4. Write a diary entry (first person, in Maya's voice) → `diary` table.

### 5.9 Mind (`mind.py`)
Orchestrator: receives events, updates memory/emotion/initiative, decides
whether to invoke the Thinker, converts thoughts into actions (`typing`,
`animate`, `say`, `state`). Single asyncio task queue — events are processed in
order; at most one LLM call in flight.

### 5.10 Simulator CLI (`python -m maya_brain.sim`)
Interactive REPL against the real Mind with a virtual clock:
- plain text → `user_message`
- `/app TikTok`, `/close`, `/time 02:00`, `/skip 3h`, `/screen off`, `/shush`
- `/thoughts`, `/mood`, `/facts`, `/traits`, `/diary`, `/reflect`
- `--fake-llm` flag for offline runs.

### 5.11 Config (`maya.toml`)
`[server]` host, port, token · `[llm]` base_url, chat_model, embed_model ·
`[persona]` name, core_traits, style · `[initiative]` thresholds, cooldowns,
caps, quiet hours · `[reflection]` time.

## 6. Android app components (Kotlin, minSdk 29, targetSdk 35)

- **MayaService** — foreground service (persistent low-priority notification
  with "Shh" and "Hide" actions). Owns the WebSocket client, senses, and overlay.
  Restarts on boot (`RECEIVE_BOOT_COMPLETED`).
- **Overlay body** — `WindowManager` view of type `TYPE_APPLICATION_OVERLAY`
  sized to the sprite only (touches elsewhere pass through). Sprite animator
  plays frames from sprite sheets defined by `sprites.json`
  (`{animation: {row, frames, fps, loop}}`). A simple behavior state machine
  handles idle wander along screen edges, sit, nap, drag (with fling →
  `user_drag thrown=true`), and executes `animate` actions.
- **Bubble & chat** — speech bubble view anchored above the sprite; tapping
  Maya opens a small focusable input window to type a message.
- **Senses** — `Sense` interface (`start(emit)`, `stop()`).
  - `AppUsageSense`: polls `UsageStatsManager` events every 5 s; resolves app
    labels via `PackageManager`; filters the do-not-watch list.
  - `DeviceSense`: screen on/off and charging broadcasts.
- **Connection** — OkHttp WebSocket with exponential-backoff reconnect (1 s →
  60 s). While disconnected: Maya shows `nap`; events go to a bounded queue
  (200, oldest dropped) and are sent as one `offline_summary` on reconnect.
- **Settings / onboarding** — screens to grant overlay + usage access, enter
  PC address and token (or scan a QR code shown by the brain at startup —
  stretch goal), edit the do-not-watch list (pre-filled with common banking/
  password apps), toggle Maya on/off.
- **Sprites** — v1 ships programmatically generated placeholder pixel sprites;
  real art drops in by replacing the sheet + `sprites.json`.

## 7. Error handling summary

| Failure | Behavior |
|---|---|
| PC/brain unreachable | Maya naps; events queued and summarized on reconnect |
| Bad token | brain sends `error` and closes; app shows setup prompt |
| Ollama down / timeout (30 s) | no action; `typing off`; logged; for direct user message, Maya shows a short canned "…my head's foggy, try again?" |
| LLM returns invalid JSON | one repair retry, then silent |
| Android kills service | foreground service + boot restart |
| Permission revoked | sense stops, notification links to settings |

## 8. Testing

- **Brain:** pytest unit tests for persona rendering, memory (store/retrieve/
  ranking), emotion (decay, clamping, tiers), initiative (gains, guardrails,
  quiet hours) using an injectable clock, thinker (prompt contents, JSON
  validation, retry), mind (event → action flows with `FakeLLM`), gateway
  (auth, protocol validation) via FastAPI test client.
- **Simulator** for manual tuning of personality and pacing.
- **Android:** JVM unit tests for protocol serialization, offline queue,
  do-not-watch filtering, sprite config parsing, behavior state machine.
- **CI (GitHub Actions):** brain tests; Android `assembleDebug` + unit tests,
  uploading the debug APK as an artifact.

## 9. Out of scope for v1 (roadmap)

1. Voice (STT + TTS)
2. Notification sense (level B)
3. Screen text via AccessibilityService (level C)
4. Screenshot + vision model (level D)
5. Live2D / VRM body
6. Helping with phone actions
7. On-device fallback brain
8. QR pairing (if not done as stretch)

## 10. Privacy

All data stays on the phone and the user's PC. The brain listens only with a
token; the user is advised to expose it only on LAN/Tailscale. Do-not-watch
apps are filtered on the phone before anything is sent.
