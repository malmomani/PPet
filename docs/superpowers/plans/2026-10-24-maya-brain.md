# Maya Brain (PC) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Maya's brain: a Python server that holds her personality, memory, emotions, and initiative, talks to Ollama, and serves the phone over WebSocket — plus a simulator CLI to chat with her before the phone app exists.

**Architecture:** Package `maya_brain` in `brain/`. Small single-purpose modules (persona, memory, emotion, thinker, initiative, reflection) are composed by `Mind`, which turns protocol events into protocol actions. `gateway` exposes Mind over FastAPI WebSocket; `sim` exposes it in a terminal with a virtual clock. All time flows through an injectable `Clock`; all LLM access through an `LLM` protocol with a `FakeLLM` for tests.

**Tech Stack:** Python ≥3.11, FastAPI, uvicorn, Pydantic v2, httpx, numpy, SQLite (stdlib), pytest + pytest-asyncio.

**Spec:** `docs/superpowers/specs/2026-10-24-maya-design.md` (sections 4, 5, 7, 8 are implemented here; section 6 is Plan 2).

## Global Constraints

- Python `>=3.11` (uses `tomllib`). Runtime deps only: `fastapi`, `uvicorn[standard]`, `pydantic>=2`, `httpx`, `numpy`. Dev: `pytest`, `pytest-asyncio` (`asyncio_mode = "auto"`).
- All code in `brain/maya_brain/`, tests in `brain/tests/`. Run tests from `brain/`: `pytest -q`.
- `Clock.now()` returns a **tz-aware local** datetime. Quiet hours, late night, "today", and diary dates use local time. Protocol `ts` fields are UTC ISO-8601. DB timestamps are `isoformat()` strings.
- Persona core: name `Maya`, core traits `["curious", "playful"]`.
- Moods: `happy, curious, bored, excited, sulky, worried, sleepy`. Baseline `curious` @ 0.3, half-life 2 h.
- Animations: `idle, walk, sit, nap, wave, peek, happy, sulk, surprised`.
- Closeness starts 10, range 0–100; tiers stranger 0–29, friend 30–69, close 70–100; per-thought delta clamped [-3, +3]; per-day net loss ≤ 8; after 2 days without interaction, drift 1 point/day toward 10.
- Initiative: threshold 60, tick 30 s, cooldown 10 min, cap 15/day, quiet 23:30–08:00, late-night window 00:00–05:00 may break quiet hours ≤ 2×/night, shush = 60 min silence.
- Defaults: port `8765`, chat model `qwen3:8b`, embed model `nomic-embed-text`, Ollama `http://localhost:11434`, LLM timeout 30 s, reflection at `04:00` or after 6 h idle, once per local day.
- Unknown protocol `type`s are ignored, never errors.
- No model identifiers other than config defaults in code; no personal data in repo.

## Review Focus

1. **Ollama down or slow while the user is chatting** → user gets the canned line `"…my head's a bit foggy. Try again?"`, `typing off` is always sent, nothing crashes. (Test: Task 10 `test_user_message_llm_down_sends_canned_reply`.)
2. **Messy LLM output** — JSON wrapped in ```` ``` ```` fences or `<think>…</think>`, unknown mood/animation, huge `say`, closeness_delta of 50 → normalized, not dropped. Note: for *direct* user triggers, an unrecoverable bad reply also gets the canned line (refines spec §7 "silent", which would leave a typed message unanswered). (Tests: Task 3 `test_strips_think_and_fences`, Task 7 `test_thought_normalizes_bad_fields`, `test_direct_trigger_bad_json_twice_returns_none`.)
3. **Malformed frames from the phone** (not JSON, missing fields, unknown type) → `error` action for malformed, silence for unknown; socket stays open. (Test: Task 11 `test_bad_frames_do_not_kill_socket`.)
4. **Quiet hours spanning midnight and daily counters rolling over at local midnight.** (Tests: Task 8 `test_quiet_window_wraps_midnight`, `test_daily_cap_resets_next_day`.)
5. **Brain restart** → mood, closeness, urge/cooldown state, traits, facts, buffer all survive reopening the DB file. (Tests: Task 6 `test_emotion_persists_across_reopen`, Task 8 `test_initiative_persists_across_reopen`, Task 4 `test_buffer_persists_across_reopen`.)

---

## File Structure

```
brain/
  pyproject.toml            package + deps + pytest config
  maya.example.toml         documented config
  README.md                 setup: Ollama, models, run server, run sim
  maya_brain/
    __init__.py
    __main__.py             `python -m maya_brain` → server
    config.py               Config dataclasses + load_config
    clock.py                Clock protocol, SystemClock, FakeClock
    protocol.py             Pydantic events/actions, parse_event, MOODS, ANIMATIONS
    llm.py                  LLM protocol, OllamaLLM, FakeLLM, LLMError, extract_json
    db.py                   open_db + schema, get_state/set_state
    memory.py               turns buffer, episodes, facts, recall
    persona.py              core + learned traits, render()
    emotion.py              mood + closeness, tiers
    thinker.py              prompt building, Thought, think()
    initiative.py           urge meter + guardrails + app tracking
    reflection.py           summarize_pending, nightly reflect, diary
    mind.py                 orchestrator: events → actions, tick
    gateway.py              FastAPI app + WebSocket
    sim.py                  simulator REPL
  tests/  test_<module>.py per module
.github/workflows/brain.yml
```

---

### Task 1: Scaffold, config, clock, CI

**Files:**
- Create: `brain/pyproject.toml`, `brain/maya_brain/__init__.py`, `brain/maya_brain/config.py`, `brain/maya_brain/clock.py`, `brain/maya.example.toml`, `.github/workflows/brain.yml`, `.gitignore`
- Test: `brain/tests/test_config.py`, `brain/tests/test_clock.py`, `brain/tests/conftest.py` (shared fixtures `clock` = `FakeClock` at Sat 2026-10-24 14:00 local, `db` = `open_db(":memory:")`, `llm` = `FakeLLM()`; later tasks add their component fixtures here)

**Interfaces:**
- Produces:
  - `@dataclass ServerConfig(host="0.0.0.0", port=8765, token="change-me")`
  - `@dataclass LLMConfig(base_url="http://localhost:11434", chat_model="qwen3:8b", embed_model="nomic-embed-text", timeout_s=30.0, think: bool | None = False, temperature=0.8)`
  - `@dataclass PersonaConfig(name="Maya", core_traits=["curious","playful"], style=<one-paragraph default: short casual messages, emoji sometimes, teasing grows with closeness>)`
  - `@dataclass InitiativeConfig(threshold=60.0, tick_s=30.0, cooldown_min=10, daily_cap=15, quiet_start="23:30", quiet_end="08:00", late_night_max=2, shush_min=60)`
  - `@dataclass ReflectionConfig(time="04:00", idle_hours=6.0)`
  - `@dataclass Config(server, llm, persona, initiative, reflection, db_path="maya.db")`
  - `load_config(path: Path | None) -> Config` — `None` or missing file → defaults; TOML sections override field-by-field; unknown keys ignored.
  - `class Clock(Protocol): def now(self) -> datetime`; `SystemClock` (`datetime.now().astimezone()`); `FakeClock(start: datetime)` with `.set(dt)`, `.advance(td)`.

- [ ] **Step 1: Write failing tests**

```python
def test_defaults_when_no_file(tmp_path):
    cfg = load_config(tmp_path / "missing.toml")
    assert cfg.server.port == 8765 and cfg.llm.chat_model == "qwen3:8b"
    assert cfg.persona.name == "Maya" and cfg.persona.core_traits == ["curious", "playful"]
    assert cfg.initiative.threshold == 60 and cfg.initiative.quiet_start == "23:30"

def test_partial_override_and_unknown_keys(tmp_path):
    p = tmp_path / "m.toml"
    p.write_text('[server]\ntoken="abc"\nbogus=1\n[llm]\nchat_model="llama3.1:8b"\n')
    cfg = load_config(p)
    assert cfg.server.token == "abc" and cfg.server.port == 8765
    assert cfg.llm.chat_model == "llama3.1:8b" and cfg.llm.embed_model == "nomic-embed-text"

def test_example_config_loads():
    cfg = load_config(Path(__file__).parent.parent / "maya.example.toml")
    assert cfg.persona.name == "Maya"

def test_fake_clock_advance():
    start = datetime(2026, 10, 24, 12, 0, tzinfo=timezone.utc)
    c = FakeClock(start); c.advance(timedelta(hours=3))
    assert c.now() == start + timedelta(hours=3)

def test_system_clock_is_aware():
    assert SystemClock().now().tzinfo is not None
```

- [ ] **Step 2: Run** `cd brain && pip install -e .[dev] && pytest -q` → FAIL (modules missing).
- [ ] **Step 3: Implement** pyproject (setuptools, `[project.optional-dependencies] dev`, `[tool.pytest.ini_options] asyncio_mode="auto"`, `testpaths=["tests"]`), config, clock, example TOML (every key with a comment), `.gitignore` (`*.db`, `__pycache__/`, `.venv/`, `maya.toml`, `*.egg-info/`), CI workflow (ubuntu, Python 3.11 + 3.12 matrix, `pip install -e brain[dev]`, `pytest -q` in `brain/`).
- [ ] **Step 4: Run** `pytest -q` → PASS.
- [ ] **Step 5: Commit** `feat(brain): scaffold package with config, clock, CI`

---

### Task 2: Protocol models

**Files:** Create `brain/maya_brain/protocol.py`; Test `brain/tests/test_protocol.py`

**Interfaces:**
- Produces:
  - `MOODS: tuple[str, ...]`, `ANIMATIONS: tuple[str, ...]` (Global Constraints values).
  - Event models (Pydantic, each with `type: Literal[...]`, `id: str` default uuid4 hex, `ts: datetime` default UTC now): `Hello(client_version: str, screen_w: int, screen_h: int)`, `UserMessage(text: str 1..2000 chars)`, `UserTap()`, `UserDrag(thrown: bool = False)`, `UserShush()`, `AppOpened(package: str, label: str)`, `AppClosed(package, label, duration_s: int >= 0)`, `Screen(on: bool)`, `Charging(on: bool)`, `OfflineSummary(events: list[dict], since: datetime)`. Type strings exactly as spec §4.
  - `Event` = union of the above.
  - `class ProtocolError(ValueError)`
  - `parse_event(raw: str | dict) -> Event | None` — unknown/missing `type` → `None`; invalid JSON or invalid fields for a known type → `ProtocolError`.
  - Action models: `Say(text: str, bubble_ms: int = 6000)`, `Animate(name: Literal[*ANIMATIONS])`, `State(mood: str, closeness_tier: str)`, `Typing(on: bool)`, `Error(code: str, message: str)`; type strings `say, animate, state, typing, error`; same `id`/`ts` defaults. `Action` = union. Serialize with `.model_dump_json()`.

- [ ] **Step 1: Write failing tests**

```python
def test_parse_user_message():
    e = parse_event('{"type":"user_message","text":"hi"}')
    assert isinstance(e, UserMessage) and e.text == "hi" and e.id and e.ts.tzinfo

def test_unknown_type_ignored():
    assert parse_event({"type": "teleport"}) is None
    assert parse_event({"no": "type"}) is None

def test_bad_json_and_bad_fields_raise():
    with pytest.raises(ProtocolError): parse_event("{not json")
    with pytest.raises(ProtocolError): parse_event({"type": "app_closed", "package": "x"})
    with pytest.raises(ProtocolError): parse_event({"type": "user_message", "text": ""})

def test_all_event_types_parse():
    for raw in [{"type":"hello","client_version":"1","screen_w":1,"screen_h":2},
                {"type":"user_tap"},{"type":"user_drag","thrown":True},{"type":"user_shush"},
                {"type":"app_opened","package":"p","label":"L"},
                {"type":"app_closed","package":"p","label":"L","duration_s":5},
                {"type":"screen","on":False},{"type":"charging","on":True},
                {"type":"offline_summary","events":[],"since":"2026-10-24T00:00:00Z"}]:
        assert parse_event(raw) is not None

def test_action_serialization():
    d = json.loads(Say(text="yo").model_dump_json())
    assert d["type"] == "say" and d["text"] == "yo" and d["bubble_ms"] == 6000 and "id" in d
    with pytest.raises(ValidationError): Animate(name="moonwalk")
```

- [ ] **Step 2: Run** `pytest tests/test_protocol.py -q` → FAIL.
- [ ] **Step 3: Implement** with a Pydantic discriminated union on `type` via `TypeAdapter`; check `type` against the known set before validating so unknown → `None`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(brain): protocol events and actions`

---

### Task 3: LLM adapter

**Files:** Create `brain/maya_brain/llm.py`; Test `brain/tests/test_llm.py`

**Interfaces:**
- Consumes: `LLMConfig`.
- Produces:
  - `class LLMError(Exception)`
  - `class LLM(Protocol)`: `async def chat(self, messages: list[dict], json_mode: bool = False) -> str`; `async def embed(self, text: str) -> list[float]`
  - `class OllamaLLM(cfg: LLMConfig, transport: httpx.AsyncBaseTransport | None = None)` — `chat` POSTs `/api/chat` with `{"model", "messages", "stream": False, "options": {"temperature"}}`, plus `"format": "json"` when `json_mode`, plus `"think"` only when `cfg.think is not None`; returns `message.content` with `<think>…</think>` blocks removed. `embed` POSTs `/api/embed` `{"model": embed_model, "input": text}` → `embeddings[0]`. Any httpx error, timeout, non-2xx, or bad shape → `LLMError`.
  - `extract_json(text: str) -> dict` — strips `<think>` blocks and ```` ``` ```` fences, parses the outermost `{…}`; raises `ValueError` if none.
  - `class FakeLLM(replies: list[str] | Callable[[list[dict]], str] | None = None)` — `.calls: list[list[dict]]`; `.fail_next: int` (each chat/embed decrements and raises `LLMError` while > 0); with no replies returns `'{"thought":"","speak":true,"say":"hehe","animation":"happy","mood":"happy","mood_intensity":0.6,"closeness_delta":1}'`; list replies are consumed in order, last one repeats. `embed` returns a deterministic 64-dim L2-normalized bag-of-words hashing vector (lowercase word tokens, `hash` via `zlib.crc32`) so texts sharing words are similar.

- [ ] **Step 1: Write failing tests** (Ollama via `httpx.MockTransport`)

```python
async def test_chat_sends_json_format_and_strips_think():
    seen = {}
    def handler(req):
        seen.update(json.loads(req.content))
        return httpx.Response(200, json={"message": {"content": "<think>hmm</think>{\"a\":1}"}})
    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(handler))
    out = await llm.chat([{"role": "user", "content": "x"}], json_mode=True)
    assert out.strip() == '{"a":1}'
    assert seen["format"] == "json" and seen["stream"] is False and seen["model"] == "qwen3:8b" and seen["think"] is False

async def test_embed():
    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"embeddings": [[0.1, 0.2]]})))
    assert await llm.embed("x") == [0.1, 0.2]

async def test_errors_become_llmerror():
    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(lambda r: httpx.Response(500)))
    with pytest.raises(LLMError): await llm.chat([])
    def boom(r): raise httpx.ConnectError("down")
    llm = OllamaLLM(LLMConfig(), transport=httpx.MockTransport(boom))
    with pytest.raises(LLMError): await llm.embed("x")

def test_strips_think_and_fences():
    assert extract_json('<think>x</think>```json\n{"a": {"b": 1}}\n```') == {"a": {"b": 1}}
    with pytest.raises(ValueError): extract_json("no json here")

async def test_fake_llm_replies_fail_and_embed_similarity():
    f = FakeLLM(["one", "two"])
    assert [await f.chat([]), await f.chat([]), await f.chat([])] == ["one", "two", "two"]
    f.fail_next = 1
    with pytest.raises(LLMError): await f.chat([])
    a, b, c = [np.array(await f.embed(t)) for t in ["ramen is great", "I love ramen", "tax forms"]]
    assert a @ b > a @ c
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(brain): Ollama LLM adapter and FakeLLM`

---

### Task 4: Database and memory

**Files:** Create `brain/maya_brain/db.py`, `brain/maya_brain/memory.py`; Test `brain/tests/test_memory.py`

**Interfaces:**
- Consumes: `LLM`, `Clock`.
- Produces:
  - `open_db(path: str | Path) -> sqlite3.Connection` (`":memory:"` allowed; `row_factory = sqlite3.Row`; creates tables if missing):
    `turns(id INTEGER PK, role TEXT, content TEXT, ts TEXT, summarized INTEGER DEFAULT 0)`,
    `episodes(id INTEGER PK, summary TEXT, embedding BLOB, importance INTEGER, created_at TEXT)`,
    `facts(key TEXT PK, value TEXT, updated_at TEXT)`,
    `traits(id INTEGER PK, kind TEXT, text TEXT, confidence REAL, updated_at TEXT, UNIQUE(kind, text))`,
    `thoughts(id INTEGER PK, ts TEXT, trigger TEXT, json TEXT)`,
    `diary(date TEXT PK, entry TEXT, created_at TEXT)`,
    `state(key TEXT PK, value TEXT)`.
  - `get_state(db, key: str) -> dict | None`, `set_state(db, key: str, value: dict) -> None` (JSON in `state`).
  - `@dataclass Turn(role: str, content: str, ts: datetime)` — role ∈ `user | maya | event`.
  - `@dataclass Episode(id: int, summary: str, importance: int, created_at: datetime, score: float = 0.0)`
  - `class Memory(db, llm: LLM, clock: Clock, buffer_size: int = 20)`:
    `add_turn(role, content) -> None`; `recent_turns() -> list[Turn]` (last `buffer_size`, oldest first); `unsummarized_turns() -> list[Turn]` (all, oldest first); `mark_summarized() -> None`; `last_turn_time(roles=("user","maya")) -> datetime | None`;
    `async add_episode(summary: str, importance: int) -> int` (importance clamped 1..5; embedding stored as float32 bytes);
    `async recall(query: str, k: int = 5) -> list[Episode]` — score = `cos_sim × 0.5 ** (age_days / 30) × (0.5 + importance / 10)`, top k by score; returns `[]` when no episodes or when `embed` raises `LLMError`;
    `episodes_since(dt: datetime) -> list[Episode]`;
    `set_fact(key, value)`, `delete_fact(key)`, `facts(limit: int = 40) -> dict[str, str]` (most recently updated first).

- [ ] **Step 1: Write failing tests**

```python
@pytest.fixture
def mem():
    clock = FakeClock(datetime(2026, 10, 24, 12, tzinfo=timezone.utc))
    return Memory(open_db(":memory:"), FakeLLM(), clock), clock

def test_buffer_keeps_last_20_in_order(mem):
    m, _ = mem
    for i in range(25): m.add_turn("user", f"msg {i}")
    t = m.recent_turns()
    assert len(t) == 20 and t[0].content == "msg 5" and t[-1].content == "msg 24"

def test_unsummarized_and_mark(mem):
    m, _ = mem
    m.add_turn("user", "a"); m.add_turn("maya", "b")
    assert [t.content for t in m.unsummarized_turns()] == ["a", "b"]
    m.mark_summarized(); assert m.unsummarized_turns() == []
    assert len(m.recent_turns()) == 2

def test_buffer_persists_across_reopen(tmp_path):
    clock = FakeClock(datetime(2026, 10, 24, tzinfo=timezone.utc))
    Memory(open_db(tmp_path / "m.db"), FakeLLM(), clock).add_turn("user", "remember me")
    assert Memory(open_db(tmp_path / "m.db"), FakeLLM(), clock).recent_turns()[0].content == "remember me"

async def test_recall_prefers_similar_then_recent(mem):
    m, clock = mem
    await m.add_episode("We talked about ramen and noodles", 3)
    await m.add_episode("User filed tax forms", 3)
    got = await m.recall("favorite ramen place", k=1)
    assert got[0].summary.startswith("We talked about ramen")

async def test_recall_empty_and_embed_failure(mem):
    m, _ = mem
    assert await m.recall("anything") == []
    await m.add_episode("x", 9)  # clamped
    m.llm.fail_next = 1
    assert await m.recall("x") == []

def test_facts_upsert_order_limit(mem):
    m, clock = mem
    m.set_fact("name", "Sam"); clock.advance(timedelta(seconds=1)); m.set_fact("food", "ramen")
    clock.advance(timedelta(seconds=1)); m.set_fact("name", "Sammy")
    assert list(m.facts().items()) == [("name", "Sammy"), ("food", "ramen")]
    m.delete_fact("food"); assert m.facts() == {"name": "Sammy"}
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(brain): sqlite schema and memory (buffer, episodes, facts)`

---

### Task 5: Persona

**Files:** Create `brain/maya_brain/persona.py`; Test `brain/tests/test_persona.py`

**Interfaces:**
- Consumes: `PersonaConfig`, `open_db`, `Clock`.
- Produces:
  - `TRAIT_KINDS = ("like", "dislike", "opinion", "joke", "nickname", "habit")`
  - `@dataclass Trait(kind: str, text: str, confidence: float)`
  - `@dataclass TraitUpdate(kind: str, text: str, action: Literal["add", "reinforce", "weaken"])`
  - `class Persona(db, cfg: PersonaConfig, clock: Clock)`:
    `traits() -> list[Trait]` (confidence desc);
    `apply_updates(updates: list[TraitUpdate]) -> None` — ignore unknown kinds/actions and empty text (text stripped, ≤ 120 chars); `add` → new at 0.4 or, if it exists, treat as reinforce; `reinforce` → +0.2 (new at 0.4) capped 1.0; `weaken` → −0.3; every trait not touched by this call decays −0.02; then delete confidence < 0.15;
    `render() -> str` — contains the name, the core traits labelled as never-changing, the style text, and up to 25 learned traits grouped by kind (omit section when none).

- [ ] **Step 1: Write failing tests**

```python
def test_render_core_only(persona):
    r = persona.render()
    assert "Maya" in r and "curious" in r and "playful" in r and "like:" not in r.lower()

def test_add_reinforce_weaken_prune(persona):
    persona.apply_updates([TraitUpdate("like", "ramen", "add")])
    assert persona.traits()[0].confidence == pytest.approx(0.4)
    persona.apply_updates([TraitUpdate("like", "ramen", "reinforce")])
    assert persona.traits()[0].confidence == pytest.approx(0.6)
    persona.apply_updates([TraitUpdate("like", "ramen", "weaken")] * 2)
    assert persona.traits() == []          # 0.6 -0.3 -0.3 → pruned

def test_untouched_decay_and_ignores_garbage(persona):
    persona.apply_updates([TraitUpdate("joke", "the potato incident", "add")])
    persona.apply_updates([TraitUpdate("superpower", "x", "add"), TraitUpdate("like", "  ", "add")])
    assert [(t.kind, round(t.confidence, 2)) for t in persona.traits()] == [("joke", 0.38)]

def test_render_includes_traits(persona):
    persona.apply_updates([TraitUpdate("nickname", "calls the user 'boss'", "add")])
    assert "boss" in persona.render()
```

- [ ] **Step 2–4:** Run → FAIL, implement, run → PASS.
- [ ] **Step 5: Commit** `feat(brain): persona with learned traits`

---

### Task 6: Emotion

**Files:** Create `brain/maya_brain/emotion.py`; Test `brain/tests/test_emotion.py`

**Interfaces:**
- Consumes: `MOODS`, `get_state/set_state`, `Clock`.
- Produces:
  - `closeness_tier(closeness: float) -> str` → `"stranger" | "friend" | "close"`.
  - `TIER_GUIDANCE: dict[str, str]` — one or two sentences each: stranger = polite, curious, rarely starts conversations; friend = chatty, jokes; close = teases, checks on the user, remembers small things, can get a little sulky if ignored.
  - `class Emotion(db, clock: Clock)` (state key `"emotion"`, saved on every mutation):
    `mood() -> tuple[str, float]` — lazily decayed: non-baseline intensity × `0.5 ** (hours_since_set / 2)`, reverts to `("curious", 0.3)` when below 0.15; baseline mood intensity moves toward 0.3 by the same factor;
    `set_mood(mood: str, intensity: float) -> None` (unknown mood ignored; intensity clamped 0..1);
    `closeness -> float` property; `tier() -> str`;
    `apply_closeness(delta: float) -> float` (returns applied delta; clamp to [-3, 3]; losses limited so the local day's total loss ≤ 8; result clamped 0..100);
    `note_interaction() -> None`;
    `daily_drift() -> None` — idempotent per local date; if ≥ 2 days since last interaction, move closeness 1 point toward 10.

- [ ] **Step 1: Write failing tests**

```python
def test_defaults(emo): assert emo.mood() == ("curious", 0.3) and emo.closeness == 10 and emo.tier() == "stranger"

def test_tiers(): assert [closeness_tier(x) for x in (0, 29.9, 30, 69.9, 70, 100)] == ["stranger"]*2 + ["friend"]*2 + ["close"]*2

def test_mood_decays_to_baseline(emo, clock):
    emo.set_mood("excited", 0.8); clock.advance(timedelta(hours=2))
    assert emo.mood() == ("excited", pytest.approx(0.4))
    clock.advance(timedelta(hours=4))
    assert emo.mood() == ("curious", 0.3)

def test_unknown_mood_ignored(emo):
    emo.set_mood("hangry", 1.0); assert emo.mood()[0] == "curious"

def test_closeness_clamp_and_daily_loss_cap(emo, clock):
    assert emo.apply_closeness(50) == 3
    for _ in range(5): emo.apply_closeness(-3)
    assert emo.closeness == pytest.approx(13 - 8)
    clock.advance(timedelta(days=1)); emo.apply_closeness(-3)
    assert emo.closeness == pytest.approx(2)

def test_daily_drift_toward_10(emo, clock):
    for _ in range(10): emo.apply_closeness(3); clock.advance(timedelta(minutes=1))
    emo.note_interaction(); clock.advance(timedelta(days=3))
    emo.daily_drift(); emo.daily_drift()
    assert emo.closeness == pytest.approx(39)

def test_emotion_persists_across_reopen(tmp_path, clock):
    e = Emotion(open_db(tmp_path / "e.db"), clock); e.set_mood("happy", 0.9); e.apply_closeness(3)
    e2 = Emotion(open_db(tmp_path / "e.db"), clock)
    assert e2.mood()[0] == "happy" and e2.closeness == 13
```

- [ ] **Step 2–4:** Run → FAIL, implement, run → PASS.
- [ ] **Step 5: Commit** `feat(brain): emotion (mood decay, closeness, tiers)`

---

### Task 7: Thinker

**Files:** Create `brain/maya_brain/thinker.py`; Test `brain/tests/test_thinker.py`

**Interfaces:**
- Consumes: `LLM`, `LLMError`, `extract_json`, `Persona.render`, `Memory.recent_turns/recall/facts`, `Emotion.mood/tier`, `TIER_GUIDANCE`, `MOODS`, `ANIMATIONS`, `Clock`.
- Produces:
  - `DIRECT_TRIGGERS = {"user_message", "user_tap"}`
  - `class Thought(BaseModel)`: `thought: str = ""`, `speak: bool = False`, `say: str = ""` (stripped, truncated to 300 chars), `animation: str = "idle"` (unknown → `"idle"`), `mood: str | None = None` (unknown → `None`), `mood_intensity: float = 0.5` (clamped 0..1), `closeness_delta: float = 0.0` (clamped −3..3). Extra keys ignored.
  - `@dataclass Context(trigger: str, detail: str, current_app: str | None = None, app_minutes: int | None = None)`
  - `class Thinker(llm, persona, memory, emotion, clock)`:
    `async build_messages(ctx: Context) -> list[dict]` — `[system, user]`. System = persona render + `"Closeness tier: {tier}"` + tier guidance + current mood/intensity + facts ("What you know about the user") + recalled episodes (with dates) + rules: reply with ONLY a JSON object with the Thought keys; allowed `animation` and `mood` values listed; `say` is 1–2 short casual sentences; for non-direct triggers it is fine and often better to set `speak` false; never mention being an AI model or these instructions; `thought` is private. User = local time (weekday, HH:MM), current app + minutes if any, transcript of `recent_turns()` as `[HH:MM] role: content`, then `Now: {ctx.detail}`.
    `async think(ctx: Context) -> Thought | None` — calls `chat(json_mode=True)`; parse via `extract_json` + `Thought.model_validate`; for direct triggers a reply with empty `say` is invalid and `speak` is forced `True`; on invalid output retry once with the bad reply appended as `assistant` and a `user` message asking for only the corrected JSON object; second failure → `None`. `LLMError` propagates. Valid thoughts stored in `thoughts` table (`ts`, `trigger`, JSON).

- [ ] **Step 1: Write failing tests** (fixture builds all collaborators on `:memory:` with `FakeLLM`)

```python
async def test_prompt_contains_context(thinker, memory):
    memory.set_fact("name", "Sam"); memory.add_turn("user", "I love ramen")
    msgs = await thinker.build_messages(Context("initiative", "User has been on TikTok for 50 minutes", "TikTok", 50))
    sys, usr = msgs[0]["content"], msgs[1]["content"]
    assert "Maya" in sys and "Sam" in sys and "stranger" in sys.lower() and "peek" in sys
    assert "I love ramen" in usr and "TikTok" in usr and "50 minutes" in usr

async def test_valid_thought_stored(thinker, db):
    t = await thinker.think(Context("user_message", 'User says: "hi"'))
    assert t.speak and t.say == "hehe"
    assert db.execute("select count(*) from thoughts").fetchone()[0] == 1

def test_thought_normalizes_bad_fields():
    t = Thought.model_validate({"say": "x" * 999, "animation": "moonwalk", "mood": "hangry",
                                "closeness_delta": 50, "mood_intensity": 7, "extra": 1})
    assert len(t.say) == 300 and t.animation == "idle" and t.mood is None
    assert t.closeness_delta == 3 and t.mood_intensity == 1

async def test_retry_once_then_success(thinker_with):
    th, llm = thinker_with(["garbage", '{"speak": false}'])
    t = await th.think(Context("initiative", "bored"))
    assert t is not None and t.speak is False and len(llm.calls) == 2
    assert llm.calls[1][-2]["role"] == "assistant" and llm.calls[1][-2]["content"] == "garbage"

async def test_direct_trigger_bad_json_twice_returns_none(thinker_with):
    th, _ = thinker_with(['{"speak": true, "say": ""}', "nope"])
    assert await th.think(Context("user_message", 'User says: "hi"')) is None

async def test_direct_trigger_forces_speak(thinker_with):
    th, _ = thinker_with(['{"speak": false, "say": "hm?"}'])
    assert (await th.think(Context("user_tap", "User tapped you."))).speak is True

async def test_llm_error_propagates(thinker_with):
    th, llm = thinker_with(None); llm.fail_next = 5
    with pytest.raises(LLMError): await th.think(Context("user_message", "x"))
```

- [ ] **Step 2–4:** Run → FAIL, implement, run → PASS.
- [ ] **Step 5: Commit** `feat(brain): thinker with inner-thought JSON and retry`

---

### Task 8: Initiative

**Files:** Create `brain/maya_brain/initiative.py`; Test `brain/tests/test_initiative.py`

**Interfaces:**
- Consumes: `InitiativeConfig`, event models, `get_state/set_state`, `Clock`.
- Produces:
  - `TIER_MULT = {"stranger": 0.5, "friend": 1.0, "close": 1.5}`; `MOOD_MULT = {"bored": 2.0, "excited": 1.5, "sleepy": 0.3}` (others 1.0).
  - `in_window(t: time, start: time, end: time) -> bool` (wraps midnight when start > end).
  - `class Initiative(cfg: InitiativeConfig, db, clock: Clock)` (state key `"initiative"`, saved on every mutation):
    `urge: float` property; `last_reason: str` property;
    `current_app() -> tuple[str, int] | None` (label, whole minutes);
    `on_event(event) -> None` — `AppOpened`: set current app + session start, +5 ("User switched to {label}"); `AppClosed` for current app or `Screen(on=False)`: clear current app; `Charging(on=True)`: +3; any `AppOpened`/`Screen(on=True)`/`UserMessage`/`UserTap` after > 3 h since last activity: +30 ("User came back after N hours"); `AppOpened`/`Screen(on=True)` with local time in 00:00–05:00: +10 at most once per clock hour ("It's HH:MM at night and the user is still on their phone"), and marks a late-night trigger as pending; every event updates last activity;
    `tick(tier: str, mood: str) -> None` — `+1 × TIER_MULT × MOOD_MULT`; if current app session > 45 min and not yet bumped this session: +15 ("User has been on {label} for N minutes");
    `shush() -> None` — urge 0, silent until now + `shush_min`;
    `should_fire() -> bool` — `urge >= threshold` and not silenced and ≥ `cooldown_min` since last unprompted remark and remarks today (local date) < `daily_cap` and (not in quiet hours, or a late-night trigger is pending and late-night fires tonight < `late_night_max`);
    `fired(spoke: bool) -> None` — urge 0, clear pending late-night; if `spoke`: record last remark time, increment today's count, and increment tonight's late-night count when inside quiet hours. "Tonight" is keyed by `(now - timedelta(hours=12)).date()`.
    Return-after-3h only applies when a previous activity exists.

- [ ] **Step 1: Write failing tests** (`clock` starts Sat 2026-10-24 14:00 local; config defaults)

```python
def test_quiet_window_wraps_midnight():
    s, e = time(23, 30), time(8, 0)
    assert in_window(time(23, 45), s, e) and in_window(time(3, 0), s, e) and not in_window(time(12, 0), s, e)
    assert in_window(time(10, 0), time(9, 0), time(17, 0))

def test_tick_multipliers(ini):
    ini.tick("close", "bored"); assert ini.urge == pytest.approx(3.0)
    ini.tick("stranger", "sleepy"); assert ini.urge == pytest.approx(3.15)

def test_long_session_bump_once(ini, clock):
    ini.on_event(AppOpened(package="t", label="TikTok"))      # +5
    clock.advance(timedelta(minutes=46)); ini.tick("friend", "curious"); ini.tick("friend", "curious")
    assert ini.urge == pytest.approx(5 + 1 + 15 + 1) and "TikTok" in ini.last_reason
    assert ini.current_app() == ("TikTok", 46)

def test_return_after_3h(ini, clock):
    ini.on_event(Screen(on=True)); clock.advance(timedelta(hours=4)); ini.on_event(Screen(on=True))
    assert ini.urge == pytest.approx(30) and "came back" in ini.last_reason

def test_guardrails_cooldown_and_shush(ini, clock):
    ini._set_urge(60); assert ini.should_fire()
    ini.fired(spoke=True); ini._set_urge(60); assert not ini.should_fire()
    clock.advance(timedelta(minutes=10)); assert ini.should_fire()
    ini.shush(); ini._set_urge(60); assert not ini.should_fire()
    clock.advance(timedelta(minutes=60)); assert ini.should_fire()

def test_daily_cap_resets_next_day(ini, clock):
    for _ in range(15): ini._set_urge(60); assert ini.should_fire(); ini.fired(True); clock.advance(timedelta(minutes=10))
    ini._set_urge(60); assert not ini.should_fire()
    clock.set(clock.now().replace(hour=9) + timedelta(days=1)); ini._set_urge(60); assert ini.should_fire()

def test_quiet_hours_except_late_night_twice(ini, clock):
    clock.set(clock.now().replace(hour=23, minute=45)); ini._set_urge(60); assert not ini.should_fire()
    night = clock.now() + timedelta(days=1)
    for hour, expected in [(1, True), (2, True), (3, False)]:
        clock.set(night.replace(hour=hour, minute=0))
        ini.on_event(AppOpened(package="y", label="YouTube")); ini._set_urge(60)
        assert ini.should_fire() is expected
        if expected: ini.fired(True)

def test_initiative_persists_across_reopen(tmp_path, clock):
    a = Initiative(InitiativeConfig(), open_db(tmp_path / "i.db"), clock); a.tick("close", "bored")
    assert Initiative(InitiativeConfig(), open_db(tmp_path / "i.db"), clock).urge == pytest.approx(3.0)
```

(`_set_urge(v)` is a test helper method on `Initiative` that sets urge and saves.)

- [ ] **Step 2–4:** Run → FAIL, implement, run → PASS.
- [ ] **Step 5: Commit** `feat(brain): initiative urge meter with guardrails`

---

### Task 9: Reflection

**Files:** Create `brain/maya_brain/reflection.py`; Test `brain/tests/test_reflection.py`

**Interfaces:**
- Consumes: `LLM`, `extract_json`, `Memory`, `Persona`, `TraitUpdate`, `get_state/set_state`, `ReflectionConfig`, `Clock`.
- Produces:
  - `class Reflection(llm, memory, persona, db, clock, cfg: ReflectionConfig)` (state key `"reflection"` → `{"last_date": "YYYY-MM-DD", "last_at": iso}`):
    `async summarize_pending() -> int | None` — if no unsummarized turns → `None`; else one JSON call (`{"summary": str, "importance": 1-5}`, Maya's perspective, third person about "the user") → `memory.add_episode` → `mark_summarized` → episode id. Any `LLMError`/bad JSON → `None`, turns stay unsummarized.
    `due(last_event: datetime | None) -> bool` — false if already reflected this local date; true if local time ≥ `cfg.time`, or `last_event` older than `idle_hours`.
    `async reflect() -> bool` — `summarize_pending()`; then three independent JSON calls fed with episodes since last reflection + current facts/traits: facts `{"facts": [{"key","value"}], "remove": [key]}`, traits `{"traits": [{"kind","text","action"}]}` → `persona.apply_updates`, diary `{"entry": str}` (first person, Maya's voice) → upsert `diary` for today's local date. A failing step is skipped; the others still run. Records last date. Returns `True` if the diary entry was written.
    `diary(limit: int = 7) -> list[tuple[str, str]]` (date, entry), newest first.

- [ ] **Step 1: Write failing tests**

```python
async def test_summarize_pending(refl, memory, llm):
    llm.replies = ['{"summary": "The user said they love ramen.", "importance": 4}']
    memory.add_turn("user", "I love ramen")
    eid = await refl.summarize_pending()
    assert eid and memory.unsummarized_turns() == []
    assert (await memory.recall("ramen"))[0].importance == 4
    assert await refl.summarize_pending() is None

async def test_summarize_failure_keeps_turns(refl, memory, llm):
    memory.add_turn("user", "x"); llm.fail_next = 1
    assert await refl.summarize_pending() is None and len(memory.unsummarized_turns()) == 1

async def test_reflect_full(refl, memory, persona, llm):
    llm.replies = ['{"facts":[{"key":"food","value":"ramen"}],"remove":[]}',
                   '{"traits":[{"kind":"like","text":"talking about food","action":"add"}]}',
                   '{"entry":"Today we talked about ramen!"}']
    assert await refl.reflect() is True
    assert memory.facts() == {"food": "ramen"} and persona.traits()[0].text == "talking about food"
    assert refl.diary()[0][1] == "Today we talked about ramen!"

async def test_reflect_step_failure_isolated(refl, llm):
    llm.replies = ["bad", '{"traits":[]}', '{"entry":"ok"}']
    assert await refl.reflect() is True

def test_due(refl, clock):          # clock at 14:00 local
    assert refl.due(None) is True
    clock.set(clock.now().replace(hour=2)); assert refl.due(clock.now()) is False
    assert refl.due(clock.now() - timedelta(hours=7)) is True

async def test_once_per_day(refl, clock, llm):
    llm.replies = ['{"facts":[],"remove":[]}', '{"traits":[]}', '{"entry":"a"}']
    await refl.reflect(); assert refl.due(None) is False
    clock.advance(timedelta(days=1)); assert refl.due(None) is True
```

- [ ] **Step 2–4:** Run → FAIL, implement, run → PASS.
- [ ] **Step 5: Commit** `feat(brain): reflection (episodes, facts, traits, diary)`

---

### Task 10: Mind orchestrator

**Files:** Create `brain/maya_brain/mind.py`; Test `brain/tests/test_mind.py`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `ActionSink = Callable[[Action], Awaitable[None]]`
  - `CANNED_FOGGY = "…my head's a bit foggy. Try again?"`
  - `bubble_ms(text: str) -> int` = `min(15000, max(4000, 2500 + 60 * len(text)))`
  - `class Mind(cfg: Config, llm: LLM, db, clock: Clock, sink: ActionSink | None = None)` with public attributes `memory, persona, emotion, thinker, initiative, reflection`:
    `set_sink(sink) -> None`;
    `async handle(event) -> None` (serialized by an `asyncio.Lock`);
    `async tick() -> None`; `async run_ticker(stop: asyncio.Event) -> None` (calls `tick` every `cfg.initiative.tick_s` until `stop`);
    `status() -> dict` with keys `mood, intensity, closeness, tier, urge, current_app`.
- Behavior (`handle`): every event → `initiative.on_event`.
  - `Hello` → `State`.
  - `UserMessage` → turn `("user", text)`, `note_interaction`, `Typing(on)`, think(`"user_message"`, `User says: "{text}"`), `Typing(off)` always (even on error).
  - `UserTap` → `note_interaction`; if a tap was thought about < 30 s ago → `Animate("happy")` only; else think(`"user_tap"`, `"User tapped you."`) with typing on/off.
  - `UserDrag` → turn `("event", "User dragged you around")` / `"User picked you up and threw you!"`; `Animate("surprised")`; if thrown → think(`"event"`) (speaking optional).
  - `UserShush` → `initiative.shush()`, turn `("event", "User asked you to be quiet for a while")`, `Animate("sit")`.
  - `AppClosed` with `duration_s >= 120` → turn `("event", "User used {label} for N min")`.
  - `OfflineSummary` → aggregate inner `app_closed` durations per label (parse with `parse_event`, skip invalid) → one turn `("event", "While you were disconnected the user used: YouTube 2h 10m, Instagram 30m")`.
  - Non-direct events then call `_maybe_initiate()`.
  - Emit order for a thought: `Typing(on)` → think → `Typing(off)` → apply.
  - Applying a thought: `set_mood` if `mood`, `apply_closeness`, then emit `Animate(animation)` if not `"idle"`, `Say(say, bubble_ms)` if `speak and say` (turn `("maya", say)`), and `State` if mood or tier changed.
  - Direct trigger with `LLMError` or `None` thought → `Say(CANNED_FOGGY)`; non-direct failures → nothing.
- Behavior (`tick`): `emotion.daily_drift()`; `initiative.tick(tier, mood)`; if unsummarized turns and last user/maya turn ≥ 10 min ago → `reflection.summarize_pending()`; if `reflection.due(last_event)` → `reflection.reflect()`; `_maybe_initiate()`.
- `_maybe_initiate()`: if `initiative.should_fire()` → think(`Context("initiative", initiative.last_reason or "You feel like saying something.", *current_app)`), `initiative.fired(spoke)`, apply.

- [ ] **Step 1: Write failing tests** (`sent` list sink; `FakeLLM`; `FakeClock` at Sat 2026-10-24 14:00 local; the `mind` fixture marks today's reflection as done via `set_state(db, "reflection", {"last_date": today})` so reflection doesn't consume replies)

```python
async def test_user_message_flow(mind, sent):
    await mind.handle(UserMessage(text="hi"))
    assert [a.type for a in sent] == ["typing", "typing", "animate", "say", "state"]
    assert sent[0].on is True and sent[1].on is False
    assert [t.content for t in mind.memory.recent_turns()] == ["hi", "hehe"]
    assert mind.emotion.closeness == 11

async def test_user_message_llm_down_sends_canned_reply(mind, sent):
    mind.thinker.llm.fail_next = 10
    await mind.handle(UserMessage(text="hi"))
    assert any(a.type == "say" and a.text == CANNED_FOGGY for a in sent)
    assert sent[-1].type == "typing" and sent[-1].on is False

async def test_tap_rate_limit(mind, sent):
    await mind.handle(UserTap()); n = len(mind.thinker.llm.calls)
    await mind.handle(UserTap())
    assert len(mind.thinker.llm.calls) == n and sent[-1].type == "animate" and sent[-1].name == "happy"

async def test_initiative_fires_from_tick(mind, sent):
    mind.initiative._set_urge(59.9)
    await mind.handle(AppOpened(package="t", label="TikTok"))
    assert any(a.type == "say" for a in sent) and mind.initiative.urge == 0

async def test_initiative_can_choose_silence(mind, sent):
    mind.thinker.llm.replies = ['{"speak": false, "animation": "peek"}']
    mind.initiative._set_urge(60); await mind.tick()
    assert [a.type for a in sent] == ["animate"]

async def test_shush_blocks_initiative(mind, sent):
    await mind.handle(UserShush()); mind.initiative._set_urge(100); await mind.tick()
    assert not any(a.type == "say" for a in sent)

async def test_idle_conversation_gets_summarized(mind, clock):
    mind.thinker.llm.replies = ['{"speak":true,"say":"yo"}', '{"summary":"chat","importance":2}']
    await mind.handle(UserMessage(text="hi")); clock.advance(timedelta(minutes=11)); await mind.tick()
    assert mind.memory.unsummarized_turns() == []

async def test_offline_summary_aggregates(mind):
    await mind.handle(OfflineSummary(since=datetime(2026, 10, 24, tzinfo=timezone.utc), events=[
        {"type":"app_closed","package":"y","label":"YouTube","duration_s":3600},
        {"type":"app_closed","package":"y","label":"YouTube","duration_s":4200},
        {"type":"garbage"}]))
    assert "YouTube 2h 10m" in mind.memory.recent_turns()[-1].content
```

- [ ] **Step 2–4:** Run → FAIL, implement, run → PASS.
- [ ] **Step 5: Commit** `feat(brain): mind orchestrator`

---

### Task 11: WebSocket gateway and server entrypoint

**Files:** Create `brain/maya_brain/gateway.py`, `brain/maya_brain/__main__.py`; Test `brain/tests/test_gateway.py`

**Interfaces:**
- Consumes: `Config`, `Mind`, `parse_event`, `ProtocolError`, `Error`.
- Produces:
  - `create_app(cfg: Config, mind: Mind) -> FastAPI` — `GET /health` → `{"ok": true}`; `WS /ws?token=…`: wrong/missing token → accept, send `Error(code="auth")`, close 4401. One active socket: a new connection closes the previous one (4000) and becomes the sink target. Each frame: `parse_event` → `None` ignored; `ProtocolError` → `Error(code="bad_message")`; else `mind.handle` in a task (order kept by Mind's lock). Sink sends to the active socket, drops silently when none. Lifespan starts/stops `mind.run_ticker`.
  - `main(argv: list[str] | None = None) -> int` in `__main__.py`; `python -m maya_brain [--config PATH]` (default `maya.toml`) — refuses to start (exit 2, clear message) when token is `"change-me"`; builds `OllamaLLM`, `open_db(cfg.db_path)`, `SystemClock`, `Mind`, runs uvicorn on host/port; prints the URL for each local IPv4.

- [ ] **Step 1: Write failing tests** (FastAPI `TestClient`, Mind with `FakeLLM`, token `"t"`)

```python
def test_health(client): assert client.get("/health").json() == {"ok": True}

def test_bad_token(client):
    with client.websocket_connect("/ws?token=nope") as ws:
        assert ws.receive_json()["code"] == "auth"
        with pytest.raises(WebSocketDisconnect): ws.receive_json()

def test_chat_roundtrip(client):
    with client.websocket_connect("/ws?token=t") as ws:
        ws.send_json({"type": "user_message", "text": "hi"})
        got = [ws.receive_json() for _ in range(4)]
        assert any(m["type"] == "say" and m["text"] == "hehe" for m in got)

def test_bad_frames_do_not_kill_socket(client):
    with client.websocket_connect("/ws?token=t") as ws:
        ws.send_text("{nope"); assert ws.receive_json()["code"] == "bad_message"
        ws.send_json({"type": "teleport"})
        ws.send_json({"type": "hello", "client_version": "1", "screen_w": 1, "screen_h": 1})
        assert ws.receive_json()["type"] == "state"

def test_main_refuses_default_token(tmp_path, capsys):
    from maya_brain.__main__ import main
    assert main(["--config", str(tmp_path / "none.toml")]) == 2
    assert "token" in capsys.readouterr().err
```

- [ ] **Step 2–4:** Run → FAIL, implement, run → PASS.
- [ ] **Step 5: Commit** `feat(brain): websocket gateway and server entrypoint`

---

### Task 12: Simulator CLI and README

**Files:** Create `brain/maya_brain/sim.py`, `brain/README.md`; Test `brain/tests/test_sim.py`

**Interfaces:**
- Consumes: `Mind`, `FakeClock`, `FakeLLM`, `OllamaLLM`, event models, `load_config`, `open_db`.
- Produces:
  - `parse_command(line: str, now: datetime) -> tuple[str, object]` — returns `("event", Event)` for plain text (`UserMessage`), `/tap`, `/throw`, `/shush`, `/app <Label>` (`AppOpened`, package = `label.lower()`), `/close` (`AppClosed` of current app with elapsed seconds — resolved by runner, so returns `("close", None)`), `/screen on|off`, `/charge on|off`; `("time", datetime)` for `/time HH:MM` (same local date); `("skip", timedelta)` for `/skip 90m|3h|2d`; `("show", name)` for `/thoughts /mood /facts /traits /diary /status`; `("reflect", None)`; `("quit", None)` for `/quit`; `("help", None)` for unknown slash commands.
  - `async run_sim(mind: Mind, clock: FakeClock, read: Callable[[], str], write: Callable[[str], None]) -> None` — loop: read line → dispatch; actions printed as `Maya: <text>`, `*<animation>*`, `[state mood=… tier=…]`; `/skip` advances clock in `tick_s` steps calling `mind.tick()`; `/thoughts` prints the last 5 private thoughts; `EOFError` or `/quit` ends.
  - `python -m maya_brain.sim [--config PATH] [--fake-llm] [--db PATH]` (default db `maya-sim.db`).
  - README: install Ollama, `ollama pull qwen3:8b` and `ollama pull nomic-embed-text`, copy `maya.example.toml` → `maya.toml` and set token, run server, run simulator, Tailscale tip, privacy note.

- [ ] **Step 1: Write failing tests**

```python
NOW = datetime(2026, 10, 24, 14, 0).astimezone()

def test_parse_commands():
    assert parse_command("hello there", NOW)[1].text == "hello there"
    assert parse_command("/app TikTok", NOW)[1].label == "TikTok"
    assert parse_command("/skip 3h", NOW) == ("skip", timedelta(hours=3))
    assert parse_command("/skip 90m", NOW) == ("skip", timedelta(minutes=90))
    assert parse_command("/time 02:00", NOW)[1].hour == 2
    assert parse_command("/screen off", NOW)[1].on is False
    assert parse_command("/mood", NOW) == ("show", "mood")
    assert parse_command("/wat", NOW) == ("help", None)

async def test_scripted_session(mind, clock):
    lines = iter(["hi Maya", "/app TikTok", "/skip 1h", "/close", "/thoughts", "/quit"]); out = []
    await run_sim(mind, clock, lambda: next(lines), out.append)
    text = "\n".join(out)
    assert "Maya: hehe" in text
    assert mind.initiative.current_app() is None
```

- [ ] **Step 2–4:** Run → FAIL, implement, run → PASS.
- [ ] **Step 5: Manual check** with Ollama if available (`python -m maya_brain.sim`), otherwise `--fake-llm`: chat, `/app TikTok`, `/skip 2h`, `/reflect`, `/diary` all print sensible output.
- [ ] **Step 6: Commit** `feat(brain): simulator CLI and README`

---

## Done when

`cd brain && pytest -q` is green on 3.11 and 3.12 in CI, `python -m maya_brain.sim --fake-llm` runs a full session, and `python -m maya_brain` serves `/health` and `/ws`. Plan 2 (Android app) follows.
