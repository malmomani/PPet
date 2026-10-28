# Maya Bigger Brain, Vision and Phone Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OpenAI-compatible brain backend, screenshot vision, and confirmed phone actions, plus hiding Maya in do-not-watch apps.

**Architecture:** Brain gets a provider abstraction (Ollama | OpenAI-compatible) and a vision describer; the phone's accessibility service takes screenshots on picture-heavy screens or on request and runs `act` actions through Android APIs or her body, asking the user before risky ones.

**Tech Stack:** Python (httpx, pydantic, pytest); Kotlin core (JUnit); Android `AccessibilityService.takeScreenshot` (API 30+), `performAction`/`dispatchGesture`, intents.

**Spec:** `docs/superpowers/specs/2026-10-28-maya-vision-actions-design.md`

## Global Constraints

- Wire (snake_case JSON): `sight{type,id,ts,package,app,reason:"auto"|"asked",image_b64}`; `sight_failed{type,id,ts,reason:"blocked"|"error"}`; `look{type,id,ts}`; `act{type,id,ts,kind,...}` with kinds/fields exactly per spec §5.1; `act_result{type,id,ts,act_id,status:"done"|"declined"|"timeout"|"failed"|"blocked",detail?}`. None of `sight`, `sight_failed`, `act_result` are queued offline; none count as user activity.
- Screenshots: RAM only, never written to disk or logged, long side ≤ 1024 px, JPEG q70, ≤ 400 KB; brain drops `image_b64` > 600 000 chars.
- Vision prompt (exact): `"This is a screenshot of the user's phone. In 1-3 short sentences, describe what the user is looking at (the pictures, not the menus). The small pixel girl is Maya herself; don't describe her."`; description ≤ 300 chars; memory line ≤ 160 chars.
- Numbers: auto look ≤ 1/300 s global and ≤ 1/600 s per app; asked look ≤ 1/20 s; picture-heavy = image area ≥ 40 % of screen, or one image ≥ 25 % with ≤ 8 text lines; auto-sight urge +5 ≤ 1/300 s; own-initiative acts ≤ 10 per rolling hour (persisted); confirm timeout 15 s; press give-up 6 s; timer 5..86400 s; web_search query ≤ 200; type text ≤ 500; press label ≤ 40.
- Never act/look: do-not-watch apps, locked keyguard, `com.android.systemui`, `com.android.settings`, Maya's own package, password fields; actions switch `Prefs.allowActions` default **false**.
- Brain tests: `cd brain && .venv/bin/pytest -q` (+ `-W error`). Core: `cd android/core && gradle test -I /tmp/claude-0/-home-user-PPet/d6eab334-7ee4-5d52-a74e-33e22d437b37/scratchpad/init.gradle`. `:app` compiles in CI (AGP 8.7.3, compileSdk 35, minSdk 29, Kotlin 2.0.21, no AndroidX); a local framework-jar compile harness exists in the scratchpad (see `t8`).
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01WLLDks9zoTp2qVAJ3nppe8`. Never commit `.superpowers/`.

## Review Focus

1. **Vision model slow or down** (llama-server busy, 60 s+ call) → chat replies are not blocked (vision runs outside the Mind lock); failure leaves no description, no crash. (T2 `test_sight_described_outside_lock`, `test_vision_failure_is_silent`.)
2. **LLM proposes a risky act on its own** (“send”, typing a message) → dropped by the brain; if asked by the user, the phone still asks for a yes. (T2 `test_own_initiative_never_risky`; T4 `risky_press_needs_confirmation`.)
3. **Action in a forbidden place** (bank, Settings, lock screen, password field) → `blocked`, nothing pressed or typed. (T4 `never_list_blocks`; T6 manual.)
4. **Old brain / old app** → unknown `sight`/`act`/`look`/`act_result` ignored both ways. (T1/T2 parse tests; T3 `unknown_action_ignored`.)
5. **Picture-heavy feed while scrolling** → auto looks ≤ 1 per 5 min, no GPU storm. (T3 `look_scheduler_limits`.)

---

### Task 1: Brain — OpenAI-compatible provider and vision describer

**Files:** Modify `brain/maya_brain/{config.py, llm.py, __main__.py}`, `brain/maya.example.toml`, `brain/README.md`, `README.md`; Create `brain/maya_brain/vision.py`; Test `brain/tests/test_providers.py`, `brain/tests/test_vision.py`

**Interfaces:**
- `LLMConfig` gains `provider: str = "ollama"`, `api_key: str = ""`, `embed_base_url: str = "http://localhost:11434"`. New `VisionConfig(enabled: bool = True, model: str = "", max_side: int = 1024)` at `Config.vision`.
- `OpenAILLM(cfg, transport=None)` implements `LLM` (`chat`, `embed` → Ollama `/api/embed` at `embed_base_url`, `warm_up`). `make_llm(cfg: LLMConfig, transport=None) -> LLM` picks by provider (unknown → ValueError at startup). `OllamaLLM` is unchanged (embeds at its own `base_url`).
- `vision.py`: `VISION_PROMPT` (exact, global constraints); `class Describer(Protocol): async def describe(self, image_b64: str) -> str | None`; `make_describer(cfg: Config, transport=None) -> Describer | None` (None when `vision.enabled` is false); OpenAI shape per spec §3 (model = `vision.model or llm.chat_model`), Ollama shape `/api/chat` with `images` (model = `vision.model or "gemma3:4b"`); result trimmed ≤ 300; any error → None (log type only). `FakeDescriber` for tests.
- README (brain + root): "Bigger brain with llama-server" section per spec §3 (Windows steps, command, `--n-cpu-moe` tuning, speed check, `maya.toml` example with `provider = "openai"`, privacy note about cloud providers).

- [ ] **Step 1: Failing tests** — `test_openai_chat_request_shape` (URL `/chat/completions`, model, temperature, `stream:false`, `response_format` only in JSON mode, `Authorization: Bearer` only when api_key set), `test_openai_strips_think_and_errors` (HTTP 500 / bad shape → LLMError), `test_openai_embeds_via_ollama`, `test_make_llm_picks_provider`, `test_config_defaults_and_toml`, `test_describe_openai_shape`, `test_describe_ollama_shape`, `test_describe_trims_and_fails_quietly`.
- [ ] **Step 2–4:** FAIL → implement → PASS (full suite, `-W error`).
- [ ] **Step 5: Commit** `feat(brain): OpenAI-compatible backend and vision describer`

---

### Task 2: Brain — sight, look, act and act_result

**Files:** Modify `brain/maya_brain/{protocol.py, thinker.py, mind.py, initiative.py, gateway.py, sim.py}`, `brain/README.md`; Test `brain/tests/test_sight_act.py`

**Interfaces:**
- Consumes: Task 1 `Describer`, `make_describer`.
- protocol: events `SightEvent(package, app, reason: Literal["auto","asked"], image_b64: str)` (> 600 000 chars → ProtocolError), `SightFailedEvent(reason: Literal["blocked","error"])`, `ActResultEvent(act_id: str, status: Literal[...], detail: str|None ≤ 200)`; actions `Look()`, `Act(kind: Literal[ACT_KINDS], app?, key?, seconds?, label?, hour?, minute?, dir?, query?, target?, text?, into?)` with per-kind validation per spec §5.1 (model_validator).
- `RISKY_TAGS = ("send","share","comment","follow")`, `RISKY_WORDS` (spec §5.2 list, EN+AR); `is_risky(act: Act) -> bool` (press on risky tag/label word; any `type`).
- Mind: `describer` injected (constructor kwarg `describer=None`); `async handle_sight(event)` — called by the gateway **instead of** `handle` for sights: awaits `describer.describe` without the lock, then under the lock stores `self.sight = (app, description, ts)`, memory turn `"you looked at {app}: {description[:...]}"` (line ≤ 160), auto → initiative bump +5 (gap 300 s, persisted key `sighted`), asked → `_respond(Context("look", f"You looked at the screen as the user asked. You saw: {description}"))`; description None + asked → `_respond(Context("look", "You tried to look at the screen but couldn't see it."))`. `SightFailedEvent` → same "couldn't see" response.
- Thinker: `Context.sight: str | None`; prompt line `"You saw on screen ({app}): {description}"` when sight ≤ 10 min old and app equals the latest glance app (or no glance); `Thought.look: bool = False`; `Thought.act: ActChoice | None` (invalid → None); rules text lists act kinds in one line and says risky ones get confirmed.
- Mind `_apply`: after say/move: `look` → emit `Look()`; `act` → if own-initiative (trigger not in DIRECT_TRIGGERS and not "look") then require tier ∈ {friend, close}, not quiet, not shushed, `not is_risky`, `initiative.act_ready()` (≤ 10 per rolling hour, persisted list `acts`), else drop; emit `Act`; record `initiative.acted()` for own-initiative ones.
- `ActResultEvent` → memory turn per spec §5.4 using `summarize(act) -> str` (e.g. `open YouTube`, `set a 10-minute timer`, `press Send`, `type "…"` truncated 40).
- Gateway: route `SightEvent` to `mind.handle_sight` as a background task (one at a time; a second sight while one is being described is dropped).
- Sim: `/sight <app> <description>` (bypasses describer), `/act_result <status> [detail]`; print `[look]` and `[act] kind fields`.

- [ ] **Step 1: Failing tests** — `test_parse_sight_caps`, `test_parse_act_per_kind` (valid/invalid per kind incl. timer bounds, label/text caps, press needs target), `test_is_risky_en_ar`, `test_sight_described_outside_lock` (a user message is answered while the describer is blocked), `test_vision_failure_is_silent`, `test_sight_memory_and_urge_once_per_5_min`, `test_asked_sight_direct_response`, `test_sight_failed_response`, `test_sight_in_prompt_age_and_app`, `test_thought_look_emits_look`, `test_own_initiative_act_limits` (tier, quiet, shush, 10/h persisted), `test_own_initiative_never_risky`, `test_user_asked_act_passes_and_risky_allowed`, `test_act_result_memory_wording`, `test_old_types_still_parse`, `test_sim_sight_act_result`.
- [ ] **Step 2–4:** FAIL → implement → PASS (full suite, `-W error`).
- [ ] **Step 5: Commit** `feat(brain): Maya sees the screen and can use the phone`

---

### Task 3: Core — vision protocol and look rules

**Files:** Modify `android/core/src/main/kotlin/com/maya/core/Protocol.kt`; Create `Vision.kt`; Tests `ProtocolTest.kt`, `VisionTest.kt`

**Interfaces:**
- Events `Sight(packageName, app, reason, imageB64, id, ts)` → `"sight"`; `SightFailed(reason, id, ts)` → `"sight_failed"`; action `Look` decoded from `"look"`.
- `fun isPictureHeavy(images: List<Box>, screenW: Int, screenH: Int, textLines: Int): Boolean` per Global Constraints (union area approximated by sum clipped to screen).
- `class LookScheduler(autoGapMs = 300_000, perAppGapMs = 600_000, askedGapMs = 20_000)`: `fun autoDue(pkg: String, pictureHeavy: Boolean, allowed: Boolean, nowMs: Long): Boolean` (records when true), `fun askedAllowed(nowMs: Long): Boolean` (records when true).
- `Layout` exposes image boxes: add `images: List<Box>` to `Layout` (from `buildLayout`, image nodes ≥ minImagePx), default empty.

- [ ] **Step 1: Failing tests** — `encode_sight_and_failed`, `decode_look`, `picture_heavy_rule` (40 % area, 25 % + ≤ 8 lines, text-heavy false), `look_scheduler_limits` (global 300 s, per-app 600 s, not allowed → false and not recorded, asked 20 s), `layout_reports_images`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(android): core vision rules`

---

### Task 4: Core — actions protocol, risk and matching, press command

**Files:** Modify `android/core/src/main/kotlin/com/maya/core/{Protocol.kt, Behavior.kt}`; Create `Actions.kt`; Tests `ProtocolTest.kt`, `ActionsTest.kt`, `BehaviorCommandTest.kt`

**Interfaces:**
- Action `Act(kind: String, app: String?, key: String?, seconds: Int?, label: String?, hour: Int?, minute: Int?, dir: String?, query: String?, target: String?, text: String?, into: String?, id: String)` decoded from `"act"` with the same per-kind validation as the brain (invalid → null). Event `ActResult(actId, status, detail?, id, ts)` → `"act_result"`.
- `Actions.kt`: `RISKY_TAGS`, `RISKY_WORDS` (same lists as brain), `fun isRisky(act: Act): Boolean`; `val NEVER_PACKAGES = setOf("com.android.systemui", "com.android.settings")`; `fun blockedReason(act: Act, pkg: String?, ownPackage: String, doNotWatch: (String) -> Boolean, keyguardLocked: Boolean, allowActions: Boolean): String?` ("off", "not here", "locked"); `fun matchApp(query: String, apps: List<Pair<String, String>>): String?` (label→package; normalized exact > prefix > similarity ≥ 0.8 by normalized Levenshtein; null otherwise); `fun matchLabel(target: String, nodes: List<UiNode>): UiNode?` (tag via `tagOf` when target is a tease tag, else whole-word/contains on label, smallest clickable wins).
- Behavior: `command("press", tag)` = `poke` semantics but returns a completion callback hook: `fun pressDone(): Boolean` true once she has played the poke on the target (consumed), and `fun pressTargetLost(): Boolean` when she gave up (6 s).

- [ ] **Step 1: Failing tests** — `decode_act_kinds` (+ invalid per kind), `encode_act_result`, `unknown_action_ignored`, `risky_press_needs_confirmation` (tags + EN/AR words + `type` always risky; `open_app` not), `never_list_blocks` (systemui, settings, own, dnw, keyguard, switch off), `match_app_cases` ("youtube"→YouTube, "yt music" no false match, typo "youtub"), `match_label_prefers_smallest_clickable`, `press_command_reports_done_and_lost`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(android): core phone actions`

---

### Task 5: App — screenshots, looks, and hiding in do-not-watch apps

**Files:** Modify `android/app/src/main/kotlin/com/maya/app/senses/{MayaAccessibilityService.kt, ScreenBridge.kt}`, `MayaService.kt`, `overlay/OverlayBody.kt` (only if needed), `res/xml/accessibility_config.xml`, `android/README.md`

**Interfaces:**
- Consumes: Task 3 (`Sight`, `SightFailed`, `Look`, `isPictureHeavy`, `LookScheduler`, `Layout.images`).
- Config `android:canTakeScreenshot="true"`. `GlanceSource` gains `fun screenshot(onResult: (ByteArray?) -> Unit)` (API 30+, else null): `takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)` → `Bitmap.wrapHardwareBuffer` → software copy → scale (long side ≤ 1024) → JPEG q70, lowering quality by 10 until ≤ 400 KB (min q30, else null) → recycle bitmaps; nothing written to disk or logged.
- MayaService: `LookScheduler`; in the 1 s glance tick, if `autoDue(pkg, isPictureHeavy(layout.images, w, h, lastGlanceLines), allowed)` → screenshot → `emit(Sight(..., "auto", b64))`; `handle(Look)` → if allowed and `askedAllowed` → screenshot → Sight("asked") else `emit(SightFailed("blocked"))`; screenshot failure on asked → `SightFailed("error")`. Sights are not queued offline (verify BrainConnection whitelist).
- Bank fix: when the front app (screen sense `onApp` or AppUsageSense) is do-not-watch → `overlayBody.hide()` (remember it was auto-hidden) and restore on leaving (unless the user hid her via the notification meanwhile).
- README: vision section (what she sees, privacy), bank section (why banks block, volume-key shortcut).

- [ ] **Step 1:** Implement; strict compiler pass + local harness compile.
- [ ] **Step 2: Verify** push → `android` workflow green.
- [ ] **Step 3: Commit** `feat(android): Maya looks at pictures and steps away from banking apps`

---

### Task 6: App — action runner, confirm bubble, settings

**Files:** Create `android/app/src/main/kotlin/com/maya/app/actions/ActionRunner.kt`; Modify `MayaService.kt`, `overlay/{OverlayBody.kt, BubbleView.kt}`, `senses/MayaAccessibilityService.kt`, `res/xml/accessibility_config.xml`, `Prefs.kt`, `SettingsActivity.kt`, `res/values/strings.xml`, `AndroidManifest.xml` (queries for launcher apps / alarm intents if needed), `android/README.md`

**Interfaces:**
- Consumes: Task 4 (`Act`, `ActResult`, `isRisky`, `blockedReason`, `matchApp`, `matchLabel`, Behavior press), Task 5 bridge.
- `ActionRunner(context, emit, overlay hooks, accessibility bridge)`: one action at a time (`busy` → `blocked("busy")`); `blockedReason` first; risky → `overlay.confirm(question, onYes, onNo)` with 15 s timeout; executes per spec §5.3; always emits `ActResult(act.id, status, detail)`.
- Bubble: `BubbleView.ask(text, onYes, onNo)` shows the text with ✔ / ✖ buttons (bubble window touchable only while asking; normal bubble behaviour otherwise).
- Accessibility service: `fun click(node)`, `fun setText(node, text)`, `fun scroll(dir)`, `fun tap(x, y)` (gesture fallback); config `canPerformGestures="true"`; nodes looked up fresh at execution time.
- `Prefs.allowActions` default false; Settings switch "Let Maya use my phone" with the note "She can open apps, control music, set timers and press buttons. Anything that sends, posts, buys or deletes needs your yes."
- README: actions section (what she can do, the yes rule, the switch).

- [ ] **Step 1:** Implement; strict compiler pass + local harness compile.
- [ ] **Step 2: Verify** push → `android` workflow green.
- [ ] **Step 3: Commit** `feat(android): Maya can use the phone (asking first for anything risky)`
