# Maya Screen Sense, Roaming and Embodiment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Maya reads the current screen, roams over its buttons in 2-D, and her brain knows and drives her body.

**Architecture:** An AccessibilityService feeds pure core models (text lines, tease targets, platforms) to MayaService; a 2-D core `Behavior` moves her between platforms and executes brain `move` intents; the body reports `body`/`body_moment` events; the brain keeps the latest glance and body in its prompt, picks moves (incl. teases) and runs silent body thoughts.

**Tech Stack:** Python brain (pydantic, pytest); Kotlin core (kotlinx-serialization, JUnit); Android framework `AccessibilityService`, overlay windows.

**Spec:** `docs/superpowers/specs/2026-10-25-maya-screen-design.md`

## Global Constraints

- Wire (snake_case JSON):
  - `glance{type,id,ts,package,app,lines:string[],targets:string[]}` — app 1–100; lines ≤ 40 items, each 1–200 chars (brain truncates/drops, never rejects); unknown targets dropped.
  - `body{type,id,ts,doing,where,target:string|null,x,y}` — doing `idle|walk|sit|perch|jump|fall|nap|peek|tease|dragged` (unknown → `idle`), where `floor|platform|target` (unknown → `floor`), x/y floats 0..1 (clamped), 2 decimals.
  - `body_moment{type,id,ts,kind:"fell"|"shooed",target:string|null}`.
  - `move{type,id,ts,intent,target:string|null}` — intents `stay|wander|come_closer|go_left|go_right|go_up|go_down|sit|nap|peek|sit_on|poke`; `sit_on`/`poke` require a target.
- Tease tags (exact order): `send, like, play, search, share, comment, follow`.
- `glance`, `body`, `body_moment` are never queued offline and never in `offline_summary`; none count as user activity.
- Never read `isPassword` nodes or the content of `isEditable` nodes; skip do-not-watch packages, Maya's own package, `com.android.systemui`, and the locked keyguard. No `canPerformGestures`, no real clicks.
- Brain numbers: glance urge `+4` at most once per 300 s; tease cooldown 600 s (persisted); body thoughts every 120–240 s, only if user active within 300 s; fell `+6`, shooed `+10`.
- Phone numbers: glance settle 2 000 ms, re-check 180 000 ms; layout rebuild ≤ every 500 ms; body report throttle 5 000 ms; tease sit 4 000–8 000 ms (hard cap 8 000), poke 2 500 ms, shoo sulk 1 500 ms; jump 400–900 ms; gravity 3 000 dp/s²; platform follow ≤ 8 px else fall; ≤ 30 platforms; reach Δx ≤ 0.6 screenW, rise ≤ 0.45 screenH.
- Brain tests: `cd brain && .venv/bin/pytest -q` (+ `-W error`). Core: `cd android/core && gradle test -I /tmp/claude-0/-home-user-PPet/d6eab334-7ee4-5d52-a74e-33e22d437b37/scratchpad/init.gradle`. `:app` compiles in CI only (AGP 8.7.3, compileSdk 35, minSdk 29, no AndroidX).
- Commit trailer on every commit: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01WLLDks9zoTp2qVAJ3nppe8`. Never commit `.superpowers/`.

## Review Focus

1. **Scrolling feeds / animated screens** (content events many times a second) → layout rebuilt ≤ 2/s, glances only on schedule, body reports ≤ 1 per 5 s, no jank. (T4 `body_reporter_throttles`, T7 throttle in code + manual.)
2. **Her platform vanishes mid-jump or mid-tease** (app switch, scroll) → she falls to the floor or the platform below, never hangs in mid-air or stays on an invisible target. (T5 `platform_removed_mid_jump_falls`, T6 `tease_target_vanishes_ends_tease`.)
3. **Old brain / old app** → unknown `glance`/`body`/`body_moment`/`move` ignored on both sides without errors. (T1 `test_old_types_still_parse`; T3 `unknown_action_ignored`.)
4. **LLM returns junk moves** (`"intent":"fly"`, tease without target, target not on screen, tease during cooldown) → dropped silently, the rest of the thought still applies. (T2 `test_invalid_moves_dropped`.)
5. **Screen rotates / keyboard opens while perched** → floor moves to the keyboard top, platforms below it removed, she falls/repositions, no off-screen sprite. (T5 `keyboard_floor_and_restricted`, `resize_keeps_on_screen`.)

---

### Task 1: Brain — protocol, world state, glance/body/moment handling, sim

**Files:** Modify `brain/maya_brain/{protocol.py, mind.py, initiative.py, sim.py}`, `brain/README.md`; Test `brain/tests/test_screen.py`

**Interfaces:**
- Produces in `protocol.py`: `TEASE_TARGETS`, `MOVE_INTENTS`, `TEASE_INTENTS = ("sit_on","poke")`, `BODY_DOING`, `BODY_WHERE`; events `GlanceEvent(package: str, app: str, lines: list[str], targets: list[str])` type `"glance"`, `BodyEvent(doing, where, target: str|None, x: float, y: float)` type `"body"`, `BodyMomentEvent(kind: Literal["fell","shooed"], target: str|None)` type `"body_moment"`; action `Move(intent: Literal[*MOVE_INTENTS], target: Literal[*TEASE_TARGETS] | None = None)` type `"move"`, in the `Action` union.
- Mind: `self.glance: GlanceEvent | None`, `self.glance_at: datetime | None`, `self.body: BodyEvent | None` (public, read by Task 2).
- Initiative: `on_event` handles `GlanceEvent` (+4, reason `"something new on screen in {app}"`, gap 300 s, persisted key `glanced`) and `BodyMomentEvent` (fell +6 reason `"you just fell because the screen moved under you"`; shooed +10 reason `"the user just shooed you off the {target} button"` / `"…shooed you away"` when target null). Neither is in `USER_ACTIVITY`.
- Memory turns (`event` role): glance `"on screen in {app}: {headline}"` (headline = first line with ≥ 12 letters else first line, ≤ 120 chars; no turn when lines empty); fell `"you fell when the screen moved under you"`; shooed `"the user shooed you off the {target} button"` (or `"the user shooed you away"`). Body events: no turn.
- Sim: `/glance <app> <line>[ | <line>...] [targets=send,like]`, `/body <doing> [where] [target]`, `/moment fell|shooed [target]`; prints `move` actions as `[move] <intent> <target>`.

- [ ] **Step 1: Write failing tests** — `test_parse_glance_lenient` (41 lines → 40, 250-char line → 200, blank lines dropped, `targets=["send","rocket","send"]` → `["send"]`), `test_parse_body_defaults` (unknown doing/where → idle/floor, x=1.7 → 1.0, unknown target → None), `test_parse_body_moment`, `test_move_serializes` (`Move(intent="sit_on", target="send")` JSON has `"type":"move"`), `test_old_types_still_parse` (screen on/off, notification unchanged), `test_glance_memory_headline` (lines `["12:30","Best budget GPUs 2026 for gaming"]` → `"on screen in Chrome: Best budget GPUs 2026 for gaming"`), `test_glance_bump_once_per_5_min`, `test_glance_and_body_not_user_activity` (no came-back bump, `_last_event` unchanged), `test_body_stored_no_memory`, `test_moments_memory_and_bumps`, `test_sim_glance_body_moment_commands`.
- [ ] **Step 2: Run** `cd brain && .venv/bin/pytest tests/test_screen.py -q` — FAIL.
- [ ] **Step 3: Implement** per Interfaces (lenient validators `mode="before"`).
- [ ] **Step 4: Run** full suite `.venv/bin/pytest -q -W error` — PASS.
- [ ] **Step 5: Commit** `feat(brain): glance, body and body-moment events`

---

### Task 2: Brain — self-model, body/screen context, moves, teases, silent body thoughts

**Files:** Modify `brain/maya_brain/{thinker.py, mind.py, initiative.py}`; Test `brain/tests/test_embodiment.py`

**Interfaces:**
- Consumes: Task 1 (`Mind.glance/glance_at/body`, `Move`, constants).
- `thinker.py`:
  - `SELF_MODEL` (exact): `"You have a small body: you live on the user's phone screen as a little pixel girl. You can walk, jump onto buttons and boxes, sit, nap and peek from the screen edges; the user can tap you, pick you up and throw you. You feel it when things move under you. Your body is yours: use it to show what you feel and want."` — added to the system prompt right after `persona.render()`.
  - `describe_body(body: BodyEvent, app: str | None) -> str` → e.g. `"Your body: sitting on the send button, bottom right of the screen, in WhatsApp."` Doing words: idle standing, walk walking, sit sitting, perch perched, jump jumping, fall falling, nap napping, peek peeking from the edge, tease teasing the user, dragged being carried by the user. Where: floor `at the bottom`, platform `on a button`, target `on the {target} button`. Region rows top/middle/bottom at y < 0.34 / < 0.67 / else; columns left/centre/right same cuts; `"{row} {col}"`, except `"centre"` alone when both middle/centre.
  - `Context` gains `body: str | None = None`, `screen_app: str | None = None`, `screen_lines: tuple[str, ...] = ()`, `tease_targets: tuple[str, ...] = ()`.
  - Prompt: when `screen_lines`: `"On screen now ({screen_app}):"` + lines (≤ 25 lines, ≤ 1500 chars total). Rules gain `"move"` key: `'"move" (object or null): {"intent": one of stay, wander, come_closer, go_left, go_right, go_up, go_down, sit, nap, peek[, sit_on, poke], "target": null}'`; when `tease_targets`: `'- sit_on/poke tease the user and need "target": one of {targets}.'`.
  - `Thought.move: MoveChoice | None` (`MoveChoice(intent: str, target: str | None)`), validator → None for unknown intent, tease intent without a known target, or non-object.
  - Trigger `"body"`: `think` forces `speak=False`, `say=""`; the user message ends with `"Now: A quiet moment. Decide what your body does next; you won't speak."` (ctx.detail).
- `initiative.py`: `body_allowed() -> bool` (not silenced, not in quiet hours, last activity ≤ 300 s ago); `tease_ready() -> bool` (≥ 600 s since last tease, not silenced, not quiet); `teased()` (persist `last_tease`).
- `mind.py`:
  - `_ctx(trigger, detail) -> Context` fills app (as today), `body` (via `describe_body`) when known, screen lines when `glance_at` ≤ 10 min old, `tease_targets` = glance targets when `_tease_allowed()`; every `Context(...)` in Mind goes through it.
  - `_tease_allowed()`: glance ≤ 10 min old with targets, tier in {friend, close}, mood in {happy, curious, excited}, `initiative.tease_ready()`, `_can_be_heard()`.
  - `_apply` sends `Move` after the say (if any): tease intents only when `_tease_allowed()` and target in the glance targets (then `initiative.teased()` + event turn `"you teased the user: {intent} {target}"`); other intents always; invalid → dropped.
  - Body thoughts: `self._rng = random.Random()`, `self._next_body_at: datetime | None`; in `tick()` after `_maybe_initiate()`: when `_can_be_heard()` and `initiative.body_allowed()` and now ≥ `_next_body_at` (None → schedule only), think with `_ctx("body", …)`, `_apply(thought)`, schedule next in `rng.uniform(120, 240)` s. No urge change.

- [ ] **Step 1: Write failing tests** — `test_self_model_in_prompt`, `test_describe_body_words_and_regions` (incl. `"centre"`), `test_body_and_screen_in_prompt` (stale glance > 10 min omitted; 30 lines → 25), `test_move_sent_after_say` (action order Say, Move), `test_invalid_moves_dropped` (`fly`; `sit_on` no target; target not on screen; tease in cooldown → no Move, say still sent), `test_tease_guardrails` (each of: stranger tier, sulky mood, quiet hours, shushed, disconnected, no glance → not offered in prompt and dropped), `test_tease_cooldown_persisted` (new Initiative from same db still blocks), `test_body_thought_timer` (first tick schedules; fires after 240 s; not when user idle > 300 s, quiet, shushed or disconnected; speak forced false; urge unchanged).
- [ ] **Step 2: Run** `.venv/bin/pytest tests/test_embodiment.py -q` — FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** full suite with `-W error` — PASS.
- [ ] **Step 5: Commit** `feat(brain): Maya knows and drives her body`

---

### Task 3: Core — protocol, screen text, layout

**Files:** Modify `android/core/src/main/kotlin/com/maya/core/Protocol.kt`; Create `ScreenText.kt`, `Layout.kt`; Tests `ProtocolTest.kt`, `ScreenTextTest.kt`, `LayoutTest.kt`

**Interfaces:**
- Events: `Glance(packageName: String, app: String, lines: List<String>, targets: List<String>, id, ts)` → `"glance"`; `Body(doing: String, where: String, target: String?, x: Double, y: Double, id, ts)` → `"body"` (x/y rounded to 2 decimals on encode); `BodyMoment(kind: String, target: String?, id, ts)` → `"body_moment"`. Action `Move(intent: String, target: String?)` decoded from `"move"`; unknown intent → null action.
- `ScreenText.kt`: `data class TextNode(val text: String, val top: Int, val left: Int, val password: Boolean, val editable: Boolean)`; `fun extractLines(nodes: List<TextNode>): List<String>` (order top then left; skip password/editable; trim; drop blanks and consecutive duplicates; ≤ 40 lines, each ≤ 200 chars without splitting a surrogate pair, ≤ 3000 chars total).
- `Layout.kt`: `data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)` (+ `width`, `height`, `centerX`); `data class UiNode(val bounds: Box, val clickable: Boolean, val editable: Boolean, val image: Boolean, val viewId: String?, val description: String?, val text: String?)`; `data class Target(val tag: String, val bounds: Box)`; `data class Platform(val left: Int, val right: Int, val top: Int, val tag: String? = null)`; `data class Layout(val platforms: List<Platform>, val targets: List<Target>)` with `Layout.EMPTY`; `val TEASE_TAGS: List<String>`; `fun tagOf(n: UiNode): String?`; `fun buildLayout(nodes: List<UiNode>, screenW: Int, spriteW: Int, spriteH: Int, statusBarH: Int, keyboardTop: Int?, minImagePx: Int): Layout`.
- Tag words per spec §4.3 (lower-cased, matched as whole words in view-id tail after `/`, description, text; `unlike`/`unfollow` never match; first tag in `TEASE_TAGS` order wins). Platform rules per spec §4.3; merging: same top ±4 px and overlapping/adjacent x → one platform spanning both (keeps the tag of a target if any); keep the 30 widest.

- [ ] **Step 1: Write failing tests** — `encode_glance_body_moment` (exact keys, null target, x=0.456 → 0.46), `decode_move` (+ `unknown_action_ignored` for intent `fly` and type `teleport`), `extract_order_and_privacy`, `extract_caps_and_dupes`, `tag_words_en_ar` (`com.whatsapp:id/send` → send, description `"Like"` → like, `"Unlike"` → null, `"إرسال"` → send), `platforms_filters` (too narrow, no room above, below keyboard, images < minImagePx), `platforms_merge_and_cap`, `targets_from_clickables_only`.
- [ ] **Step 2: Run** core tests — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** core tests — PASS.
- [ ] **Step 5: Commit** `feat(android): core screen text, layout and body protocol`

---

### Task 4: Core — glance scheduler and body reporter

**Files:** Create `android/core/src/main/kotlin/com/maya/core/{GlanceScheduler.kt, BodyReporter.kt}`; Tests `GlanceSchedulerTest.kt`, `BodyReporterTest.kt`

**Interfaces:**
- `class GlanceScheduler(settleMs: Long = 2_000, periodMs: Long = 180_000)`: `fun onApp(pkg: String?, nowMs: Long)` (due at now+settle; null pkg → nothing due); `fun poll(nowMs: Long, allowed: Boolean): Boolean` (true once when due and allowed; while not allowed it stays due); `fun taken(pkg: String, lines: List<String>, nowMs: Long): Boolean` (next due at now+period; returns true iff `lines.hashCode()` differs from the last sent for `pkg`, and records it).
- `data class BodySnapshot(val doing: String, val where: String, val target: String?, val x: Double, val y: Double)`; `class BodyReporter(throttleMs: Long = 5_000)`: `fun update(s: BodySnapshot, nowMs: Long): BodySnapshot?` (returns s to send when (doing, where, target) differs from the last sent and the throttle window is open; otherwise keeps it pending); `fun poll(nowMs: Long): BodySnapshot?` (pending one once the window opens); `fun reset()` (next update sends at once even if unchanged — used on connect).

- [ ] **Step 1: Write failing tests** — `settle_then_due_once`, `app_change_rearms`, `period_and_hash_dedupe`, `not_allowed_keeps_due`, `body_reporter_change_detection`, `body_reporter_throttles` (3 changes in 1 s → first sent, last pending, sent by poll at 5 s), `reset_resends`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(android): glance scheduler and body reporter`

---

### Task 5: Core — 2-D Behavior (platforms, jump, fall, roaming, body state)

**Files:** Rewrite `android/core/src/main/kotlin/com/maya/core/Behavior.kt`; Rewrite `BehaviorTest.kt`

**Interfaces:**
- Consumes: Task 3 `Layout`, `Platform`, `Target`, `Box`.
- `class Behavior(screenW: Int, screenH: Int, spriteW: Int, spriteH: Int, footY: Int, rng: Random, speedPxPerS: Float, gravityPxPerS2: Float)`; `x`, `y` = sprite box top-left (Float); feet at `(x + spriteW/2, y + footY)`.
- Keeps (same meaning): `facingLeft`, `animation`, `tick(dtMs)`, `play(name)`, `setPose(name?)`, `setConnected(on)`, `dragStart()`, `BODY_POSES`.
- Changes/new: `dragEnd(x: Float, y: Float)` (then falls), `setFloor(feetY: Int)`, `setLayout(layout: Layout)`, `setRestricted(on: Boolean)` (platforms ignored; leaves any platform by falling), `setTemper(mood: String, tier: String)`, `val state: BodyState`.
- `data class BodyState(val feetX: Float, val feetY: Float, val vx: Float, val vy: Float, val facingLeft: Boolean, val grounded: Boolean, val doing: String, val where: String, val target: String?, val gesture: String?, val lookX: Float, val lookY: Float)` — `doing`/`where` use the wire vocabulary.
- Movement: walk stays within the current platform's [left, right] (floor = whole width); jump = parabolic arc (apex 0.25×distance above the higher end) to a reachable platform, duration `400 + 500 × dist / screenDiag` ms clamped 400–900, lands with feet on `top`; fall under gravity to the highest platform whose x-range contains her feet and whose top is below her, else the floor. Platform moves ≤ 8 px → follow; more or gone → fall. Decisions every 3–8 s idle: walk / jump / sit / peek / drop; jump chance by temper: stranger or sleepy/sulky 10 %, friend 25 %, close 40 %, +10 % for excited/happy. Animations: jump → `jump`, fall → `fall`, perched sit → `perch`.
- Disconnected → she naps where she stands (physics still runs: a vanished platform still drops her); poses hold position.
- Look-at: the destination while walking/jumping toward something, the screen centre while the pose is `talk`, else straight ahead (feet + facing).

- [ ] **Step 1: Write failing tests** — `starts_on_floor_right_side`, `walk_stays_on_platform`, `jump_lands_on_target_platform` (feetY == top after duration), `jump_reach_limits`, `fall_to_platform_below_or_floor`, `platform_moved_small_follows_large_falls`, `platform_removed_mid_jump_falls`, `keyboard_floor_and_restricted` (setFloor raises floor; restricted → falls off platform, never jumps), `drag_end_falls`, `temper_changes_jump_rate` (seeded rng, 1 000 decisions ±5 %), `resize_keeps_on_screen` (new Behavior with old position clamps), `body_state_fields`, plus the kept tests for poses, play one-shots, nap when disconnected.
- [ ] **Step 2–4:** FAIL → implement → PASS (whole core suite).
- [ ] **Step 5: Commit** `feat(android): 2-D roaming body`

---

### Task 6: Core — move intents, teases, moments, animation fallbacks

**Files:** Modify `android/core/src/main/kotlin/com/maya/core/{Behavior.kt, SpriteConfig.kt}`; Tests `BehaviorCommandTest.kt`, `SpriteConfigTest.kt`

**Interfaces:**
- Consumes: Task 5 `Behavior`.
- `fun command(intent: String, tag: String?)` per spec §4.6 (ignored while dragging; replaces a running command; unknown → ignored; tease with no matching target or while restricted → `play("happy")`; nearest target by feet distance).
- `fun tapped(): Boolean` — true iff a tease was running: sulk 1 500 ms then hop off, records `Moment("shooed", tag)`.
- `data class Moment(val kind: String, val target: String?)`; `fun drainMoments(): List<Moment>` — `fell` only when a fall starts because her platform vanished/moved > 8 px (not `go_down`, drag, or restriction).
- Tease timing: `sit_on` centre of sprite = centre of target, stays `rng` 4 000–8 000 ms, hard cap 8 000; `poke` lands beside/above, `poke` animation 2 500 ms, then hops away. Tease target vanishing ends the tease (fall). `state.where == "target"`, `state.target == tag` while on it; `doing == "tease"`.
- `SpriteConfig.kt`: `val ANIMATION_FALLBACKS = mapOf("jump" to "surprised", "fall" to "surprised", "perch" to "sit", "poke" to "wave", "tease" to "happy")`; `SpriteConfig.spec(name)` → own row, else fallback's row, else idle.

- [ ] **Step 1: Write failing tests** — one per intent (`stay`, `wander`, `come_closer`, `go_left`, `go_right`, `go_up`, `go_down`, `sit`, `nap` ends after 60 s, `peek`), `command_replaces_command`, `command_ignored_while_dragging`, `sit_on_covers_target_4_to_8s_cap`, `poke_then_hop`, `tease_missing_or_restricted_plays_happy`, `tap_during_tease_sulks_and_shoos`, `tap_without_tease_false`, `tease_target_vanishes_ends_tease`, `fell_moment_only_on_vanish`, `fallback_rows`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(android): brain-driven moves and teases`

---

### Task 7: App — accessibility service, screen pipeline, settings

**Files:** Create `android/app/src/main/kotlin/com/maya/app/senses/{MayaAccessibilityService.kt, ScreenBridge.kt}`, `android/app/src/main/res/xml/accessibility_config.xml`; Modify `MayaService.kt` (OverlayHook + wiring), `Prefs.kt`, `SettingsActivity.kt`, `AndroidManifest.xml`, `res/values/strings.xml`, `android/README.md`, `overlay/OverlayBody.kt` (no-op `setScreenLayout` only)

**Interfaces:**
- Consumes: Task 3 (`UiNode`, `TextNode`, `buildLayout`, `extractLines`, `Glance`, `tagOf`), Task 4 `GlanceScheduler`.
- `object ScreenBridge { var sink: ScreenSink?; var service: GlanceSource? }` with `interface ScreenSink { fun onLayout(pkg: String, layout: Layout, keyboardTop: Int?); fun onApp(pkg: String?) }` and `interface GlanceSource { fun glance(): GlanceData? }`, `data class GlanceData(val packageName: String, val app: String, val lines: List<String>, val targets: List<String>)`.
- Service config per spec §4.1 (description string `"Lets Maya see the text and buttons of the app you're using. She never presses anything."`). Layout rebuild throttled to 500 ms, only when `sink != null`; skip own package, `com.android.systemui`, locked keyguard; keyboard top from the `TYPE_INPUT_METHOD` window bounds. All work in try/catch; node trees recycled per platform rules (no-op on API 33+).
- `OverlayHook.setScreenLayout(layout: Layout, keyboardTop: Int?, restricted: Boolean)`.
- MayaService: when `prefs.readScreen`, sets `ScreenBridge.sink` (clears only its own on destroy, like `NotificationBridge`); owns `GlanceScheduler`; a 1 s main-thread loop: `poll(now, allowed)` where allowed = connected && screen usable && not do-not-watch; then `ScreenBridge.service?.glance()` → `taken()` → `emit(Glance(...))`; `onLayout` → `overlay?.setScreenLayout(layout, keyboardTop, restricted)` with restricted = keyboard open || do-not-watch || `AudioManager.mode` in (`MODE_IN_CALL`, `MODE_IN_COMMUNICATION`).
- `Prefs.readScreen` default true; Settings row "Screen access" (status from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` contains our flattened component; Grant → `ACTION_ACCESSIBILITY_SETTINGS`) and switch "Let Maya read my screen" (restarts the service like the notification switch).
- README section "Let Maya see your screen" (grant, restricted-settings step, privacy line, switch).

- [ ] **Step 1: Implement**; strict compiler pass over every changed file (names from core, `android.accessibilityservice.*` APIs at minSdk 29, nullability).
- [ ] **Step 2: Verify** push → `android` workflow green.
- [ ] **Step 3: Commit** `feat(android): Maya reads the screen`

---

### Task 8: App — 2-D overlay body, moves, body reports, art brief

**Files:** Modify `android/app/src/main/kotlin/com/maya/app/overlay/{OverlayBody.kt, SpriteSheet.kt}`, `MayaService.kt` (Move routing only if needed); Create `art/maya/ROAMING_ART_BRIEF.md`

**Interfaces:**
- Consumes: Task 5/6 `Behavior` API, Task 4 `BodyReporter`, Task 3 `Body`, `BodyMoment`, `Move`, Task 7 `setScreenLayout`.
- OverlayBody: builds `Behavior(screenW, screenH, spriteSize, spriteH, footY = 60 * scale (anchor from config, default frameH - 4), …, gravity 3000 dp/s²)`; window position = `behavior.x/y` every frame (both axes); default floor = today's resting line; `setScreenLayout` → `setFloor(keyboardTop ?: default)`, `setLayout`, `setRestricted`; `handle(Move)` → `behavior.command`; `handle(State)` → `behavior.setTemper`; tap → if `behavior.tapped()` no chat, still `emit(UserTap())`; drag/throw end → `dragEnd(x, y)`; each frame: `drainMoments()` → `emit(BodyMoment(...))`; `BodyReporter.update/poll` from `behavior.state` (x/y as fractions of the screen) → `emit(Body(...))`; `reset()` on connect.
- `SpriteSheet.frame/spec` use the core fallback (Task 6).
- Art brief: rows `jump` (4), `fall` (2), `perch` (2, legs dangling), `poke` (4, finger tap), `tease` (4, tongue out / giggle); same 64×64 grid, palette, anchor (32,60), facing right; appended as rows 12–16; json entries like the existing ones.

- [ ] **Step 1: Implement**; strict compiler pass (exhaustive `when` over `Action` incl. `Move`).
- [ ] **Step 2: Verify** push → `android` workflow green.
- [ ] **Step 3: Commit** `feat(android): Maya roams the whole screen and reports her body`
