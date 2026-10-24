import sqlite3
from datetime import datetime, time

from .clock import Clock
from .config import ReflectionConfig
from .db import get_state, set_state
from .llm import LLM, LLMError, extract_json
from .memory import Episode, Memory
from .persona import TRAIT_KINDS, Persona, TraitUpdate

STATE_KEY = "reflection"

_JSON_ONLY = "Respond with ONLY a single JSON object, no other text."

SUMMARY_TASK = (
    "Below is a transcript of your recent conversation with the user. Write a short episode "
    "summary of it from your own perspective, in third person about \"the user\" "
    "(e.g. \"The user told me they ...\"). Keep it to one to three sentences and keep only what "
    "is worth remembering. Rate its importance from 1 (small talk) to 5 (very significant).\n"
    f'{_JSON_ONLY} Format: {{"summary": "<text>", "importance": <1-5>}}'
)

FACTS_TASK = (
    "You are updating your notebook of durable facts about the user, based on what happened "
    "recently. Add or update a fact only if it is stable and worth remembering later "
    "(name, job, tastes, people and pets in their life, routines, ...). Keep each fact short, "
    "with a short lowercase key (e.g. \"favorite_food\"). Reuse an existing key to update its "
    "value. List in \"remove\" the keys of facts that are now wrong or obsolete. Include nothing "
    "if nothing durable was learned.\n"
    f'{_JSON_ONLY} Format: {{"facts": [{{"key": "<key>", "value": "<value>"}}], "remove": ["<key>"]}}'
)

TRAITS_TASK = (
    "Reflect on how you yourself have developed, based on what happened recently. Include a "
    "trait only if it genuinely emerged from these events; most days there are few or none. "
    f"Kinds: {', '.join(TRAIT_KINDS)}. Actions: \"add\" (new), \"reinforce\" (it showed up "
    "again), \"weaken\" (it no longer seems true). Keep each text short, in your own words. "
    "To reinforce or weaken an existing trait, repeat its kind and text exactly.\n"
    f'{_JSON_ONLY} Format: {{"traits": [{{"kind": "<kind>", "text": "<text>", '
    '"action": "add|reinforce|weaken"}]}'
)

DIARY_TASK = (
    "Write today's diary entry, in the first person and in your own voice: what happened, how "
    "you felt, what you want to remember or look forward to. A short paragraph, honest and "
    "personal. If little happened, say so briefly.\n"
    f'{_JSON_ONLY} Format: {{"entry": "<text>"}}'
)


def _parse_hhmm(value: str) -> time:
    hour, minute = value.split(":")
    return time(int(hour), int(minute))


def _clean_str(value) -> str | None:
    if not isinstance(value, str):
        return None
    value = value.strip()
    return value or None


class Reflection:
    """Maya's learning loop: episodes, facts, learned traits and the nightly diary."""

    def __init__(
        self,
        llm: LLM,
        memory: Memory,
        persona: Persona,
        db: sqlite3.Connection,
        clock: Clock,
        cfg: ReflectionConfig,
    ) -> None:
        self.llm = llm
        self.memory = memory
        self.persona = persona
        self.db = db
        self.clock = clock
        self.cfg = cfg

    # --- scheduling ---
    def due(self, last_event: datetime | None) -> bool:
        now = self.clock.now()
        state = get_state(self.db, STATE_KEY) or {}
        if state.get("last_date") == now.date().isoformat():
            return False
        if now.time() >= _parse_hhmm(self.cfg.time):
            return True
        if last_event is not None:
            return (now - last_event).total_seconds() > self.cfg.idle_hours * 3600
        return False

    # --- steps ---
    async def _ask(self, task: str, body: str) -> dict:
        """One JSON call. Raises LLMError or ValueError on failure."""
        messages = [
            {"role": "system", "content": f"{self.persona.render()}\n\n{task}"},
            {"role": "user", "content": body},
        ]
        return extract_json(await self.llm.chat(messages, json_mode=True))

    async def summarize_pending(self) -> int | None:
        turns = self.memory.unsummarized_turns()
        if not turns:
            return None
        transcript = "\n".join(f"[{t.ts.strftime('%H:%M')}] {t.role}: {t.content}" for t in turns)
        try:
            data = await self._ask(SUMMARY_TASK, transcript)
            summary = _clean_str(data.get("summary"))
            importance = data.get("importance")
            if summary is None or isinstance(importance, bool) or not isinstance(importance, (int, float)):
                raise ValueError("bad summary shape")
            episode_id = await self.memory.add_episode(summary, int(importance))
        except (LLMError, ValueError):
            return None
        self.memory.mark_summarized()
        return episode_id

    def _episodes_since_last(self) -> list[Episode]:
        now = self.clock.now()
        state = get_state(self.db, STATE_KEY) or {}
        try:
            since = datetime.fromisoformat(state["last_at"])
        except (KeyError, TypeError, ValueError):
            since = now.replace(hour=0, minute=0, second=0, microsecond=0)
        # Strictly after: an episode written at the instant of the last reflection was covered by it.
        return [e for e in self.memory.episodes_since(since) if e.created_at > since]

    @staticmethod
    def _episode_lines(episodes: list[Episode]) -> str:
        if not episodes:
            return "(nothing new happened)"
        return "\n".join(f"- {e.created_at.date().isoformat()}: {e.summary}" for e in episodes)

    def _facts_lines(self) -> str:
        facts = self.memory.facts()
        return "\n".join(f"{k}: {v}" for k, v in facts.items()) or "(none yet)"

    def _trait_lines(self) -> str:
        traits = self.persona.traits()
        return (
            "\n".join(f"{t.kind}: {t.text} ({t.confidence:.2f})" for t in traits) or "(none yet)"
        )

    async def _update_facts(self, episodes: str) -> None:
        body = (
            f"What happened recently:\n{episodes}\n\n"
            f"Facts you currently know about the user:\n{self._facts_lines()}"
        )
        data = await self._ask(FACTS_TASK, body)
        facts, remove = data.get("facts"), data.get("remove", [])
        if not isinstance(facts, list) or not isinstance(remove, list):
            raise ValueError("bad facts shape")
        updates: dict[str, str] = {}
        for item in facts:
            if not isinstance(item, dict):
                continue
            key, value = _clean_str(item.get("key")), _clean_str(item.get("value"))
            if key and value:
                updates[key] = value
        for key in map(_clean_str, remove):
            if key and key not in updates:
                self.memory.delete_fact(key)
        for key, value in updates.items():
            self.memory.set_fact(key, value)

    async def _update_traits(self, episodes: str) -> None:
        body = (
            f"What happened recently:\n{episodes}\n\n"
            f"Traits you have learned so far:\n{self._trait_lines()}"
        )
        data = await self._ask(TRAITS_TASK, body)
        traits = data.get("traits")
        if not isinstance(traits, list):
            raise ValueError("bad traits shape")
        updates = []
        for item in traits:
            if not isinstance(item, dict):
                continue
            kind, text, action = (_clean_str(item.get(k)) for k in ("kind", "text", "action"))
            if kind and text and action:
                updates.append(TraitUpdate(kind, text, action))  # type: ignore[arg-type]
        self.persona.apply_updates(updates)

    async def _write_diary(self, episodes: str) -> None:
        data = await self._ask(DIARY_TASK, f"What happened today:\n{episodes}")
        entry = _clean_str(data.get("entry"))
        if entry is None:
            raise ValueError("bad diary shape")
        now = self.clock.now()
        self.db.execute(
            "INSERT INTO diary(date, entry, created_at) VALUES(?, ?, ?) "
            "ON CONFLICT(date) DO UPDATE SET entry = excluded.entry, created_at = excluded.created_at",
            (now.date().isoformat(), entry, now.isoformat()),
        )

    async def reflect(self) -> bool:
        await self.summarize_pending()
        episodes = self._episode_lines(self._episodes_since_last())
        diary_written = True
        for step in (self._update_facts, self._update_traits):
            try:
                await step(episodes)
            except (LLMError, ValueError):
                pass  # one failing step must not stop the others
        try:
            await self._write_diary(episodes)
        except (LLMError, ValueError):
            diary_written = False
        now = self.clock.now()
        set_state(self.db, STATE_KEY, {"last_date": now.date().isoformat(), "last_at": now.isoformat()})
        return diary_written

    def diary(self, limit: int = 7) -> list[tuple[str, str]]:
        rows = self.db.execute(
            "SELECT date, entry FROM diary ORDER BY date DESC LIMIT ?", (limit,)
        ).fetchall()
        return [(r["date"], r["entry"]) for r in rows]
