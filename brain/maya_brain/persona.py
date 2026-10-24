import sqlite3
from dataclasses import dataclass
from typing import Literal

from .clock import Clock
from .config import PersonaConfig

TRAIT_KINDS = ("like", "dislike", "opinion", "joke", "nickname", "habit")

_ACTIONS = ("add", "reinforce", "weaken")
_NEW_CONFIDENCE = 0.4
_REINFORCE_DELTA = 0.2
_WEAKEN_DELTA = 0.3
_DECAY = 0.02
_PRUNE_BELOW = 0.15
_MAX_TEXT = 120
_MAX_RENDERED = 25


@dataclass
class Trait:
    kind: str
    text: str
    confidence: float


@dataclass
class TraitUpdate:
    kind: str
    text: str
    action: Literal["add", "reinforce", "weaken"]


class Persona:
    """Maya's fixed core personality plus the traits she learns over time."""

    def __init__(self, db: sqlite3.Connection, cfg: PersonaConfig, clock: Clock) -> None:
        self._db = db
        self._cfg = cfg
        self._clock = clock

    def traits(self) -> list[Trait]:
        """All learned traits, highest confidence first (ties: most recently updated)."""
        rows = self._db.execute(
            "SELECT kind, text, confidence FROM traits "
            "ORDER BY confidence DESC, updated_at DESC, id DESC"
        ).fetchall()
        return [Trait(r["kind"], r["text"], r["confidence"]) for r in rows]

    def apply_updates(self, updates: list[TraitUpdate]) -> None:
        now = self._clock.now().isoformat()
        touched: set[tuple[str, str]] = set()
        for u in updates:
            text = (u.text or "").strip()[:_MAX_TEXT].strip()
            if u.kind not in TRAIT_KINDS or u.action not in _ACTIONS or not text:
                continue
            row = self._db.execute(
                "SELECT confidence FROM traits WHERE kind = ? AND text = ?", (u.kind, text)
            ).fetchone()
            if u.action == "weaken":
                if row is None:
                    continue
                self._set(u.kind, text, row["confidence"] - _WEAKEN_DELTA, now)
            elif row is None:  # add / reinforce of a new trait
                self._set(u.kind, text, _NEW_CONFIDENCE, now)
            else:  # add of an existing trait is a reinforce
                self._set(u.kind, text, min(1.0, row["confidence"] + _REINFORCE_DELTA), now)
            touched.add((u.kind, text))
        # Anything not touched in this call fades a little.
        for r in self._db.execute("SELECT kind, text, confidence FROM traits").fetchall():
            if (r["kind"], r["text"]) not in touched:
                self._db.execute(
                    "UPDATE traits SET confidence = ? WHERE kind = ? AND text = ?",
                    (r["confidence"] - _DECAY, r["kind"], r["text"]),
                )
        self._db.execute("DELETE FROM traits WHERE confidence < ?", (_PRUNE_BELOW,))

    def _set(self, kind: str, text: str, confidence: float, now: str) -> None:
        self._db.execute(
            "INSERT INTO traits(kind, text, confidence, updated_at) VALUES(?, ?, ?, ?) "
            "ON CONFLICT(kind, text) DO UPDATE SET "
            "confidence = excluded.confidence, updated_at = excluded.updated_at",
            (kind, text, confidence, now),
        )

    def render(self) -> str:
        cfg = self._cfg
        lines = [
            f"You are {cfg.name}.",
            f"Core personality (never changes): {', '.join(cfg.core_traits)}.",
            f"Style: {cfg.style}",
        ]
        learned = self.traits()[:_MAX_RENDERED]
        if learned:
            lines.append("")
            lines.append("Things you've learned about yourself over time:")
            for kind in TRAIT_KINDS:
                texts = [t.text for t in learned if t.kind == kind]
                if texts:
                    lines.append(f"- {kind}: " + "; ".join(texts))
        return "\n".join(lines)
