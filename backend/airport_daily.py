"""按北京时间累计订阅计数增量；只写本服务的数据目录。"""
import fcntl
import json
import math
import os
import tempfile
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

STATE_FILE = Path(__file__).resolve().parent / "data" / "airport_daily.json"
SHANGHAI = ZoneInfo("Asia/Shanghai")


def update_usage(key, used_bytes, total_bytes, now=None, state_file=None):
    """返回订阅快照估算量；首次启用、长时间跨日缺采样及套餐重置标记部分统计。"""
    if not all(math.isfinite(v) and v >= 0 for v in (used_bytes, total_bytes)):
        raise ValueError("invalid subscription counter")
    now = (now or datetime.now(SHANGHAI)).astimezone(SHANGHAI)
    day, timestamp = now.date().isoformat(), now.timestamp()
    path = Path(state_file) if state_file is not None else STATE_FILE
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.with_suffix(".lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            state = json.loads(path.read_text())
        except FileNotFoundError:
            state = {}
        # 损坏文件不静默清空，调用方降级为暂不可用并保留原文件。
        if not isinstance(state, dict):
            raise ValueError("invalid airport daily state")
        old = state.get(key)
        if old and timestamp < old["sample_at"]:
            raise ValueError("out-of-order airport sample")
        same_day = bool(old and old["day"] == day)
        recent = bool(old and 0 <= timestamp - old["sample_at"] <= 180)
        used = old["today_bytes"] if same_day else 0.0
        partial = old["partial"] if same_day else not recent
        if old:
            reset = used_bytes < old["last_used"] or total_bytes != old["total"]
            if reset:
                # 无法分辨套餐重置与上游计数修正，重新建立基线，不制造用量。
                partial = True
            elif same_day or recent:
                used += used_bytes - old["last_used"]
        state[key] = {"day": day, "today_bytes": used, "partial": partial,
                      "last_used": used_bytes, "total": total_bytes, "sample_at": timestamp}
        fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".airport_daily_")
        try:
            with os.fdopen(fd, "w") as stream:
                json.dump(state, stream)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(tmp, path)
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)
    return {"today_used_gb": round(used / (1024 ** 3), 4),
            "today_usage_partial": partial, "today_usage_date": day}
