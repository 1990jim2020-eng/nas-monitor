"""Run once inside the existing API container before recreating it with Compose."""
import os
from pathlib import Path
import tempfile

import api_server as api


def migrate():
    keys = {"ALIYUN_SECURITY_URL", "ALIYUN_SECURITY_TOKEN", "TENCENT_SECURITY_URL", "TENCENT_SECURITY_TOKEN"}
    env_path = Path(__file__).resolve().parent / ".env"
    original = env_path.read_text()
    with api._server_registry_lock:
        servers = api._load_server_registry()
        for server_id, prefix, fallback in (("aliyun", "ALIYUN", f"http://{api.ALIYUN_HOST}:8767" if api.ALIYUN_HOST else ""), ("tencent", "TENCENT", f"http://{api.TENCENT_HOST}:8767" if api.TENCENT_HOST else "")):
            if server_id in servers:
                servers[server_id].setdefault("security_url", os.environ.get(prefix + "_SECURITY_URL", fallback))
                servers[server_id].setdefault("security_token", os.environ.get(prefix + "_SECURITY_TOKEN", ""))
        api._save_server_registry_locked(servers)
    filtered = "".join(line for line in original.splitlines(keepends=True) if line.split("=", 1)[0].strip() not in keys)
    fd, name = tempfile.mkstemp(dir=env_path.parent, prefix=".env-migration-")
    try:
        with os.fdopen(fd, "w") as stream:
            stream.write(filtered)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, env_path)
    finally:
        if os.path.exists(name):
            os.unlink(name)
    print("Server registry migrated; recreate only nas-monitor-api to remove legacy container credentials.")


if __name__ == "__main__":
    migrate()
