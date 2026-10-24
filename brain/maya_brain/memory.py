import sqlite3
from dataclasses import dataclass
from datetime import datetime

import numpy as np

from maya_brain.clock import Clock
from maya_brain.llm import LLM, LLMError


@dataclass
class Turn:
    role: str  # user | maya | event
    content: str
    ts: datetime


@dataclass
class Episode:
    id: int
    summary: str
    importance: int
    created_at: datetime
    score: float = 0.0


def _turn(row: sqlite3.Row) -> Turn:
    return Turn(row["role"], row["content"], datetime.fromisoformat(row["ts"]))


def _episode(row: sqlite3.Row, score: float = 0.0) -> Episode:
    return Episode(
        row["id"], row["summary"], row["importance"], datetime.fromisoformat(row["created_at"]), score
    )


class Memory:
    """Three layers: short-term turn buffer, embedded episodes, key/value facts.

    Turns are ordered by insertion (id), not by ts: the clock can jump (simulator, NTP).
    """

    def __init__(self, db: sqlite3.Connection, llm: LLM, clock: Clock, buffer_size: int = 20):
        self.db = db
        self.llm = llm
        self.clock = clock
        self.buffer_size = buffer_size

    # --- short-term turns ---
    def add_turn(self, role: str, content: str) -> None:
        self.db.execute(
            "INSERT INTO turns(role, content, ts) VALUES(?, ?, ?)",
            (role, content, self.clock.now().isoformat()),
        )

    def recent_turns(self) -> list[Turn]:
        rows = self.db.execute(
            "SELECT * FROM turns ORDER BY id DESC LIMIT ?", (self.buffer_size,)
        ).fetchall()
        return [_turn(r) for r in reversed(rows)]

    def unsummarized_turns(self) -> list[Turn]:
        rows = self.db.execute("SELECT * FROM turns WHERE summarized = 0 ORDER BY id").fetchall()
        return [_turn(r) for r in rows]

    def mark_summarized(self) -> None:
        self.db.execute("UPDATE turns SET summarized = 1 WHERE summarized = 0")

    def last_turn_time(self, roles: tuple[str, ...] = ("user", "maya")) -> datetime | None:
        if not roles:
            return None
        marks = ",".join("?" * len(roles))
        row = self.db.execute(
            f"SELECT ts FROM turns WHERE role IN ({marks}) ORDER BY id DESC LIMIT 1", roles
        ).fetchone()
        return None if row is None else datetime.fromisoformat(row["ts"])

    # --- episodes ---
    async def add_episode(self, summary: str, importance: int) -> int:
        importance = max(1, min(5, int(importance)))
        vec = np.asarray(await self.llm.embed(summary), dtype=np.float32)
        cur = self.db.execute(
            "INSERT INTO episodes(summary, embedding, importance, created_at) VALUES(?, ?, ?, ?)",
            (summary, vec.tobytes(), importance, self.clock.now().isoformat()),
        )
        return int(cur.lastrowid)

    async def recall(self, query: str, k: int = 5) -> list[Episode]:
        rows = self.db.execute("SELECT * FROM episodes").fetchall()
        if not rows:
            return []
        try:
            q = np.asarray(await self.llm.embed(query), dtype=np.float32)
        except LLMError:
            return []
        qn = float(np.linalg.norm(q))
        now = self.clock.now()
        scored: list[Episode] = []
        for row in rows:
            if row["embedding"] is None:
                continue
            v = np.frombuffer(row["embedding"], dtype=np.float32)
            denom = qn * float(np.linalg.norm(v))
            if v.shape != q.shape or denom == 0:
                continue
            sim = float(np.dot(q, v)) / denom
            ep = _episode(row)
            age_days = max(0.0, (now - ep.created_at).total_seconds() / 86400)
            ep.score = sim * 0.5 ** (age_days / 30) * (0.5 + ep.importance / 10)
            scored.append(ep)
        scored.sort(key=lambda e: e.score, reverse=True)
        return scored[:k]

    def episodes_since(self, dt: datetime) -> list[Episode]:
        rows = self.db.execute("SELECT * FROM episodes ORDER BY created_at, id").fetchall()
        return [e for e in map(_episode, rows) if e.created_at >= dt]

    # --- facts ---
    def set_fact(self, key: str, value: str) -> None:
        self.db.execute(
            "INSERT INTO facts(key, value, updated_at) VALUES(?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
            (key, value, self.clock.now().isoformat()),
        )

    def delete_fact(self, key: str) -> None:
        self.db.execute("DELETE FROM facts WHERE key = ?", (key,))

    def facts(self, limit: int = 40) -> dict[str, str]:
        rows = self.db.execute(
            "SELECT key, value FROM facts ORDER BY updated_at DESC, rowid DESC LIMIT ?", (limit,)
        ).fetchall()
        return {r["key"]: r["value"] for r in rows}
