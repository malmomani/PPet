import json
import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS turns(
    id INTEGER PRIMARY KEY,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    ts TEXT NOT NULL,
    summarized INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS episodes(
    id INTEGER PRIMARY KEY,
    summary TEXT NOT NULL,
    embedding BLOB,
    importance INTEGER NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS facts(
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS traits(
    id INTEGER PRIMARY KEY,
    kind TEXT NOT NULL,
    text TEXT NOT NULL,
    confidence REAL NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(kind, text)
);
CREATE TABLE IF NOT EXISTS thoughts(
    id INTEGER PRIMARY KEY,
    ts TEXT NOT NULL,
    trigger TEXT NOT NULL,
    json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS diary(
    date TEXT PRIMARY KEY,
    entry TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS state(
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


def open_db(path: str | Path) -> sqlite3.Connection:
    """Open (and initialise) the SQLite database. Autocommit: every write is durable at once."""
    db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
    db.row_factory = sqlite3.Row
    db.executescript(SCHEMA)
    return db


def get_state(db: sqlite3.Connection, key: str) -> dict | None:
    row = db.execute("SELECT value FROM state WHERE key = ?", (key,)).fetchone()
    return None if row is None else json.loads(row["value"])


def set_state(db: sqlite3.Connection, key: str, value: dict) -> None:
    db.execute(
        "INSERT INTO state(key, value) VALUES(?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (key, json.dumps(value)),
    )
