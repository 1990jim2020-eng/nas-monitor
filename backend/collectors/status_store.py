import fcntl
import json
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from .shadow_store import shadow_path


def _now() -> str:
    return datetime.now(timezone(timedelta(hours=8))).isoformat()


def update_status(name: str, **fields: Any) -> None:
    target = shadow_path("collector_status.json")
    target.parent.mkdir(parents=True, exist_ok=True)
    lock_path = target.with_suffix(target.suffix + ".lock")
    with open(lock_path, "a+", encoding="utf-8") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            try:
                with target.open(encoding="utf-8") as stream:
                    status = json.load(stream)
            except (FileNotFoundError, json.JSONDecodeError, OSError):
                status = {}
            if not isinstance(status, dict):
                status = {}
            entry = status.get(name)
            entry = dict(entry) if isinstance(entry, dict) else {}
            entry.update(fields)
            entry["updated_at"] = _now()
            status[name] = entry
            fd, temporary = tempfile.mkstemp(prefix=f".{target.name}.", dir=str(target.parent))
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as stream:
                    json.dump(status, stream, ensure_ascii=False, indent=2, sort_keys=True)
                    stream.write("\n")
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, target)
            except Exception:
                try:
                    os.unlink(temporary)
                except FileNotFoundError:
                    pass
                raise
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def read_status() -> dict:
    try:
        with shadow_path("collector_status.json").open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    return value if isinstance(value, dict) else {}
