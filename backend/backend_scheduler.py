import threading
import time
from typing import Any

from collectors.pipio_uptime import collect as collect_pipio_uptime
from collectors.pipio_balance import collect as collect_pipio_balance
from collectors.pipio_usage import collect as collect_pipio_usage
from collectors.workbuddy_status import collect as collect_workbuddy_status
from collectors.cron_registry import collect as collect_cron_registry
from collectors.workbuddy_cap_2359 import collect as collect_workbuddy_cap_2359
from collectors.probe_state import collect as collect_probe_state
from collectors.balance_combined import collect as collect_balance_combined
from collectors.speedtest import collect_daily as collect_speedtest_daily
from collectors.status_store import update_status, read_status


class Task:
    def __init__(self, interval, timeout, collector, enabled=True, cron_hour=None, cron_minute=None):
        self.interval = interval
        self.timeout = timeout
        self.collector = collector
        self.enabled = enabled
        self.cron_hour = cron_hour
        self.cron_minute = cron_minute

    @property
    def is_cron(self):
        return self.cron_hour is not None and self.cron_minute is not None


TASKS = {
    "pipio_uptime": Task(600, 45, collect_pipio_uptime),
    "pipio_balance": Task(600, 45, collect_pipio_balance),
    "workbuddy_status": Task(600, 30, collect_workbuddy_status),
    "cron_registry": Task(600, 30, collect_cron_registry),
    "pipio_usage": Task(1800, 60, collect_pipio_usage),
    "workbuddy_cap_2359": Task(60, 60, collect_workbuddy_cap_2359, cron_hour=23, cron_minute=59),
    "probe_state": Task(600, 30, collect_probe_state),
    "balance_combined": Task(600, 15, collect_balance_combined),
    "speedtest_daily": Task(60, 300, collect_speedtest_daily, cron_hour=2, cron_minute=0),
}

_state_lock = threading.Lock()
_running = False
_start_lock = threading.Lock()


def _safe_update_status(name: str, **fields: Any) -> None:
    """状态上报永不上抛异常；失败只保留日志输出，不影响调度循环。"""
    try:
        update_status(name, **fields)
    except Exception as exc:
        print(f"scheduler status update failed for {name}: {exc}")


def health_state() -> dict:
    with _state_lock:
        started = _running
    tasks = {}
    try:
        tasks = read_status()
    except Exception:
        tasks = {}
    return {"started": started, "tasks": tasks}


def _run_cron_one(name: str, task: Task, stop_event: threading.Event) -> None:
    """cron 型任务：到达指定 hour:minute（上海时区）执行一次，当天不重复。"""
    last_fired_day = None
    while not stop_event.is_set() and task.enabled:
        now = time.localtime()
        matched = now.tm_hour == task.cron_hour and now.tm_min == task.cron_minute
        fire_day = (now.tm_year, now.tm_mon, now.tm_mday)
        if matched and fire_day != last_fired_day:
            last_fired_day = fire_day
            started = time.monotonic()
            _safe_update_status(name, state="running", last_started_at=time.time())
            result = {}

            def invoke() -> None:
                try:
                    result["value"] = task.collector()
                except Exception as exc:
                    result["error"] = exc

            worker = threading.Thread(target=invoke, daemon=True, name=f"scheduler-{name}-worker")
            worker.start()
            worker.join(task.timeout)
            if worker.is_alive():
                _safe_update_status(name, state="failed", error=f"timeout after {task.timeout}s")
                worker.join()
            elif "error" in result:
                _safe_update_status(name, state="failed", failures=1, error=str(result["error"])[:300])
            else:
                _safe_update_status(name, state="ok", failures=0)
        if stop_event.wait(1):
            break


def _run_one(name: str, task: Task, stop_event: threading.Event) -> None:
    failures = 0
    while not stop_event.is_set() and task.enabled:
        started = time.monotonic()
        _safe_update_status(name, state="running", last_started_at=time.time())
        result = {}

        def invoke() -> None:
            try:
                result["value"] = task.collector()
            except Exception as exc:
                result["error"] = exc

        worker = threading.Thread(target=invoke, daemon=True, name=f"scheduler-{name}-worker")
        worker.start()
        worker.join(task.timeout)
        if worker.is_alive():
            # 超时：worker 无法安全取消，但必须阻止下一轮在它结束前再次启动。
            _safe_update_status(name, state="failed", error=f"timeout after {task.timeout}s")
            worker.join()
            failures += 1
            delay = min(task.interval, 5 * (2 ** min(failures - 1, 5)))
            if stop_event.wait(delay):
                break
            continue
        if "error" in result:
            failures += 1
            delay = min(task.interval, 5 * (2 ** min(failures - 1, 5)))
            _safe_update_status(name, state="failed", failures=failures, error=str(result["error"])[:300])
        else:
            failures = 0
            _safe_update_status(name, state="ok", failures=0)
            delay = task.interval
        delay = max(0.0, delay - (time.monotonic() - started))
        if stop_event.wait(delay):
            break


def start_scheduler(stop_event: threading.Event) -> threading.Thread:
    global _running
    with _start_lock:
        with _state_lock:
            if _running:
                raise RuntimeError("scheduler already started in this process")
            _running = True

    def run() -> None:
        try:
            threads = []
            for name, task in TASKS.items():
                if not task.enabled:
                    continue
                target = _run_cron_one if task.is_cron else _run_one
                threads.append(threading.Thread(target=target, args=(name, task, stop_event), daemon=True, name=f"scheduler-{name}"))
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()
        finally:
            with _state_lock:
                global _running
                _running = False

    thread = threading.Thread(target=run, daemon=True, name="nas-monitor-scheduler")
    thread.start()
    return thread
