import tomllib
from dataclasses import dataclass, field, fields, is_dataclass, replace
from pathlib import Path
from typing import Any

DEFAULT_STYLE = (
    "Short, casual messages, like texting a friend. Uses an emoji sometimes, "
    "not every message. Teasing grows as she gets closer to the person."
)


@dataclass
class ServerConfig:
    host: str = "0.0.0.0"
    port: int = 8765
    token: str = "change-me"


@dataclass
class LLMConfig:
    base_url: str = "http://localhost:11434"
    chat_model: str = "qwen3:8b"
    embed_model: str = "nomic-embed-text"
    timeout_s: float = 30.0
    think: bool | None = False
    temperature: float = 0.8


@dataclass
class PersonaConfig:
    name: str = "Maya"
    core_traits: list[str] = field(default_factory=lambda: ["curious", "playful"])
    style: str = DEFAULT_STYLE


@dataclass
class InitiativeConfig:
    threshold: float = 60.0
    tick_s: float = 30.0
    cooldown_min: int = 10
    daily_cap: int = 15
    quiet_start: str = "23:30"
    quiet_end: str = "08:00"
    late_night_max: int = 2
    shush_min: int = 60


@dataclass
class ReflectionConfig:
    time: str = "04:00"
    idle_hours: float = 6.0


@dataclass
class Config:
    server: ServerConfig = field(default_factory=ServerConfig)
    llm: LLMConfig = field(default_factory=LLMConfig)
    persona: PersonaConfig = field(default_factory=PersonaConfig)
    initiative: InitiativeConfig = field(default_factory=InitiativeConfig)
    reflection: ReflectionConfig = field(default_factory=ReflectionConfig)
    db_path: str = "maya.db"


def _merge(section: Any, overrides: dict[str, Any]) -> Any:
    known = {f.name for f in fields(section)}
    return replace(section, **{k: v for k, v in overrides.items() if k in known})


def load_config(path: Path | None) -> Config:
    cfg = Config()
    if path is None or not Path(path).is_file():
        return cfg
    with open(path, "rb") as f:
        data = tomllib.load(f)
    updates: dict[str, Any] = {}
    for f_ in fields(cfg):
        if f_.name not in data:
            continue
        value = data[f_.name]
        current = getattr(cfg, f_.name)
        if is_dataclass(current):
            if isinstance(value, dict):
                updates[f_.name] = _merge(current, value)
        else:
            updates[f_.name] = value
    return replace(cfg, **updates)
