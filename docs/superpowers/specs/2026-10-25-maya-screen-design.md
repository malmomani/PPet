# Maya — Screen Sense, Roaming and Embodiment (v1)

Date: 2026-10-25
Status: Approved in chat (Q1–Q4 "a", parts 1–2 "yes", embodiment "yes, add it and go ahead").
Builds on: `2026-10-24-maya-design.md` (senses roadmap level C), `2026-10-25-maya-notifications-design.md`.

## 1. Goal

Maya can see what is on the screen of the app the user is in (its text and its buttons). She uses it
two ways:

1. **Screen reading** — she glances at the visible text now and then and may comment on it.
2. **Roaming** — she wanders the whole screen instead of only the bottom edge: walks along the tops
   of buttons and boxes, jumps between them, falls when they scroll away, and sometimes teases the
   user by sitting on the Send button or pretending to press Like.
3. **Embodiment** — brain and body are one: she knows she has a body and where it is, the body
   reports what happens to it, her thoughts choose where she goes, and she acts on silent "body
   thoughts" instead of waiting for the user.

### Success criteria
- Switching to an app → within a few seconds the brain knows its visible text; she sometimes comments
  ("ooh, budget GPUs? planning something?"), within her usual guardrails.
- Passwords, text the user is typing, do-not-watch apps, the lock screen and system UI are never read.
- She visibly roams: hops onto buttons, walks along them, falls when they scroll away.
- When playful and close, she occasionally sits on Send / pokes Like (≤ 1 per 10 min); a tap makes
  her pout and hop off; she never stays on a button longer than 8 s and never really presses anything.
- She never sits on buttons while the keyboard is open, during a call, or in do-not-watch apps.
- Everything works with today's sprite pack (stand-in animations); new art rows drop in later.
- She refers to her body naturally ("I'm sitting on your Send button 😌") and her moves visibly follow
  her thoughts; every few minutes while the user is on the phone she does something of her own accord.

## 2. Decisions (agreed)

| Topic | Decision |
|---|---|
| Eyes | One Android AccessibilityService (text + button positions). No screenshots/OCR. No gestures. |
| When she reads | ~2 s after an app switch, then every 3 min while the visible text changed (Q1 A). |
| Blocking | She may cover a button for 4–8 s as a tease; tap shoos her off (Q2 A). |
| Who decides | Phone runs roaming locally; the brain picks deliberate teases (Q3 A). |
| Art | Stand-ins now, new rows later via an art brief (Q4 A). |
| Embodiment | Self-model in prompt, body reports, brain-chosen moves, silent body thoughts every 2–4 min. |
| Future 3D | The body is described by renderer-agnostic kinematic state (§4.9) so a rigged model can move its legs procedurally later. |

## 3. Protocol (backward compatible)

### Event `glance` (phone → brain)
(`screen` is already the screen on/off event.)
`{type:"glance", id, ts, package, app, lines: string[], targets: string[]}`
- `app` 1–100 chars. `lines`: ≤ 40 items, each 1–200 chars (brain truncates longer items and drops
  extras rather than rejecting). `targets`: subset of the tease tags (§4.3); unknown tags dropped.
- Not queued while disconnected (a stale screen is useless) and not part of `offline_summary`.
- Old brains ignore the unknown type.

### Event `body` (phone → brain)
`{type:"body", id, ts, doing, where, target: tag|null, x, y}`
- `doing`: `idle|walk|sit|perch|jump|fall|nap|peek|tease|dragged`; `where`: `floor|platform|target`;
  `x`, `y`: her feet position as fractions of the screen (0..1, rounded to 2 decimals).
- Sent when (doing, where, target) changes, at most once per 5 s (the latest wins), and once on
  connect. Not queued while disconnected. Unknown `doing`/`where` → `idle`/`floor`.

### Event `body_moment` (phone → brain)
`{type:"body_moment", id, ts, kind: "fell"|"shooed", target: tag|null}` — she fell because her
platform scrolled/vanished; the user tapped her off a tease target. Not queued.

### Action `move` (brain → phone)
`{type:"move", id, ts, intent, target: tag|null}`
- `intent`: `stay|wander|come_closer|go_left|go_right|go_up|go_down|sit|nap|peek|sit_on|poke`.
- `sit_on`/`poke` need `target` (a tease tag) — these are the teases. Old apps ignore `move`.

## 4. Phone

### 4.1 `MayaAccessibilityService`
- Config: event types `typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled|typeWindowsChanged`,
  `canRetrieveWindowContent=true`, flags `flagReportViewIds|flagRetrieveInteractiveWindows`,
  `notificationTimeout=300`, feedback generic. **No** `canPerformGestures`.
- Like the notification listener it forwards to `MayaService` through a process-local bridge
  (`ScreenBridge`) and does nothing when the service isn't running or the switch is off.
- On events it (a) rebuilds the **layout** (targets + platforms) at most every 500 ms while the
  overlay is visible and the screen is usable, and (b) produces a **glance** (text) only when the
  glance scheduler asks.
- Reads only the active window's tree (`rootInActiveWindow`) plus the window list to know whether
  the input-method (keyboard) window is present and where its top is.

### 4.2 Text extraction (glance)
- Visible nodes only (`isVisibleToUser`), `text` else `contentDescription`, ordered top-to-bottom
  then left-to-right; trimmed; consecutive duplicates dropped.
- **Never**: nodes with `isPassword`; the content of editable nodes (`isEditable`) — what the user is
  typing; do-not-watch packages; Maya's own package; the lock screen (keyguard locked) and system UI
  (`com.android.systemui`).
- Caps: ≤ 40 lines, each ≤ 200 chars, ≤ 3000 chars total.
- Pure part lives in core: `ScreenText.extract(nodes: List<TextNode>): List<String>` where
  `TextNode(text, top, left, password, editable)` (no Android types).

### 4.3 Targets and platforms (layout)
- Pure core `Layout.build(nodes: List<UiNode>, screen, sprite, keyboardTop?)` where
  `UiNode(bounds, clickable, editable, viewId?, description?, text?)`.
- **Tease tags** from clickable nodes, matching lower-cased view-id / description / text words:
  `send` (send, إرسال, ارسال), `like` (like, heart, favorite, favourite, love, أعجبني, إعجاب),
  `play` (play), `search` (search, بحث), `share` (share, مشاركة), `comment` (comment, reply, تعليق),
  `follow` (follow, subscribe, متابعة). Words "unlike"/"unfollow" don't match. One node → first tag.
- **Platforms**: the top edge of every visible node (clickable or editable, or an image ≥ 48 dp)
  whose width ≥ 0.6 × sprite width and whose top leaves room for her above it (≥ status bar + sprite
  height), excluding anything below `keyboardTop`. Overlapping/adjacent edges on the same y (±4 px)
  merge. At most 30 platforms (largest first). Plus the **floor**: screen bottom, or the keyboard's
  top when the keyboard is open.

### 4.4 Glance scheduler (pure core `GlanceScheduler`)
- App change → glance due in 2 s (re-armed if the app changes again first).
- Same app → re-check every 180 s; send only if the text hash differs from the last one sent for
  that app.
- Only when connected, the screen is usable, the switch is on, and the app is not do-not-watch.

### 4.5 Body (core `Behavior`, now 2-D)
- State gains `y` and a current platform. New modes: `JUMP` (parabolic arc to a target platform,
  400–900 ms by distance), `FALL` (gravity 3000 dp/s² to the highest platform below, else the
  floor), `PERCH` (sit on a platform), `TEASE`.
- She stands with her feet (sprite anchor y = 60 of 64) on the platform's top edge.
- Platforms update from the layout; if hers disappears or moves > 8 px she falls (or follows a small
  move). The floor is always there.
- Decisions (every 3–8 s while idle): walk along the current platform, jump to a reachable platform
  (Δx ≤ 0.6 screen width, rise ≤ 0.45 screen height), sit/perch, peek over an edge, or drop down.
- Playfulness from the brain's existing `state` action:
  - tier `stranger` or mood `sleepy`/`sulky` → mostly floor; jump chance 10 %;
  - tier `friend` → jump chance 25 %; tier `close` → 40 %; mood `excited`/`happy` adds 10 %.
- Stays: `PERCH` on a non-target platform as long as she likes (sit range). On a tease target she
  stays 4–8 s, hard cap 8 s.
- Keyboard open, in a call (`AudioManager.mode` is `MODE_IN_CALL`/`MODE_IN_COMMUNICATION`), or a
  do-not-watch app → no platforms except the floor, and any tease ends at once.
- Dragging/throwing still work: on release she falls to the platform below her drop point.

### 4.6 Moves and tease execution
- `move` intents run through `Behavior.command(intent, target)`; they replace her current random
  plan and her own wandering resumes after them:
  - `stay` — stop and idle 10 s; `wander` — resume random roaming now;
  - `come_closer` — go to the platform nearest the screen centre (floor centre when none);
  - `go_left|go_right` — walk/jump to the far left/right reachable spot at about her height;
  - `go_up` — jump to the highest reachable platform; `go_down` — drop to the floor;
  - `sit` / `nap` — sit / nap where she is (nap ends on the next move, tap or after 60 s);
  - `peek` — go to the nearest screen side edge and peek;
  - `sit_on` / `poke` — teases (below).
- Moves are ignored while she is being dragged; a move arriving while another runs replaces it.
- Tease: find a visible node with that tag in the current layout (nearest to her). None, or teasing
  is blocked (§4.5) → she plays `happy` instead.
- `sit_on`: she walks/jumps to it and sits centred on top of it, covering it, 4–8 s, then hops off.
- `poke`: she lands next to/above it and plays `poke` (≈ 2.5 s), then hops away.
- A tap on her while teasing → `sulk` for 1.5 s, then she hops off; the tap is still sent to the
  brain as `user_tap` (no chat window opens).
- She never sends a real click or gesture.

### 4.7 Animations and fallbacks
- New names: `jump`, `fall`, `perch`, `poke`, `tease`.
- Core fallback map used when the sprite pack lacks a row: `jump→surprised`, `fall→surprised`,
  `perch→sit`, `poke→wave`, `tease→happy`; anything else unknown → `idle`.
- `art/maya/ROAMING_ART_BRIEF.md` describes the new rows (same 64×64 grid, palette, facing right).

### 4.8 Body reports
- `BodyReporter` (pure core) turns behavior state into `body` events per §3 (change detection,
  5 s throttle with the latest pending state flushed when the window opens, resend on connect).
- `body_moment` is emitted when a fall is caused by her platform vanishing (not by a `go_down`) and
  when a tap ends a tease.

### 4.9 Renderer-agnostic body state (for the future 3D model)
- `Behavior` exposes a `BodyState`: position (feet), velocity, facing, grounded/airborne, mode,
  current gesture (wave/happy/…), and a look-at point (the target she's heading to, or the screen
  centre while speaking).
- The sprite renderer maps `BodyState` to sheet rows (§4.7). A later skeletal/VRM renderer consumes
  the same state to drive legs with procedural locomotion/IK (step cycle from velocity, feet
  planted on the platform, arms for gestures, head toward the look-at point) — no protocol or brain
  change needed.

### 4.10 Settings
- Row "Screen access" (status: our service in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`;
  Grant → `Settings.ACTION_ACCESSIBILITY_SETTINGS`). README covers the "Allow restricted settings"
  step for sideloaded apps.
- Switch "Let Maya read my screen" (`Prefs.readScreen`, default true). Off → no glances, no
  targets/platforms (she roams only the floor), no teases.

## 5. Brain

- **Protocol:** `GlanceEvent` (lenient caps per §3), `BodyEvent`, `BodyMomentEvent`; `Move` action
  with `intent: Literal[*MOVE_INTENTS]`, `target: Literal[*TEASE_TARGETS] | None`.
- **Mind:**
  - Keeps the latest screen in memory (not persisted): app, lines, targets, time.
  - Memory turn: `event: "on screen in {app}: {headline}"` — headline = first line with ≥ 12
    letters (else the first line), ≤ 120 chars.
  - Thinker context: when the latest screen is ≤ 10 min old, adds "On screen now ({app}):" with up to
    25 lines / 1500 chars.
  - A glance is activity-neutral (no came-back bump, no idle reset), like notifications.
- **Initiative:** `+4` urge with reason `"something new on screen in {app}"`, at most once per 5 min.
- **Self-model:** the persona block gains a fixed paragraph: she is a small girl who lives on the
  user's phone screen; she can walk, jump onto buttons and boxes, sit, nap, peek from the edges, and
  get picked up or thrown by the user; she feels it when things move under her.
- **Body awareness:**
  - Mind keeps the latest `body` state (not persisted). Every thinker prompt includes
    `"Your body: {doing} on {where/target} in {app}, {region}"` (region from x/y: e.g. "bottom left").
  - `body_moment` → memory turn (`"you fell when the screen scrolled"`, `"the user shooed you off
    {target}"`) and a reaction check like a drag (it may make her speak); drags/throws keep today's
    handling.
- **Moves from thoughts:** `Thought` gains `move: {intent, target?} | null`. The thinker prompt lists
  the intents (and `sit_on`/`poke` only when teasing is allowed). A valid move is sent as a `move`
  action after the `say` (if any); `animation` stays for gestures. Invalid intents/targets → dropped.
- **Silent body thoughts:**
  - A timer in Mind fires every 120–240 s (random) when: connected, screen usable, the user was
    active in the last 5 min (app/screen events), not quiet hours, not shushed, no response in flight.
  - It runs a short "body" prompt (same self-model + body + screen context) that must return
    `{thought, move, animation}` with `speak` forced false; the thought is stored like other thoughts.
  - It does not bump or reset the initiative urge and does not count as user activity.
- **Teases** (`move` with `sit_on`/`poke`):
  - Allowed when: latest screen ≤ 10 min old and has targets; tier `friend` or `close`; mood
    `happy`, `curious` or `excited`; ≥ 600 s since the last tease (persisted); not quiet hours;
    not shushed; connected.
  - When allowed, the prompt lists `sit_on`/`poke` with the current targets; otherwise they're not
    mentioned and any tease returned is dropped (other intents still go through). A sent tease is
    recorded (`"you teased the user: {intent} {target}"` as an event turn) and starts the 600 s
    cooldown. Silent body thoughts may tease too, under the same rules.
- **Simulator:** `/glance <app> <line>[ | <line>...] [targets=send,like]` sends a `GlanceEvent`;
  `/body <doing> [where] [target]` and `/moment fell|shooed [target]` send body events; incoming
  `move` actions are printed.

## 6. Privacy

Only the visible text of the current app (never passwords, never what the user is typing, never
do-not-watch apps, the lock screen or system UI) and a short list of button tags leave the phone,
only to the user's PC. One headline per glance is stored in her memory; the full text is kept only
in RAM as "the latest screen". Turning the switch off or revoking access stops all of it.

## 7. Testing

- Core (Kotlin, JUnit): text extraction (order, password/editable skipped, caps, duplicates);
  tag matching (incl. "unlike" not matching, Arabic words); platform building (width/room filters,
  merging, keyboard cut, cap 30); glance scheduler (2 s settle, re-arm, 180 s, hash dedupe,
  gating); behavior 2-D (jump arc lands on target, fall to platform below/floor, platform removed →
  fall, keyboard → floor only, tease sit 4–8 s with 8 s cap, tap during tease → sulk then off,
  playfulness table, each move intent, move replaced by move, moves ignored while dragged);
  body reporter (change detection, 5 s throttle, flush, resend on connect); body state for renderers;
  animation fallbacks; protocol encode `glance`/`body`/`body_moment`, decode `move`.
- Brain (pytest): `GlanceEvent` parse + lenient caps; memory headline; thinker context age rule;
  urge +4 with 5-min limit; activity-neutral; body line in prompt; body moments → memory; move parsed
  and sent after say, invalid dropped; tease allowed/blocked by each guardrail; cooldown persisted;
  silent body thought timer (gating, no urge change, speak forced false); sims `/glance`, `/body`,
  `/moment`.
- App: CI build; on-device checklist (grant access, glance in Chrome, password field skipped,
  typing not read, do-not-watch app, roaming onto buttons, scroll → fall, keyboard → floor, tease
  sit + tap shoo, call → no teases, switch off, she mentions where she is, she moves on her own every
  few minutes).

## 8. Out of scope

Real taps/gestures (roadmap "phone actions"); screenshots/vision (roadmap "vision"); reading other
windows than the active one; per-app reading rules beyond do-not-watch; non-English/Arabic tag words
beyond the list above.
