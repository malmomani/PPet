# Maya Voice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hold Maya to talk (on-device speech-to-text on the phone) and hear her reply in a Kokoro voice synthesized on the PC.

**Architecture:** The brain gains a `tts` module and voices `say` actions with a follow-up `audio` action (base64 WAV) according to the phone's `voice_mode`. The phone gains hold-to-talk (Android `SpeechRecognizer`, on-device) that sends `user_message{voice:true}`, and a player for `audio`. Protocol additions are optional fields/new types, so old clients keep working.

**Tech Stack:** Brain: Python 3.11+, `kokoro-onnx` (optional extra `voice`), stdlib `wave`/`base64`. Phone: Kotlin, framework `SpeechRecognizer`, `MediaPlayer`.

**Spec:** `docs/superpowers/specs/2026-10-25-maya-voice-design.md` (and the v1 spec it builds on).

## Global Constraints

- Brain tests: `cd brain && .venv/bin/pytest -q` (also clean with `-W error`). Core tests: `cd android/core && gradle test -I /tmp/claude-0/-home-user-PPet/d6eab334-7ee4-5d52-a74e-33e22d437b37/scratchpad/init.gradle`. `:app` compiles only in GitHub Actions (`android` workflow).
- Wire format: `hello.voice_mode` ∈ `off|replies|always` (default `off`); `user_message.voice: bool` (default `false`); action `audio{type, id, ts, ref_id, mime:"audio/wav", data_b64}`.
- WAV: mono, 16-bit PCM, 24000 Hz (Kokoro native rate).
- Voice rules: voice=true reply → voiced if mode `replies`/`always`; every other `say` → voiced only if `always`; never if `[voice].enabled` false or TTS unavailable.
- `[voice]` defaults: `enabled = true`, `voice = "af_heart"`, `speed = 1.0`, `model_path = "models/kokoro-v1.0.onnx"`, `voices_path = "models/voices-v1.0.bin"`.
- `kokoro-onnx` is an optional dependency (`pip install -e ".[voice]"`); the brain must import and run without it. `brain/models/` is git-ignored.
- Hold-to-talk: stationary (< touch slop) press held ≥ 400 ms starts listening; release sends; listening max 30 s; quick tap < 400 ms opens chat; drag/fling unchanged.
- Phone strings: listening bubble prefix `"🎤 "`; no-match bubble `"…didn't catch that 🤔"`; mic failure bubble `"I can't hear you — check mic permission"`.
- No audio leaves the phone; never log transcripts at INFO or above on the phone.

## Review Focus

1. **Kokoro not installed / model files missing on the PC** → brain starts, logs one warning, text works. (Task 3 `test_voice_unavailable_still_sends_text`; Task 4 `test_main_runs_without_kokoro`.)
2. **Text that is only emoji or symbols** (e.g. "hehe 😊" → "hehe"; "👀" → nothing) → no empty/garbage audio. (Task 2 `test_speakable_strips_emoji`.)
3. **Rapid replies** (user sends two voice messages fast) → only the newest reply's audio plays; older pending TTS is cancelled. (Task 3 `test_newer_say_cancels_pending_tts`.)
4. **Old phone app without voice fields** → brain treats `voice_mode` as `off`, never sends `audio`. (Task 1 `test_hello_without_voice_mode_defaults_off`; Task 3 `test_mode_off_never_voices`.)
5. **Maya's own `VoiceActivity` coming to the foreground** → no `app_closed`/`app_opened` churn. (Task 5 `own_package_is_transparent`.)

---

### Task 1: Protocol and config (brain)

**Files:** Modify `brain/maya_brain/protocol.py`, `brain/maya_brain/config.py`, `brain/maya.example.toml`, `.gitignore`; Test `brain/tests/test_protocol.py`, `brain/tests/test_config.py`

**Interfaces:**
- Produces: `Hello.voice_mode: Literal["off","replies","always"] = "off"`; `UserMessage.voice: bool = False`; action `Audio(ref_id: str, mime: str = "audio/wav", data_b64: str)` with `type: Literal["audio"]`, included in the `Action` union; `VOICE_MODES = ("off","replies","always")`; `@dataclass VoiceConfig(enabled=True, voice="af_heart", speed=1.0, model_path="models/kokoro-v1.0.onnx", voices_path="models/voices-v1.0.bin")`; `Config.voice: VoiceConfig`.
- An unknown `voice_mode` string in `hello` → treated as `"off"` (validator), not a ProtocolError.

- [ ] **Step 1: Write failing tests**

```python
def test_hello_without_voice_mode_defaults_off():
    assert parse_event({"type":"hello","client_version":"1","screen_w":1,"screen_h":1}).voice_mode == "off"
def test_hello_voice_mode_and_unknown_value():
    base = {"type":"hello","client_version":"1","screen_w":1,"screen_h":1}
    assert parse_event({**base, "voice_mode":"replies"}).voice_mode == "replies"
    assert parse_event({**base, "voice_mode":"shout"}).voice_mode == "off"
def test_user_message_voice_flag():
    assert parse_event({"type":"user_message","text":"hi"}).voice is False
    assert parse_event({"type":"user_message","text":"hi","voice":True}).voice is True
def test_audio_action_serializes():
    d = json.loads(Audio(ref_id="abc", data_b64="UklGRg==").model_dump_json())
    assert d["type"] == "audio" and d["ref_id"] == "abc" and d["mime"] == "audio/wav" and d["data_b64"] == "UklGRg=="
def test_voice_config_defaults_and_override(tmp_path):  # in test_config.py
    assert load_config(None).voice.voice == "af_heart"
    p = tmp_path / "m.toml"; p.write_text('[voice]\nenabled=false\nvoice="af_bella"\n')
    c = load_config(p); assert c.voice.enabled is False and c.voice.voice == "af_bella" and c.voice.speed == 1.0
```

- [ ] **Step 2–4:** run → FAIL, implement (example TOML gets a commented `[voice]` section; `.gitignore` adds `brain/models/` and `maya-voice-last.wav`), run full suite → PASS.
- [ ] **Step 5: Commit** `feat(brain): voice protocol fields, audio action, voice config`

---

### Task 2: TTS module (brain)

**Files:** Create `brain/maya_brain/tts.py`, `brain/maya_brain/fetch_voice.py`; Modify `brain/pyproject.toml` (optional extra `voice = ["kokoro-onnx>=0.4"]`); Test `brain/tests/test_tts.py`

**Interfaces:**
- Consumes: `VoiceConfig`.
- Produces:
  - `class TTSError(Exception)`; `class TTS(Protocol): async def synth(self, text: str) -> bytes`
  - `speakable(text: str) -> str` — removes emoji/pictographs/symbols (Unicode categories `So`, `Sk`, `Cs`, `Co`, and variation selectors/ZWJ), collapses whitespace; keeps letters, digits, punctuation.
  - `pcm_wav(samples: Sequence[float], rate: int) -> bytes` — clamps to [-1, 1], 16-bit mono WAV via `wave`.
  - `class KokoroTTS(cfg: VoiceConfig, base_dir: Path)`: `available() -> bool` (kokoro_onnx importable and both files exist, paths relative to `base_dir`); `synth` lazy-loads `kokoro_onnx.Kokoro(model, voices)` once, runs `create(text, voice=cfg.voice, speed=cfg.speed, lang="en-us")` in `asyncio.to_thread`, returns `pcm_wav(samples, sample_rate)`; any exception → `TTSError`; empty `speakable(text)` → `TTSError("nothing to say")`.
  - `class FakeTTS`: `synth` returns `pcm_wav([0.0]*240, 24000)`, records `.calls: list[str]`, honors `.fail_next: int` and optional `.delay_s: float` (awaits `asyncio.sleep`).
  - `python -m maya_brain.fetch_voice [--dir models]` downloads `kokoro-v1.0.onnx` and `voices-v1.0.bin` from `https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/` with `httpx` (streamed, skip if file exists, print progress per file).

- [ ] **Step 1: Write failing tests**

```python
def test_speakable_strips_emoji():
    assert speakable("hehe 😊") == "hehe"
    assert speakable("👀") == ""
    assert speakable("isn't your exam tomorrow? 👀✨") == "isn't your exam tomorrow?"
def test_pcm_wav_header():
    w = wave.open(io.BytesIO(pcm_wav([0.0, 0.5, -2.0], 24000)))
    assert (w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()) == (1, 2, 24000, 3)
async def test_fake_tts_and_failure():
    t = FakeTTS(); assert (await t.synth("hi"))[:4] == b"RIFF" and t.calls == ["hi"]
    t.fail_next = 1
    with pytest.raises(TTSError): await t.synth("hi")
def test_kokoro_unavailable_without_files(tmp_path):
    assert KokoroTTS(VoiceConfig(), tmp_path).available() is False
async def test_kokoro_synth_uses_library(monkeypatch, tmp_path):
    # fake kokoro_onnx module in sys.modules with Kokoro(...).create returning ([0.0]*10, 24000); touch both model files
    ...  # assert synth returns RIFF bytes and create got voice="af_heart", lang="en-us"
```

(The last test installs a stub module via `monkeypatch.setitem(sys.modules, "kokoro_onnx", stub)`.)

- [ ] **Step 2–4:** FAIL → implement → PASS (full suite, `-W error`).
- [ ] **Step 5: Commit** `feat(brain): Kokoro TTS module and model fetcher`

---

### Task 3: Mind voices replies (brain)

**Files:** Modify `brain/maya_brain/mind.py`; Test `brain/tests/test_mind_voice.py`

**Interfaces:**
- Consumes: `TTS`, `TTSError`, `speakable`, `Audio`, `Hello.voice_mode`, `UserMessage.voice`.
- Produces: `Mind(cfg, llm, db, clock, sink=None, tts: TTS | None = None)`; `Mind.voice_mode: str`; `async Mind.wait_voice() -> None` (awaits the in-flight TTS task if any; for tests/sim).
- Behavior: `Hello` sets `voice_mode`. After each `Say` emit, decide per Global Constraints (the "voice=true reply" case = the `say` produced while handling a `UserMessage` whose `voice` is true, including the canned foggy line). Voicing = cancel any pending TTS task, then start `asyncio.create_task` (outside the lock) that synthesizes `speakable(say.text)` and emits `Audio(ref_id=say.id, data_b64=base64(wav))`; `TTSError` → one `log.warning`, nothing emitted; empty speakable text → skip. `tts is None` or `cfg.voice.enabled` false → never voice.

- [ ] **Step 1: Write failing tests** (fixture: Mind with `FakeTTS`, `sent` sink, reflection pre-marked done as in existing mind tests)

```python
async def test_voice_reply_gets_audio(mind_v, sent):
    await mind_v.handle(Hello(client_version="1", screen_w=1, screen_h=1, voice_mode="replies"))
    await mind_v.handle(UserMessage(text="hi", voice=True)); await mind_v.wait_voice()
    say = next(a for a in sent if a.type == "say"); audio = [a for a in sent if a.type == "audio"]
    assert len(audio) == 1 and audio[0].ref_id == say.id and base64.b64decode(audio[0].data_b64)[:4] == b"RIFF"
async def test_typed_reply_not_voiced_in_replies_mode(mind_v, sent): ...  # voice=False → no audio
async def test_always_mode_voices_typed_and_initiative(mind_v, sent): ...  # typed reply and an initiative say both get audio
async def test_mode_off_never_voices(mind_v, sent): ...  # no Hello at all, voice=True → no audio
async def test_voice_unavailable_still_sends_text(mind_v, sent):
    mind_v.tts.fail_next = 1  # TTSError
    ...  # say present, no audio, no exception
async def test_newer_say_cancels_pending_tts(mind_v, sent):
    mind_v.tts.delay_s = 0.2
    ...  # two voice messages back to back → exactly one audio, ref_id == second say's id
async def test_disabled_in_config_never_voices(...): ...  # cfg.voice.enabled = False
```

- [ ] **Step 2–4:** FAIL → implement → PASS (full suite, `-W error`).
- [ ] **Step 5: Commit** `feat(brain): voice Maya's replies with TTS audio`

---

### Task 4: Wire TTS into server and simulator; docs (brain)

**Files:** Modify `brain/maya_brain/__main__.py`, `brain/maya_brain/sim.py`, `brain/README.md`; Test `brain/tests/test_sim.py`, `brain/tests/test_gateway.py`

**Interfaces:**
- Consumes: `KokoroTTS`, `FakeTTS`, `Mind(..., tts=)`, `Mind.wait_voice`.
- Produces: server builds `KokoroTTS(cfg.voice, base_dir=config file's directory)`; if `cfg.voice.enabled and not tts.available()` → print one warning naming `pip install -e ".[voice]"` and `python -m maya_brain.fetch_voice`, run with `tts=None`. Sim: `--voice` flag → typed lines become `UserMessage(voice=True)`, sends `Hello(voice_mode="replies")` at start, uses `FakeTTS` when `--fake-llm` else `KokoroTTS` (falls back to None with the same warning); each `Audio` is written to `maya-voice-last.wav` and, on Windows, played with `winsound.PlaySound(path, winsound.SND_FILENAME)` after `await mind.wait_voice()`; prints `[audio N KB]`. Command `/say-voice <text>` synthesizes directly and plays/saves the same way (prints `(voice unavailable)` without TTS).
- README "Voice" section: `pip install -e ".[voice]"`, `python -m maya_brain.fetch_voice`, `[voice]` config, voice names to try (`af_heart`, `af_bella`, `af_nicole`, `af_sky`), `python -m maya_brain.sim --voice`.

- [ ] **Step 1: Write failing tests**: `test_main_runs_without_kokoro` (monkeypatch uvicorn.run; voice enabled, no model files → exit 0 and warning printed); `test_sim_voice_flag_writes_wav` (FakeTTS, `--fake-llm --voice` scripted "hi" → `maya-voice-last.wav` exists in tmp cwd and output contains `[audio`); `test_parse_say_voice_command` (`/say-voice hello` → `("say_voice", "hello")`).
- [ ] **Step 2–4:** FAIL → implement → PASS (full suite).
- [ ] **Step 5: Commit** `feat(brain): TTS in server and simulator, voice setup docs`

---

### Task 5: Core — protocol, hold-to-talk rule, transparent own package (Android core)

**Files:** Modify `android/core/src/main/kotlin/com/maya/core/{Protocol.kt, Gestures.kt, AppUsageTracker.kt}`; Tests in the matching `*Test.kt`

**Interfaces:**
- Produces:
  - `UserMessage(text: String, voice: Boolean = false, id, ts)` → encodes `"voice"`; `Hello(clientVersion, screenW, screenH, voiceMode: String = "off", id, ts)` → encodes `"voice_mode"`; action `Audio(refId: String, mime: String, dataB64: String)` decoded from `audio` (missing field → null).
  - `const val HOLD_TO_TALK_MS = 400L`; `fun isHoldToTalk(distancePx: Float, heldMs: Long, touchSlopPx: Float): Boolean` = `distancePx < touchSlopPx && heldMs >= HOLD_TO_TALK_MS`.
  - `AppUsageTracker(isIgnored: (String) -> Boolean, isTransparent: (String) -> Boolean = { false })` — a transparent package returns `emptyList()` and leaves the current app unchanged.

- [ ] **Step 1: Write failing tests**: `encode_user_message_voice` (`"voice":true`), `encode_hello_voice_mode`, `decode_audio` (`Audio("abc","audio/wav","UklGRg==")`) and `decode_audio_missing_data_is_null`, `hold_to_talk_rule` (`isHoldToTalk(2f, 400, 8f)` true, `(2f, 399, 8f)` false, `(20f, 900, 8f)` false), `own_package_is_transparent` (open TikTok, observe transparent pkg → empty, `current()` still TikTok, later observe YouTube → closes TikTok with full duration).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(android): core support for voice messages and hold-to-talk`

---

### Task 6: Hold-to-talk on the phone (app)

**Files:** Create `android/app/src/main/kotlin/com/maya/app/voice/{VoiceInput.kt, VoiceActivity.kt}`; Modify `overlay/OverlayBody.kt`, `overlay/BubbleView.kt` (if needed for live text), `MayaService.kt`, `AndroidManifest.xml`, `res/values/strings.xml`, `senses/AppUsageSense.kt` (pass `isTransparent = { it == ownPackage }` and remove own package from `isIgnored`)

**Interfaces:**
- Consumes: `isHoldToTalk`, `HOLD_TO_TALK_MS`, `UserMessage(text, voice = true)`.
- Produces:
  - `class VoiceInput(context: Context, listener: Listener)` with `interface Listener { fun onPartial(text: String); fun onFinal(text: String); fun onNoMatch(); fun onUnavailable(reason: Reason) }`, `enum class Reason { NO_RECOGNIZER, PERMISSION, BACKGROUND_DENIED }`, `fun start()`, `fun stop()` (finish and deliver final), `fun cancel()`, `fun destroy()`, `val usesActivity: Boolean`. Recognizer choice and intent extras per spec §5.2. `ERROR_INSUFFICIENT_PERMISSIONS`/`ERROR_AUDIO` (or no `onReadyForSpeech` within 1500 ms) → switch to activity mode for the rest of the service run and report `BACKGROUND_DENIED` once (overlay shows the mic-failure bubble only if the activity path also fails).
  - `VoiceActivity`: translucent, no layout, `excludeFromRecents`, not exported; started with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION`; runs the recognizer and relays callbacks to `VoiceInput` through a process-local singleton (`VoiceBridge` object); finishes on stop/cancel/result.
  - OverlayBody: a 400 ms hold timer from ACTION_DOWN (cancelled by moving past slop or UP); on fire → `startListening()` (stops `VoicePlayer` from Task 7 via `MayaService` hook if present), bubble `"🎤 "` + live partials, 30 s cap; UP while listening → `stop()`; final → `emit(UserMessage(text, voice = true))`; no-match → bubble `"…didn't catch that 🤔"`; disconnected → existing "zzz…" bubble, no listening; `RECORD_AUDIO` not granted → bubble "I can't hear you — check mic permission".
  - Manifest: `RECORD_AUDIO` permission; `<activity android:name=".voice.VoiceActivity" android:exported="false" android:theme="@android:style/Theme.Translucent.NoTitleBar" android:excludeFromRecents="true" android:noHistory="true"/>`; `<queries><intent><action android:name="android.speech.RecognitionService"/></intent></queries>`.

- [ ] **Step 1:** Implement; re-read every changed file as a compiler (API guards: `createOnDeviceSpeechRecognizer` 31+, `isOnDeviceRecognitionAvailable` 33+).
- [ ] **Step 2: Verify** push → `android` workflow green.
- [ ] **Step 3: Commit** `feat(android): hold Maya to talk`

---

### Task 7: Voice playback, settings, docs (app)

**Files:** Create `android/app/src/main/kotlin/com/maya/app/voice/VoicePlayer.kt`; Modify `MayaService.kt`, `BrainConnection.kt` (hello voice mode), `Prefs.kt`, `SettingsActivity.kt`, `overlay/OverlayBody.kt`, `strings.xml`, `android/README.md`

**Interfaces:**
- Consumes: `Audio`, `Say`, `Hello(voiceMode)`.
- Produces:
  - `Prefs.voiceMode: String` (default `"replies"`), sent in `Hello`.
  - `class VoicePlayer(context: Context) { fun play(refId: String, dataB64: String); fun stop(); fun release() }` — decodes to `cacheDir/maya-voice.wav` (overwrite), `MediaPlayer` with `AudioAttributes.USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH`; skips when `AudioManager.ringerMode != RINGER_MODE_NORMAL`; plays only if `refId` equals the latest `Say` id it was told about (`fun onSay(id: String)`); errors swallowed.
  - Service routes `Say` → `player.onSay(id)` (and overlay as today), `Audio` → `player.play`; stops audio on Shh, Hide, listening start (hook used by Task 6), and in `onDestroy`; overlay plays `happy` once when audio starts.
  - Settings: "Microphone" permission row (runtime `RECORD_AUDIO`); "Maya's voice" radio group Off / Only when I talk to her / Always → `off|replies|always`; "Offline English speech" row shown when `SDK_INT >= 33 && !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)` with a button to `Settings.ACTION_VOICE_INPUT_SETTINGS` (fallback `ACTION_SETTINGS`).
  - README "Talking to Maya" section: grant mic, hold her ~half a second, speak, let go; voice setting; installing offline English speech on Samsung (Settings → General management → Keyboard/Voice input; wording varies); PC voice setup pointer to brain README.

- [ ] **Step 1:** Implement; compiler pass.
- [ ] **Step 2: Verify** push → `android` workflow green.
- [ ] **Step 3: Commit** `feat(android): Maya speaks her replies; voice settings`

---

## Done when

Brain suite and core suite green; `android` workflow green with `maya-debug-apk`; with Kokoro installed on the PC, `python -m maya_brain.sim --voice` speaks, and on the phone holding Maya transcribes live and her reply is spoken.
