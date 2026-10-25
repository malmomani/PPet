# Maya — Screen Sense and Roaming (v1)

Date: 2026-10-25
Status: Design approved in chat (Q1–Q4 all "a", parts 1 and 2 "yes"); spec pending review.
Builds on: `2026-10-24-maya-design.md` (senses roadmap level C), `2026-10-25-maya-notifications-design.md`.

## 1. Goal

Maya can see what is on the screen of the app the user is in (its text and its buttons). She uses it
two ways:

1. **Screen reading** — she glances at the visible text now and then and may comment on it.
2. **Roaming** — she wanders the whole screen instead of only the bottom edge: walks along the tops
   of buttons and boxes, jumps between them, falls when they scroll away, and sometimes teases the
   user by sitting on the Send button or pretending to press Like.

### Success criteria
- Switching to an app → within a few seconds the brain knows its visible text; she sometimes comments
  ("ooh, budget GPUs? planning something?"), within her usual guardrails.
- Passwords, text the user is typing, do-not-watch apps, the lock screen and system UI are never read.
- She visibly roams: hops onto buttons, walks along them, falls when they scroll away.
- When playful and close, she occasionally sits on Send / pokes Like (≤ 1 per 10 min); a tap makes
  her pout and hop off; she never stays on a button longer than 8 s and never really presses anything.
- She never sits on buttons while the keyboard is open, during a call, or in do-not-watch apps.
- Everything works with today's sprite pack (stand-in animations); new art rows drop in later.

## 2. Decisions (agreed)

| Topic | Decision |
|---|---|
| Eyes | One Android AccessibilityService (text + button positions). No screenshots/OCR. No gestures. |
| When she reads | ~2 s after an app switch, then every 3 min while the visible text changed (Q1 A). |
| Blocking | She may cover a button for 4–8 s as a tease; tap shoos her off (Q2 A). |
| Who decides | Phone runs roaming locally; the brain picks deliberate teases (Q3 A). |
| Art | Stand-ins now, new rows later via an art brief (Q4 A). |

## 3. Protocol (backward compatible)

### Event `screen` (phone → brain)
`{type:"screen", id, ts, package, app, lines: string[], targets: string[]}`
- `app` 1–100 chars. `lines`: ≤ 40 items, each 1–200 chars (brain truncates longer items and drops
  extras rather than rejecting). `targets`: subset of the tease tags (§4.3); unknown tags dropped.
- Not queued while disconnected (a stale screen is useless) and not part of `offline_summary`.
- Old brains ignore the unknown type.

### Action `tease` (brain → phone)
`{type:"tease", id, ts, target: tag, style: "sit"|"poke"}`. Old apps ignore the unknown type.

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

### 4.6 Tease execution
- `tease{target, style}` → find a visible node with that tag in the current layout (nearest to her).
  None, or teasing is blocked (§4.5) → she plays `happy` instead.
- `sit`: she walks/jumps to it and sits centred on top of it, covering it, 4–8 s, then hops off.
- `poke`: she lands next to/above it and plays `poke` (≈ 2.5 s), then hops away.
- A tap on her while teasing → `sulk` for 1.5 s, then she hops off; the tap is still sent to the
  brain as `user_tap` (no chat window opens).
- She never sends a real click or gesture.

### 4.7 Animations and fallbacks
- New names: `jump`, `fall`, `perch`, `poke`, `tease`.
- Core fallback map used when the sprite pack lacks a row: `jump→surprised`, `fall→surprised`,
  `perch→sit`, `poke→wave`, `tease→happy`; anything else unknown → `idle`.
- `art/maya/ROAMING_ART_BRIEF.md` describes the new rows (same 64×64 grid, palette, facing right).

### 4.8 Settings
- Row "Screen access" (status: our service in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`;
  Grant → `Settings.ACTION_ACCESSIBILITY_SETTINGS`). README covers the "Allow restricted settings"
  step for sideloaded apps.
- Switch "Let Maya read my screen" (`Prefs.readScreen`, default true). Off → no glances, no
  targets/platforms (she roams only the floor), no teases.

## 5. Brain

- **Protocol:** `ScreenEvent` model (lenient caps per §3); `Tease` action with
  `target: Literal[*TEASE_TARGETS]`, `style: Literal["sit","poke"]`.
- **Mind:**
  - Keeps the latest screen in memory (not persisted): app, lines, targets, time.
  - Memory turn: `event: "on screen in {app}: {headline}"` — headline = first line with ≥ 12
    letters (else the first line), ≤ 120 chars.
  - Thinker context: when the latest screen is ≤ 10 min old, adds "On screen now ({app}):" with up to
    25 lines / 1500 chars.
  - A screen event is activity-neutral (no came-back bump, no idle reset), like notifications.
- **Initiative:** `+4` urge with reason `"something new on screen in {app}"`, at most once per 5 min.
- **Teases:**
  - Allowed when: latest screen ≤ 10 min old and has targets; tier `friend` or `close`; mood
    `happy`, `curious` or `excited`; ≥ 600 s since the last tease (persisted); not quiet hours;
    not shushed; connected.
  - When allowed, the thinker prompt offers an optional key
    `"tease": {"target": <one of the current targets>, "style": "sit"|"poke"}`; otherwise the key is
    not mentioned and any tease returned is dropped. A valid tease is sent after the `say` (if any)
    and recorded (`"you teased the user by sitting on {target}"` as an event turn).
- **Simulator:** `/screen <app> <line>[ | <line>...] [targets=send,like]` sends a `ScreenEvent`;
  incoming `tease` actions are printed.

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
  playfulness table); animation fallbacks; protocol encode `screen`, decode `tease`.
- Brain (pytest): `ScreenEvent` parse + lenient caps; memory headline; thinker context age rule;
  urge +4 with 5-min limit; activity-neutral; tease allowed/blocked by each guardrail; tease
  cooldown persisted; invalid tease dropped; sim `/screen`.
- App: CI build; on-device checklist (grant access, glance in Chrome, password field skipped,
  typing not read, do-not-watch app, roaming onto buttons, scroll → fall, keyboard → floor, tease
  sit + tap shoo, call → no teases, switch off).

## 8. Out of scope

Real taps/gestures (roadmap "phone actions"); screenshots/vision (roadmap "vision"); reading other
windows than the active one; per-app reading rules beyond do-not-watch; non-English/Arabic tag words
beyond the list above.
