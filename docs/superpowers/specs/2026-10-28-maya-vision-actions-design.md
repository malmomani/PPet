# Maya — Bigger Brain, Vision and Phone Actions (v1)

Date: 2026-10-28
Status: Approved in chat (vision source "A", model "D" = Qwen3.6-35B-A3B via llama-server, look trigger "A",
actions autonomy "A", actions method "A", design "yes, write the spec and start building").
Builds on: `2026-10-25-maya-screen-design.md` (screen sense, body, moves).

## 1. Goal

1. **Bigger brain** — the brain can use any OpenAI-compatible server (llama.cpp `llama-server` running the
   MoE model Qwen3.6-35B-A3B on the user's RTX 3070 8 GB + 32 GB RAM), keeping Ollama as an option and for
   embeddings.
2. **Vision** — she looks at the screen as a picture when it is mostly images or when the user asks, and
   understands it through a vision-capable model.
3. **Phone actions** — she can do harmless things on her own (open apps, music, timers, scroll…) and press
   buttons / type inside apps with her body; anything that sends, posts, buys, deletes, calls, shares or
   subscribes needs the user's yes.
4. **Bank fix** — she removes her overlay while a do-not-watch app is in front.

### Success criteria
- With `provider = "openai"` pointing at llama-server she chats as before (same prompts, JSON thoughts).
- On Instagram / gallery / YouTube she sometimes says something about the pictures; "look at this" makes her
  look within a few seconds and answer about it. No image is ever stored on the phone or PC.
- "Open YouTube", "set a timer for 10 minutes", "pause the music" work when asked; she sometimes does
  harmless things on her own; "send" always asks first, and no answer = no.
- She never acts or looks in do-not-watch apps, on the lock screen, in password fields or in her own settings.
- Opening the bank app removes her overlay until the user leaves it.

## 2. Decisions (agreed)

| Topic | Decision |
|---|---|
| Model | Qwen3.6-35B-A3B (+ vision projector) on llama-server with `--n-cpu-moe`; Ollama `gemma3:4b` as vision fallback. |
| What she sees | Screenshots of the screen only (no camera). |
| When she looks | Auto on picture-heavy screens (≤ 1 per 5 min) + when asked. |
| Autonomy | Harmless actions on her own; risky ones need a yes in her bubble. |
| How | System actions via Android; in-app presses by her body walking to the button; typing into a field. |
| Default | Actions switch "Let Maya use my phone" **off** until the user turns it on. |

## 3. Brain: LLM providers

- `[llm] provider = "ollama" | "openai"` (default `ollama`, today's behaviour unchanged).
- `openai`: `base_url` (e.g. `http://localhost:8080/v1`), optional `api_key`, `chat_model` (sent as `model`),
  `temperature`, `timeout_s`. Chat → `POST {base_url}/chat/completions` with
  `{"model", "messages", "temperature", "stream": false}` plus `"response_format": {"type": "json_object"}`
  when JSON mode is requested; reply text = `choices[0].message.content` with `<think>…</think>` stripped.
  Warm-up: one tiny chat request.
- Embeddings always go to Ollama: `[llm] embed_base_url` (default `http://localhost:11434`) +
  `embed_model` via `/api/embed` (unchanged code path).
- `[vision] enabled = true`, `model` (default empty = use the chat model on the `openai` provider; with the
  `ollama` provider default `gemma3:4b`), `max_side = 1024`.
- Vision call: one request with the image —
  openai: user content `[{type:"text",text:PROMPT},{type:"image_url",image_url:{url:"data:image/jpeg;base64,…"}}]`;
  ollama: `/api/chat` message with `images: [b64]`.
  PROMPT (exact): `"This is a screenshot of the user's phone. In 1-3 short sentences, describe what the user is looking at (the pictures, not the menus). The small pixel girl is Maya herself; don't describe her."`
  Reply trimmed to ≤ 300 chars. Failure → no description (logged without content).
- README: llama-server setup for Windows (download, `llama-server -hf <repo>:<quant> --jinja -ngl 99
  --n-cpu-moe <N> -c 16384 --port 8080`, tuning N, a speed check), `maya.toml` example, note that the numbers
  are community reports to verify on the user's PC.

## 4. Vision

### 4.1 Phone
- `MayaAccessibilityService` gains `android:canTakeScreenshot="true"` and uses
  `takeScreenshot(Display.DEFAULT_DISPLAY, …)` (API 30+; older phones: no vision).
- **Picture-heavy** (pure core rule): image nodes (from the layout pass: `image` nodes ≥ 48 dp) cover ≥ 40 %
  of the screen area, or ≥ 1 image node ≥ 25 % with ≤ 8 text lines.
- **Auto look**: when the current layout is picture-heavy, at most once per 300 s globally and once per app
  per 600 s, only when glances are allowed (connected, screen usable, switch on, not do-not-watch, not own,
  no keyboard, layout from the app itself).
- **Asked look**: action `look` from the brain → screenshot within 1 s if allowed, else `sight_failed`.
  Asked looks ignore the auto timers but are limited to one per 20 s.
- Image: scaled so the long side ≤ 1024 px, JPEG quality 70, ≤ 400 KB (lower quality until it fits);
  bytes only in memory, never written to disk, never logged.
- Event `sight{type,id,ts,package,app,reason:"auto"|"asked",image_b64}` — not queued offline.
  Event `sight_failed{type,id,ts,reason:"blocked"|"error"}` for asked looks that couldn't happen.

### 4.2 Brain
- `SightEvent`: image ≤ 600 000 base64 chars (else dropped). The brain calls the vision model **outside**
  the Mind lock, then handles the description under the lock; the image bytes are dropped right after.
- Keeps the latest sight (RAM only): app, description, time. Thinker context, when ≤ 10 min old and same
  app as the latest glance/app: `"You saw on screen ({app}): {description}"`.
- Memory turn: `"you looked at {app}: {description}"` (≤ 160 chars).
- `reason == "auto"`: urge `+5` with reason `"you just saw something on screen in {app}"`, ≤ 1 per 5 min.
  `reason == "asked"`: a direct response (like a user message) about what she saw.
- `sight_failed` after an asked look: a direct response that she couldn't see it (e.g. bank app).
- `Thought.look: bool` — when true (typically after "look at this"), Mind sends `look` after the say.
- Sim: `/sight <app> <description>` injects a described sight (no image) for testing.

## 5. Phone actions

### 5.1 Wire
- Action `act{type,id,ts,kind,…}` (brain → phone), kinds and fields:
  - `open_app{app}` (label, matched case-insensitively against launcher apps; best match ≥ 0.8 similarity)
  - `media{key: "play_pause"|"next"|"previous"}`
  - `timer{seconds 5..86400, label?}` · `alarm{hour, minute, label?}`
  - `volume{dir: "up"|"down"|"mute"}`
  - `web_search{query ≤ 200}`
  - `scroll{dir: "up"|"down"}`
  - `press{target}` — a tease tag (`send`, `like`, …) or a visible label (≤ 40 chars)
  - `type{text ≤ 500, into?}` — into the focused editable field, or the editable field whose label/hint
    matches `into`
- Event `act_result{type,id,ts,act_id,status:"done"|"declined"|"timeout"|"failed"|"blocked",detail?}`
  — not queued offline (a result arriving after reconnect is stale).

### 5.2 Risk
- **Risky** (needs a yes): `press` whose tag is `send|share|comment|follow`, or whose label contains
  (whole word, English/Arabic) send, post, publish, buy, pay, order, checkout, purchase, delete, remove,
  call, dial, share, subscribe, follow, confirm, submit, install, uninstall, reply (إرسال, ارسال, نشر, شراء,
  دفع, حذف, اتصال, مشاركة, متابعة, اشتراك, تأكيد); and `type` into a field in a messaging app is allowed but
  never presses send itself.
- **Never**: do-not-watch apps, keyguard, `com.android.systemui`, Maya's own package (incl. her settings),
  Android Settings (`com.android.settings`), password fields, any action while the switch is off.
- **Harmless**: everything else in §5.1.

### 5.3 Phone execution (`ActionRunner`, pure decisions in core, Android calls in app)
- `open_app` → launch intent of the matched package. `media` → `AudioManager.dispatchMediaKeyEvent`.
  `timer`/`alarm` → `AlarmClock.ACTION_SET_TIMER/ACTION_SET_ALARM` with `EXTRA_SKIP_UI`. `volume` →
  `adjustStreamVolume(STREAM_MUSIC, …, FLAG_SHOW_UI)`. `web_search` → `ACTION_WEB_SEARCH`.
  `scroll` → `ACTION_SCROLL_FORWARD/BACKWARD` on the largest scrollable node.
- `press`: find the node (tag via core `tagOf`, else label match); her body goes to it (Behavior command
  `press` = like `poke`: lands on/beside it, plays `poke`), then `ACTION_CLICK` on the node (or its clickable
  ancestor); fallback `dispatchGesture` tap at the node centre (config `canPerformGestures="true"`).
  Not found → `failed("not on screen")`; she gives up after 6 s like a tease.
- `type`: `ACTION_SET_TEXT` on the target editable (never `isPassword`).
- Risky → **confirm bubble** first: "Can I press Send?" / "Can I type that?" with ✔ and ✖ buttons; 15 s → `timeout`;
  ✖ → `declined`. Only one action runs at a time; a new `act` while one waits → `blocked("busy")`.
- Every outcome → `act_result`.

### 5.4 Brain
- `Thought.act: {kind, …} | null` (validated per §5.1; invalid → dropped, rest of thought kept).
- Prompt lists the kinds briefly; says risky ones will be confirmed by the user.
- Own-initiative acts (not in reply to a user message/tap): only when tier ∈ {friend, close}, not quiet
  hours, not shushed, ≤ 10 per rolling hour (persisted), and never risky kinds (`press` on risky targets,
  `type`). Acts in reply to the user: no rate limit (risk rules still apply on the phone).
- `act_result` → memory turn: done `"you {summary}"`, declined `"the user said no to {summary}"`,
  timeout `"the user didn't answer when you asked to {summary}"`, failed/blocked `"you couldn't {summary} ({detail})"`.
  No urge change except declined (+0; she may sulk via normal thinking).
- Sim: `act` actions are printed; `/act_result <status> [detail]` answers the last one.

### 5.5 Settings
- Switch "Let Maya use my phone" (`Prefs.allowActions`, default **false**); needs Screen access for
  `press`/`type`/`scroll`; system kinds work without it. Off → every `act` → `blocked("off")`.

## 6. Bank fix
- While the current app (screen sense or app tracker) is on the do-not-watch list, the overlay window is
  removed (like Hide) and restored when another app comes to the front. No glances, sights or acts there.
- README: why banks block Maya (sideloaded + Accessibility + overlay), and the volume-key accessibility
  shortcut to switch Screen access off/on.

## 7. Privacy
Screenshots exist only in RAM on the phone and in the brain for one vision call; never written to disk or
logged. Only a ≤ 160-char description enters her memory. The OpenAI-compatible provider is meant for a local
server; pointing it at a cloud API sends her prompts (chats, memories, screen text, descriptions) to that
provider — the README says so.

## 8. Testing
- Brain (pytest): openai provider request/response shape, JSON mode, errors → LLMError, think-strip,
  embeddings still via Ollama; vision call shapes for both providers; SightEvent caps; sight handled outside
  the lock; context/memory/urge rules; asked → direct response; sight_failed; `Thought.look` → `look` action;
  `Thought.act` validation; own-initiative limits (tier, quiet, shush, 10/h persisted, no risky); act_result
  memory wording; sims.
- Core (JUnit): picture-heavy rule; look scheduler (auto 300 s / per-app 600 s / asked 20 s / gating);
  action risk classifier (EN/AR words, tags, never-list); open_app label matching; protocol encode
  `sight`/`sight_failed`/`act_result`, decode `act`/`look`; Behavior `press` command.
- App: CI build; on-device checklist (llama-server chat, auto look on Instagram, "look at this", bank app →
  overlay gone, open YouTube, timer, pause music, press Send asks, ✖ declines, timeout, switch off, password
  field refused).

## 9. Out of scope
Camera; reading other displays; actions on other devices; long multi-step tasks ("order me a pizza");
recording or saving screenshots; cloud vision APIs.
