# Maya — Voice Design Spec (v1)

Date: 2026-10-25
Status: Approved in chat; user asked to proceed to implementation.
Builds on: `docs/superpowers/specs/2026-10-24-maya-design.md` (brain + Android v1, both shipped).

## 1. Goal

Talk to Maya by holding her, and hear her answer in a natural young female voice.

### Success criteria

- Holding Maya still for ~0.4 s starts listening; her bubble shows a mic and the live
  transcript; releasing sends it. Quick tap still opens the chat box; moving still drags.
- Her reply appears as a bubble immediately and is spoken aloud shortly after
  (target: audio starts ≤ 1.5 s after the bubble on the user's PC).
- No audio ever leaves the phone; speech-to-text runs on-device.
- Voice failures never break text: if TTS or recognition fails, Maya still works by text.

## 2. Decisions (agreed)

| Topic | Decision |
|---|---|
| Activation | Hold-to-talk only (no wake word in v1). |
| Language | English only. |
| Speech-to-text | Phone, on-device `SpeechRecognizer` (offline English model). |
| Text-to-speech | PC, Kokoro via `kokoro-onnx` on **CPU** (GPU stays with the LLM). Default voice `af_heart`, configurable. |
| When she speaks aloud | Phone setting `voice_mode`: `off` · `replies` (default: only when the user spoke) · `always` (also unprompted remarks). |

## 3. Protocol changes (backward compatible)

All additions are optional fields or new types; old clients/brains ignore unknown fields/types.

- `hello` gains `voice_mode: "off" | "replies" | "always"` (default `"off"` when absent).
- `user_message` gains `voice: bool` (default `false`).
- New action `audio`:
  `{type:"audio", id, ts, ref_id: <id of the say it voices>, mime: "audio/wav", data_b64: <base64 WAV>}`.
  WAV is mono 16-bit PCM at Kokoro's native 24 kHz. The brain sends it after the matching `say`.

## 4. Brain (PC)

### 4.1 `tts.py`
- `class TTS(Protocol): async def synth(self, text: str) -> bytes` (complete WAV file bytes).
- `KokoroTTS(cfg: VoiceConfig)`: lazy-loads `kokoro_onnx.Kokoro(model_path, voices_path)` on first use;
  `create(text, voice=cfg.voice, speed=cfg.speed, lang="en-us")` run via `asyncio.to_thread`;
  float samples → 16-bit PCM WAV with the stdlib `wave` module. Any exception → `TTSError`.
- `FakeTTS`: returns a tiny valid WAV; supports `fail_next` like `FakeLLM`.
- Text sent to TTS has emoji and other non-speech symbols stripped; empty after stripping → no audio.

### 4.2 Config `[voice]`
`enabled = true`, `voice = "af_heart"`, `speed = 1.0`,
`model_path = "models/kokoro-v1.0.onnx"`, `voices_path = "models/voices-v1.0.bin"`.
If `enabled` but model files are missing, the brain logs one clear warning at startup and runs text-only.
A helper `python -m maya_brain.fetch_voice` downloads both files into `brain/models/` (git-ignored).

### 4.3 Mind integration
- Mind stores `voice_mode` from the latest `hello` (default `off`).
- After emitting a `say`, Mind decides whether to voice it:
  - direct reply to a `user_message` with `voice=true` → voiced if mode is `replies` or `always`;
  - any other `say` (typed reply, tap reply, unprompted remark, canned "foggy" line) → voiced only if mode is `always`;
  - never voiced when `[voice].enabled` is false or TTS is unavailable.
- TTS runs in a background task **outside** the Mind lock; the resulting `audio` action is sent
  through the sink when ready. A TTS failure logs one warning line and sends nothing.
- At most one TTS job runs at a time; a newer `say` cancels a pending older one.

### 4.4 Simulator
- `--voice` flag: typed lines are sent as `voice=true`, `voice_mode` is `replies`, and each `audio`
  is written to `maya-voice-last.wav` and played with `winsound` on Windows (stdlib), otherwise only saved.
- `/say-voice <text>` synthesizes a line directly (to audition voices).

## 5. Android

### 5.1 Gesture (core `Gestures`/overlay)
- Finger down on Maya, no movement beyond touch slop, held ≥ 400 ms → **listening starts** (while held).
- Release while listening → stop and send. Movement after listening started is ignored.
- Quick tap (< 400 ms, no slop) → chat box (unchanged). Movement beyond slop before 400 ms → drag/fling (unchanged).
- Listening auto-stops after 30 s.
- Disconnected → holding shows the existing "zzz… (can't reach your PC)" bubble; no listening.

### 5.2 `VoiceInput`
- Uses `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+) when
  `SpeechRecognizer.isOnDeviceRecognitionAvailable` (API 33+) — else `createSpeechRecognizer` with
  `EXTRA_PREFER_OFFLINE = true`. `LANGUAGE_MODEL_FREE_FORM`, `en-US`, partial results on.
- Partial results update the bubble live (prefixed "🎤 "). Final result → `UserMessage(text, voice=true)`.
- Empty/no-match → bubble "…didn't catch that 🤔", nothing sent.
- **Background mic fallback:** if the recognizer reports `ERROR_INSUFFICIENT_PERMISSIONS`/`ERROR_AUDIO`
  or never starts, the next hold uses a translucent, no-UI `VoiceActivity` that runs the recognizer
  while the finger is down (an activity in the foreground always has mic rights; starting it from the
  overlay is allowed because the app holds SYSTEM_ALERT_WINDOW). The choice is remembered per service run.
- App usage tracking treats Maya's own package as "no change" (keeps the previous app) so
  `VoiceActivity` never causes `app_closed`/`app_opened` churn.

### 5.3 `VoicePlayer`
- Decodes `audio.data_b64` to a cache file, plays with `MediaPlayer` (`USAGE_ASSISTANT`).
- Skipped when the ringer mode is silent or vibrate. Stopped when listening starts, on "Shh", on Hide,
  and when a newer audio arrives. Plays only if its `ref_id` matches the latest `say`.
- Her animation shows `happy` once when audio starts (no lip-sync in v1).

### 5.4 Settings
- New "Microphone" permission row (`RECORD_AUDIO` runtime request).
- New "Maya's voice" selector: Off / Only when I talk to her / Always (stored in `Prefs.voiceMode`,
  sent in `hello`; saving restarts the service as today).
- If on-device English recognition isn't available: a row "Offline English speech: not installed"
  with a button to the system voice-input settings.
- Manifest adds `RECORD_AUDIO` and the `VoiceActivity` (translucent theme, `excludeFromRecents`, not exported).

## 6. Error handling

| Failure | Behavior |
|---|---|
| No on-device English model | Settings row + bubble pointing there; typed chat still works. |
| Mic refused (background restriction) | Fallback to `VoiceActivity`; if that fails too, bubble "I can't hear you — check mic permission". |
| Nothing recognized | "…didn't catch that 🤔", nothing sent. |
| Kokoro missing/crashing | One warning in the brain log; text-only replies. |
| PC disconnected while holding | "zzz…" bubble, no listening. |
| Phone silent/vibrate, Shh, Hide | Audio skipped/stopped; bubble still shows. |
| User starts talking while she speaks | Her audio stops immediately. |

## 7. Privacy

Speech never leaves the phone as audio. Only text goes to the PC; only her synthesized audio comes back,
over the user's LAN/Tailscale. The mic is active only while Maya is held.

## 8. Testing

- **Brain (pytest, `FakeTTS`):** voice-mode rules; `audio.ref_id` matches the `say`; TTS failure keeps text;
  newer say cancels pending TTS; protocol fields parse and default correctly; old-style messages still work;
  emoji stripping; WAV header validity; simulator `--voice` path with `FakeTTS`.
- **Core (Kotlin):** protocol encode/decode for `voice`, `voice_mode`, `audio`; gesture rule for hold-to-talk
  (hold ≥ 400 ms still → listen; release → send; tap; drag unaffected); own-package-is-no-change in the tracker.
- **App:** CI build; on-device checklist for the user (mic permission, hold to talk, live transcript, hearing her,
  silent mode, locked screen, fallback activity).

## 9. Out of scope (later)

Wake word; lip-sync/talking animation frames; voice cloning; Arabic; streaming TTS (sentence-by-sentence).
