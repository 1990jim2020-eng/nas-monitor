"""Merge backend balance + workbuddy shadows into the production balance schema.

Hermes balance_report_local.py writes a single /app/balance_snapshot.json that
contains both `channels` (Pipio) and a nested `workbuddy` section. This backend
collector reproduces that exact shape from the two backend shadow sources:

  shadow/balance_snapshot.json   -> channels, total
  shadow/workbuddy_status.json   -> workbuddy section

Output target is cutover-aware (shadow/balance_snapshot_combined.json by
default, production balance_snapshot.json when NAS_MONITOR_CUTOVER=1).
"""
from datetime import datetime, timedelta, timezone

from .shadow_store import atomic_write_snapshot, shadow_path

SHANGHAI = timezone(timedelta(hours=8))

# Internal input files stay shadow-only.
BALANCE_SHADOW = "balance_snapshot.json"
WORKBUDDY_SHADOW = "workbuddy_status.json"

# Final snapshot file name (shadow variant keeps a distinct name to avoid
# clobbering pipio_balance's own shadow output before cutover).
COMBINED_SHADOW = "balance_snapshot_combined.json"
PRODUCTION_FILE = "balance_snapshot.json"

# Keys not part of the production workbuddy payload.
_WORKBUDDY_DROP_KEYS = ("source", "updated_at")


def _load(relative: str) -> dict:
    import json
    try:
        with shadow_path(relative).open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    return value if isinstance(value, dict) else {}


def _workbuddy_section(status: dict) -> dict:
    return {k: v for k, v in status.items() if k not in _WORKBUDDY_DROP_KEYS}


def collect() -> dict:
    balance = _load(BALANCE_SHADOW)
    channels = balance.get("channels")
    if not isinstance(channels, list) or not channels:
        raise RuntimeError("Pipio balance shadow 尚未生成，保留上一份合并快照")

    total = balance.get("total")
    try:
        total = round(float(total), 2) if total is not None else None
    except (TypeError, ValueError):
        total = None

    now_local = datetime.now(SHANGHAI)
    # Hermes writes "MM-dd HH:mm" for updated_at; keep it for front-end parity.
    now_str = now_local.strftime("%m-%d %H:%M")

    result = {
        "updated_at": now_str,
        "channels": channels,
        "total": total,
    }

    workbuddy = _load(WORKBUDDY_SHADOW)
    if workbuddy:
        result["workbuddy"] = _workbuddy_section(workbuddy)

    # Shadow mode uses a distinct file so it never clobbers pipio_balance's
    # shadow output; cutover mode writes the real production file.
    atomic_write_snapshot(COMBINED_SHADOW, result, production_relative=PRODUCTION_FILE,
                          switch="NAS_MONITOR_CUTOVER_BALANCE")
    return result
