import json
import os
import tempfile
from pathlib import Path
from typing import Any, Optional


def _root() -> Path:
    return Path(os.environ.get("BACKEND_ROOT", "/app")).resolve()


def _safe_join(root: Path, relative_name: str) -> Path:
    relative = Path(relative_name)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("path must be relative and stay inside root")
    target = (root / relative).resolve()
    if target != root and root not in target.parents:
        raise ValueError("path escapes root")
    return target


def shadow_path(relative_name: str) -> Path:
    """Return a path below BACKEND_ROOT/shadow, rejecting path traversal."""
    root = (_root() / "shadow").resolve()
    return _safe_join(root, relative_name)


def cutover_enabled(switch: Optional[str] = None) -> bool:
    """Whether a final snapshot should be written to its production path.

    `NAS_MONITOR_CUTOVER=1` is a master switch that enables every source at
    once (kept for emergency/one-shot migration). Otherwise a per-source switch
    (e.g. NAS_MONITOR_CUTOVER_BALANCE=1) enables just that source, allowing
    safe, independent, reversible cutover without double-writing production.
    """
    if os.environ.get("NAS_MONITOR_CUTOVER", "0") == "1":
        return True
    if switch:
        return os.environ.get(switch, "0") == "1"
    return False


def snapshot_target(relative_name: str,
                    production_relative: Optional[str] = None,
                    switch: Optional[str] = None) -> Path:
    """Final-snapshot output path, cutover-aware.

    Shadow mode writes BACKEND_ROOT/shadow/<relative_name>.
    Cutover mode writes BACKEND_ROOT/<production_relative or relative_name>.
    """
    if cutover_enabled(switch):
        return _safe_join(_root(), production_relative or relative_name)
    return shadow_path(relative_name)


def _atomic_write(target: Path, value: Any) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{target.name}.", dir=str(target.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
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
    return target


def atomic_write_json(relative_name: str, value: Any) -> Path:
    """Write an internal/shadow state file. Never follows the cutover switch."""
    return _atomic_write(shadow_path(relative_name), value)


def atomic_write_snapshot(relative_name: str, value: Any,
                          production_relative: Optional[str] = None,
                          switch: Optional[str] = None) -> Path:
    """Write a final snapshot, switching to production only when its cutover is on."""
    return _atomic_write(snapshot_target(relative_name, production_relative, switch), value)


def read_snapshot(relative_name: str,
                  production_relative: Optional[str] = None,
                  switch: Optional[str] = None):
    """Read a final snapshot, from production when its cutover is on, else shadow.

    Returns None when the file is missing or malformed.
    """
    target = snapshot_target(relative_name, production_relative, switch)
    try:
        with target.open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None
    return value if isinstance(value, dict) else None
