import glob
import json
import os
import threading
import urllib.request
from datetime import datetime, timedelta, timezone

from .shadow_store import atomic_write_json, atomic_write_snapshot, shadow_path, snapshot_target

WORKBUDDY_BASE = os.environ.get("WORKBUDDY_BASE", "")
WORKBUDDY_CONFIG = os.environ.get("WORKBUDDY_CONFIG", "/run/secrets/workbuddy-config.json")
WORKBUDDY_WAN1_BASE = os.environ.get("WORKBUDDY_WAN1_BASE", "")
WORKBUDDY_WAN1_CONFIG = os.environ.get("WORKBUDDY_WAN1_CONFIG", "/run/secrets/workbuddy-wan1-config.json")
WORKBUDDY_AUTH_DIR = os.environ.get("WORKBUDDY_AUTH_DIR", "/app/secrets/workbuddy-auths")
# 各出口实例的授权目录：默认出口与 WAN1 出口各有独立 auths，
# billing 积分必须按分组各自读取，否则 WAN1 账号取不到额度。
WORKBUDDY_WAN1_AUTH_DIR = os.environ.get("WORKBUDDY_WAN1_AUTH_DIR", "/app/secrets/workbuddy-wan1-auths")
_WORKBUDDY_AUTH_DIRS = (WORKBUDDY_AUTH_DIR, WORKBUDDY_WAN1_AUTH_DIR)
SHANGHAI = timezone(timedelta(hours=8))

_CREDIT_CACHE = {"ts": 0.0, "value": None}
_CREDIT_CACHE_TTL = 600  # 10 分钟
_AUTH_FINGERPRINT = None


def _read_json(path):
    with open(path, encoding="utf-8") as stream:
        value = json.load(stream)
    return value if isinstance(value, dict) else {}


def _http_json(url, token=None, extra_headers=None, timeout=20, data=None):
    headers = {"User-Agent": "Mozilla/5.0"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if extra_headers:
        headers.update(extra_headers)
    if data is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(data).encode()
    req = urllib.request.Request(url, data=data, headers=headers, method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8", errors="replace"))


def _load_shadow_json(relative):
    try:
        with shadow_path(relative).open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    return value if isinstance(value, dict) else {}


def _save_shadow_json(relative, value):
    atomic_write_json(relative, value)

def _load_cap_cutover():
    target = snapshot_target("workbuddy_credit_cap.json",
                             production_relative="workbuddy_credit_cap.json",
                             switch="NAS_MONITOR_CUTOVER_BALANCE")
    try:
        with target.open(encoding="utf-8") as stream:
            value = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    return value if isinstance(value, dict) else {}


def _valid_credit(value):
    import math
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(float(value)) and float(value) >= 0
            and float(value).is_integer())


def _auth_dirs_fingerprint():
    """auths 目录文件指纹：新增/删除账号时变化，用于使积分缓存立即失效。"""
    parts = []
    for directory in _WORKBUDDY_AUTH_DIRS:
        try:
            names = sorted(os.listdir(directory))
        except OSError:
            names = []
        stat_parts = []
        for name in names:
            try:
                st = os.stat(os.path.join(directory, name))
                stat_parts.append((name, st.st_size, int(st.st_mtime)))
            except OSError:
                continue
        parts.append((directory, tuple(stat_parts)))
    return tuple(parts)


def _today_key():
    return datetime.now(SHANGHAI).strftime("%Y-%m-%d")


def _today_used(used_now, channel_name):
    """今日消耗 = 当前累计值 - 今日基线累计值；wb 月累计月初归零，回退视为周期重置。"""
    if used_now is None:
        return None
    today = _today_key()
    base = _load_shadow_json("balance_baseline.json")
    base = {k: v for k, v in base.items() if k == today}
    today_base = base.get(today, {})
    prev = today_base.get(channel_name)
    if prev is None:
        today_base[channel_name] = used_now
        base[today] = today_base
        _save_shadow_json("balance_baseline.json", base)
        return None
    if used_now < prev:
        return used_now
    return used_now - prev


def _roll_cap_shadow(workbuddy, now_local, source, effective_date=None, merge_missing=False):
    """shadow 版 roll_wb_cap：写 shadow workbuddy_credit_cap.json。"""
    local_now = now_local.astimezone(SHANGHAI)
    day = effective_date or (local_now.date() + timedelta(days=1)).isoformat()
    values = {}
    captured_at = local_now.isoformat(timespec="seconds")
    for account in (workbuddy or {}).get("accounts") or []:
        uid = account.get("uid")
        credit = account.get("credits")
        if uid and _valid_credit(credit):
            values[str(uid)] = {"credits": int(credit), "captured_at": captured_at, "source": source}
    if not values:
        return 0
    data = _load_cap_cutover()
    existing = data.get(day)
    if isinstance(existing, dict) and not merge_missing:
        return 0
    if isinstance(existing, dict) and merge_missing:
        old_values = existing.get("values") if isinstance(existing.get("values"), dict) else {}
        missing = {uid: value for uid, value in values.items() if uid not in old_values}
        if not missing:
            return 0
        existing["values"] = {**old_values, **missing}
        existing["source"] = existing.get("source") or source
        data[day] = existing
    else:
        data[day] = {
            "effective_date": day, "timezone": "Asia/Shanghai",
            "captured_at": captured_at, "source": source, "values": values,
        }
    atomic_write_snapshot("workbuddy_credit_cap.json", data,
                          production_relative="workbuddy_credit_cap.json",
                          switch="NAS_MONITOR_CUTOVER_BALANCE")
    return len(values)


def _apply_wb_daily_cap(workbuddy, now_local):
    local_now = now_local.astimezone(SHANGHAI)
    today = local_now.date().isoformat()
    try:
        _roll_cap_shadow(workbuddy, local_now, source="lazy", effective_date=today, merge_missing=True)
    except Exception:
        pass
    data = _load_cap_cutover()
    record = data.get(today) if isinstance(data.get(today), dict) else None
    values = record.get("values") if isinstance(record, dict) else {}
    if not isinstance(values, dict):
        values = {}
    captured_at = local_now.isoformat(timespec="seconds")
    for entry in (workbuddy or {}).get("accounts") or []:
        uid = str(entry.get("uid") or "")
        raw_value = values.get(uid)
        if isinstance(raw_value, dict):
            base_value = raw_value.get("credits")
            raw_source = raw_value.get("source") or record.get("source")
            raw_captured_at = raw_value.get("captured_at")
        else:
            base_value = raw_value
            raw_source = record.get("source") if isinstance(record, dict) else None
            raw_captured_at = record.get("captured_at") if isinstance(record, dict) else None
        if uid in values and _valid_credit(base_value):
            entry["daily_cap"] = int(base_value)
            entry["daily_cap_source"] = "lazy" if raw_source == "lazy" else "baseline"
            entry["daily_cap_effective_date"] = today
            entry["daily_cap_captured_at"] = raw_captured_at
            entry["daily_cap_timezone"] = record.get("timezone", "Asia/Shanghai")
            entry["daily_cap_record_source"] = raw_source
        elif _valid_credit(entry.get("credits")):
            entry["daily_cap"] = int(entry["credits"])
            entry["daily_cap_source"] = "seed"
            entry["daily_cap_effective_date"] = None
            entry["daily_cap_captured_at"] = captured_at
            entry["daily_cap_timezone"] = "Asia/Shanghai"
            entry["daily_cap_record_source"] = "seed_current_uid"
        else:
            entry["daily_cap"] = None
            entry["daily_cap_source"] = None
            entry["daily_cap_effective_date"] = None
            entry["daily_cap_captured_at"] = None
            entry["daily_cap_timezone"] = "Asia/Shanghai"
            entry["daily_cap_record_source"] = None
    return workbuddy



WORKBUDDY_STATE_FILE = os.environ.get(
    "WORKBUDDY_STATE",
    os.environ.get("WORKBUDDY_STATE_FILE", ""),
)


def _workbuddy_state_fallback(uids):
    """billing API 全挂时的兜底：读 workbuddy2api 的 state.json。

    state.json 的 credits 是签到/任务积分余额（整数），与 billing 的 token
    额度不是一个口径，但总比前端显示「积分未知」好。返回 {uid: credits}。
    """
    try:
        with open(WORKBUDDY_STATE_FILE, encoding="utf-8") as stream:
            data = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}
    accounts = data.get("accounts") or {}
    result = {}
    for uid in uids:
        entry = accounts.get(uid)
        if isinstance(entry, dict):
            credits = entry.get("credits")
            if isinstance(credits, (int, float)) and not isinstance(credits, bool):
                result[uid] = int(credits)
    return result


def _workbuddy_credit():
    """读 auths 目录 + 调 billing API，聚合每账号自然月累计消耗。"""
    try:
        files = []
        for directory in _WORKBUDDY_AUTH_DIRS:
            files.extend(sorted(glob.glob(os.path.join(directory, "workbuddy-*.json"))))
        if not files:
            return None, "auths 目录无账号文件"
        accounts = {}
        now = datetime.now()
        for f in files:
            try:
                d = _read_json(f)
            except Exception:
                continue
            a = d.get("auth") or {}
            acc = d.get("account") or {}
            uid = acc.get("uid") or ""
            token = a.get("accessToken") or ""
            if not uid or not token:
                continue
            domain = (a.get("domain") or "").strip()
            realm = (a.get("realm") or "").strip().lower()
            is_global = realm == "global" or domain == "workbuddy.ai" or domain.endswith(".workbuddy.ai")
            base = "https://www.workbuddy.ai" if is_global else "https://www.codebuddy.cn"
            path = "/billing/meter/get-user-resource" if is_global else "/v2/billing/meter/get-user-resource"
            body = {
                "PageNumber": 1, "PageSize": 100, "ProductCode": "p_tcaca",
                "Status": [0, 3],
                "PackageEndTimeRangeBegin": now.strftime("%Y-%m-%d %H:%M:%S"),
                "PackageEndTimeRangeEnd": (now + timedelta(days=365 * 101)).strftime("%Y-%m-%d %H:%M:%S"),
            }
            headers = {
                "Authorization": "Bearer " + token,
                "Accept": "application/json",
                "Content-Type": "application/json",
                "X-User-Id": uid,
                "User-Agent": "CLI/2.63.2 CodeBuddy/2.63.2",
                "Origin": base,
                "Referer": base + "/",
            }
            if acc.get("enterpriseId"):
                headers["X-Enterprise-Id"] = acc["enterpriseId"]
                headers["X-Tenant-Id"] = acc["enterpriseId"]
            if domain:
                headers["X-Domain"] = domain
            try:
                with urllib.request.urlopen(urllib.request.Request(base + path, data=json.dumps(body).encode(), headers=headers, method="POST"), timeout=20) as r:
                    env = json.loads(r.read().decode("utf-8", errors="replace"))
            except Exception:
                continue
            if env.get("code") != 0:
                continue
            pkgs = (((env.get("data") or {}).get("Response") or {}).get("Data") or {}).get("Accounts") or []
            remain = used = size = 0
            for p in pkgs:
                cyc_size = p.get("CycleCapacitySize") or 0
                if cyc_size > 0:
                    r = p.get("CycleCapacityRemain") or 0
                    s = cyc_size
                    u = p.get("CycleCapacityUsed") or 0
                else:
                    r = p.get("CapacityRemain") or 0
                    s = p.get("CapacitySize") or 0
                    u = p.get("CapacityUsed") or 0
                if r < 0:
                    r = 0
                if s < r:
                    s = r
                if u == 0 and s > r:
                    u = s - r
                remain += r
                used += u
                size += s
            if size > 0 and size - remain > used:
                used = size - remain
            accounts[uid] = {"used": used, "remain": remain, "size": size}
        return accounts, None
    except Exception as exc:
        return None, str(exc)[:120]


def _groups():
    """按出口分组返回 WorkBuddy 实例；未配置 base 的分组自动跳过。

    WorkBuddy 是可选插件，开源用户不配置 WORKBUDDY_BASE 时该模块不产生任何请求。
    """
    groups = (
        {"id": "default", "name": "默认出口", "base": WORKBUDDY_BASE, "config": WORKBUDDY_CONFIG},
        {"id": "wan1", "name": "WAN1 出口", "base": WORKBUDDY_WAN1_BASE, "config": WORKBUDDY_WAN1_CONFIG},
    )
    return tuple(group for group in groups if group["base"])


def toggle_account(uid, disabled, group_id=None):
    """调 workbuddy2api admin 接口切换 manual_disabled，返回 (success, result)。"""
    groups = {group["id"]: group for group in _groups()}
    group = groups.get(group_id or "default")
    if not group:
        raise RuntimeError("未知 WorkBuddy 分组: %s" % group_id)
    cfg = _read_json(group["config"])
    key = cfg.get("api_key") or ""
    if not key:
        raise RuntimeError("%s 未找到 api_key" % group["name"])
    action = "disable" if disabled else "enable"
    url = group["base"] + "/admin/accounts/" + uid + "/" + action
    body = {"reason": "nas-monitor"} if disabled else {}
    try:
        result = _http_json(url, token=key, data=body, extra_headers={"Accept": "application/json"})
        if isinstance(result, dict) and result.get("changed"):
            threading.Thread(target=_refresh_snapshots, daemon=True, name="workbuddy-toggle-refresh").start()
        return True, result
    except Exception as exc:
        return False, str(exc)[:300]


def _refresh_snapshots():
    try:
        collect()
        from .balance_combined import collect as collect_balance_combined
        collect_balance_combined()
    except Exception:
        pass


def _collect_group(group):
    cfg = _read_json(group["config"])
    key = cfg.get("api_key") or ""
    if not key:
        raise RuntimeError("%s 未找到 api_key" % group["name"])
    payload = _http_json(group["base"] + "/status", token=key, extra_headers={"Accept": "application/json"})
    if isinstance(payload, dict) and payload.get("error"):
        raise RuntimeError(str(payload["error"])[:120])
    if not isinstance(payload, dict):
        raise RuntimeError("响应格式异常")
    return payload


def collect():
    group_results = []
    group_errors = []
    all_accounts = []

    for group in _groups():
        try:
            payload = _collect_group(group)
        except Exception as exc:
            group_errors.append("%s: %s" % (group["name"], str(exc)[:120]))
            continue

        accounts_raw = payload.get("accounts") or []
        credit_map, credit_err = _workbuddy_credit_cached()
        state_fallback = None
        if not credit_map:
            state_fallback = _workbuddy_state_fallback([a.get("uid") for a in accounts_raw if isinstance(a, dict)])
        norm = []
        for a in accounts_raw:
            if not isinstance(a, dict):
                continue
            uid = a.get("uid") or ""
            entry = {
                "uid": uid,
                "nickname": a.get("nickname") or a.get("name") or "",
                "credits": a.get("credits"),
                "credits_total": a.get("credits_total"),
                "realm": a.get("realm"),
                "cooling": bool(a.get("cooling")),
                "manual_disabled": bool(a.get("manual_disabled")),
                "disabled": bool(a.get("disabled")) or bool(a.get("manual_disabled")),
                "in_flight": a.get("in_flight"),
                "success_count": a.get("success_count"),
                "last_success": a.get("last_success"),
                "last_err": a.get("last_err"),
                "breaker_fails": a.get("breaker_fails"),
                "group_id": group["id"],
            }
            if credit_map and uid in credit_map:
                entry["month_used"] = credit_map[uid]["used"]
                entry["today_used"] = _today_used(credit_map[uid]["used"], "wb:" + uid)
                # 上游 /status 对新账号可能返回 0（实例本地额度尚未同步），
                # 用 billing 的剩余额度兜底，避免前端显示 0 / 0。
                remain = credit_map[uid].get("remain")
                if not entry.get("credits") and isinstance(remain, (int, float)) and remain > 0:
                    entry["credits"] = int(remain)
                    entry["credit_source"] = "billing_remain"
            elif state_fallback and uid in state_fallback:
                entry["credits"] = state_fallback[uid]
                entry["credit_source"] = "state_fallback"
            norm.append(entry)
        norm.sort(key=lambda x: (x["disabled"], x["cooling"],
                                 -(x["credits"] if isinstance(x["credits"], (int, float)) else -1)))

        # 注入 daily_cap（读 shadow workbuddy_credit_cap.json）
        workbuddy_for_cap = {"accounts": norm}
        _apply_wb_daily_cap(workbuddy_for_cap, datetime.now(SHANGHAI))

        realm_raw = payload.get("realm_totals") or payload.get("realm_servable") or {}
        realm_servable = None
        if isinstance(realm_raw, dict) and realm_raw:
            realm_servable = {}
            for k, v in realm_raw.items():
                if isinstance(v, dict):
                    realm_servable[str(k)] = (v.get("healthy") or 0) > 0
                else:
                    realm_servable[str(k)] = bool(v)

        group_result = {
            "id": group["id"],
            "name": group["name"],
            "total": payload.get("total", len(norm)),
            "healthy": payload.get("healthy", len(norm) - sum(1 for x in norm if x["disabled"] or x["cooling"])),
            "cooling": payload.get("cooling", sum(1 for x in norm if x["cooling"])),
            "disabled": payload.get("disabled", sum(1 for x in norm if x["disabled"])),
            "realm_servable": realm_servable,
            "accounts": norm,
        }
        if credit_err and not credit_map:
            group_result["credit_error"] = credit_err
        group_results.append(group_result)
        all_accounts.extend(norm)

    if not group_results:
        raise RuntimeError("；".join(group_errors) or "全部 WorkBuddy 分组不可用")

    realm_servable = {}
    for group_result in group_results:
        if isinstance(group_result.get("realm_servable"), dict):
            for realm, servable in group_result["realm_servable"].items():
                realm_servable[realm] = realm_servable.get(realm, False) or bool(servable)

    result = {
        "updated_at": datetime.now(SHANGHAI).strftime("%Y-%m-%dT%H:%M:%S+08:00"),
        "total": sum(x.get("total", len(x.get("accounts") or [])) for x in group_results),
        "healthy": sum(x.get("healthy", 0) for x in group_results),
        "cooling": sum(x.get("cooling", 0) for x in group_results),
        "disabled": sum(x.get("disabled", 0) for x in group_results),
        "realm_servable": realm_servable or None,
        "in_flight_full": None,
        "accounts": all_accounts,
        "groups": group_results,
        "source": "backend-shadow",
    }
    if group_errors:
        result["group_errors"] = group_errors
    atomic_write_json("workbuddy_status.json", result)
    # 立即重跑合并快照，避免 balance_combined 独立调度相位滞后导致
    # App 读到的 balance_snapshot.json 晚一拍（曾出现约 100s 延迟）。
    threading.Thread(target=_refresh_combined_snapshot, daemon=True,
                     name="workbuddy-merge-refresh").start()
    return result


def _refresh_combined_snapshot():
    try:
        from .balance_combined import collect as collect_balance_combined
        collect_balance_combined()
    except Exception:
        pass


def _workbuddy_credit_cached():
    """带 10 分钟内存缓存的 _workbuddy_credit，避免每次 collect 都串行调多次外部 API。

    账号增删时（auths 目录文件指纹变化）立即失效缓存，避免新增账号在
    10 分钟内积分显示为 0、删除账号后仍被计入。
    """
    import time as _time
    global _AUTH_FINGERPRINT
    now = _time.time()
    fingerprint = _auth_dirs_fingerprint()
    changed = _AUTH_FINGERPRINT is not None and fingerprint != _AUTH_FINGERPRINT
    if _CREDIT_CACHE["value"] is not None and not changed and now - _CREDIT_CACHE["ts"] < _CREDIT_CACHE_TTL:
        return _CREDIT_CACHE["value"]
    value = _workbuddy_credit()
    _CREDIT_CACHE["ts"] = now
    _CREDIT_CACHE["value"] = value
    _AUTH_FINGERPRINT = fingerprint
    return value


# ── 运行态实时刷新（「在飞」等瞬时字段）──────────────────────────────────
# 背景：workbuddy_status 采集周期 600s，而 in_flight 是上游 pool 的瞬时并发
# 计数（见上游 internal/pool/entry.go）。快照里的 in_flight 只在采集那一刻
# 有效，App 每 30s 拉到的其实是同一份旧值，导致「实际在用但看不到在飞」。
# 这里按需直连上游 /status，只覆盖运行态字段，不重算积分与分组，成本极低。
_RUNTIME_CACHE = {"ts": 0.0, "value": None}
_RUNTIME_CACHE_TTL = 3.0  # 秒；同一秒内多次请求共用一次上游查询

# 会被实时覆盖的运行态字段（积分/分组等慢变字段一律不动）。
_RUNTIME_KEYS = (
    "in_flight", "cooling", "disabled", "manual_disabled", "breaker_fails",
    "success_count", "last_success", "last_err",
)


def _runtime_snapshot_cached():
    """直连各分组上游 /status，取 {uid: {运行态字段}}；带 3s 内存缓存。

    失败时返回 None，调用方保留快照原值（降级不报错）。
    """
    import time as _time
    now = _time.time()
    if _RUNTIME_CACHE["value"] is not None and now - _RUNTIME_CACHE["ts"] < _RUNTIME_CACHE_TTL:
        return _RUNTIME_CACHE["value"]
    result = {}
    ok = False
    for group in _groups():
        try:
            cfg = _read_json(group["config"])
            key = cfg.get("api_key") or ""
            if not key:
                continue
            payload = _http_json(group["base"] + "/status", token=key,
                                 extra_headers={"Accept": "application/json"}, timeout=5)
            if not isinstance(payload, dict):
                continue
            ok = True
            for account in payload.get("accounts") or []:
                if not isinstance(account, dict):
                    continue
                uid = account.get("uid") or ""
                if not uid:
                    continue
                result[uid] = {name: account.get(name) for name in _RUNTIME_KEYS}
        except Exception:
            continue
    if not ok:
        _RUNTIME_CACHE["ts"] = now
        _RUNTIME_CACHE["value"] = None
        return None
    _RUNTIME_CACHE["ts"] = now
    _RUNTIME_CACHE["value"] = result
    return result


def apply_runtime_overrides(payload):
    """把实时运行态字段覆盖到 balance 快照的 workbuddy 段（原地修改并返回）。

    只覆盖 in_flight 等运行态字段；uid 匹配不到（新账号尚未进快照）时跳过，
    等下一轮完整采集补全，避免出现「有账号没积分」的中间态。
    """
    if not isinstance(payload, dict):
        return payload
    workbuddy = payload.get("workbuddy")
    if not isinstance(workbuddy, dict):
        return payload
    runtime = _runtime_snapshot_cached()
    if not runtime:
        return payload
    for group in workbuddy.get("groups") or []:
        if not isinstance(group, dict):
            continue
        for account in group.get("accounts") or []:
            if not isinstance(account, dict):
                continue
            fresh = runtime.get(account.get("uid") or "")
            if not fresh:
                continue
            _apply_runtime_fields(account, fresh)
    # 顶层 accounts 与 groups 是同一批账号的两份投影，需同步覆盖。
    for account in workbuddy.get("accounts") or []:
        if not isinstance(account, dict):
            continue
        fresh = runtime.get(account.get("uid") or "")
        if not fresh:
            continue
        _apply_runtime_fields(account, fresh)
    _attach_recent_activity(workbuddy)
    return payload


def _attach_recent_activity(workbuddy):
    """把「最近被哪个账号处理」写进快照，供 App 直接展示。

    上游日志是请求结束时才写出的，所以这里给的是「刚刚用过谁」；配合
    in_flight（正在处理中）即可判断此刻在跑哪个账号。采集失败不影响返回。
    """
    try:
        from . import workbuddy_activity
    except Exception:
        return
    for group in workbuddy.get("groups") or []:
        if not isinstance(group, dict):
            continue
        group_id = group.get("id") or "default"
        try:
            activity = workbuddy_activity.collect(minutes=30, group_id=group_id)
        except Exception:
            continue
        recent = activity.get("accounts") or []
        group["recent_accounts"] = recent
        if recent:
            group["last_account"] = recent[0]
        by_uid = {}
        for item in recent:
            uid = item.get("uid") or ""
            if uid:
                by_uid[uid] = item
        for account in group.get("accounts") or []:
            if not isinstance(account, dict):
                continue
            item = by_uid.get(account.get("uid") or "")
            if item:
                account["recent_requests"] = item.get("requests")
                account["recent_last_ts"] = item.get("last_ts")


def _apply_runtime_fields(account, fresh):
    """把一条实时运行态写入快照账号对象。

    disabled 在快照里是 `上游 disabled OR manual_disabled` 的合成值，
    不能直接用原始字段覆盖，否则手动停用的账号会显示成可用。
    """
    for name in _RUNTIME_KEYS:
        if name in fresh:
            account[name] = fresh[name]
    account["disabled"] = bool(fresh.get("disabled")) or bool(fresh.get("manual_disabled"))
