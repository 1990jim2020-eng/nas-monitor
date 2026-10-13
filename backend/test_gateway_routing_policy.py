import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import gateway_api


class FakeNewApi:
    def __init__(self):
        self.channels = {
            1: {"id": 1, "name": "A", "status": 1, "priority": 3, "weight": 2, "models": "m", "group": "default", "auto_ban": 0},
            2: {"id": 2, "name": "B", "status": 1, "priority": 2, "weight": 4, "models": "m", "group": "default", "auto_ban": 0},
            3: {"id": 3, "name": "C", "status": 2, "priority": 1, "weight": 1, "models": "x", "group": "default", "auto_ban": 1},
        }
        self.clears = 0
        self.fail_on = None

    def __call__(self, method, path, payload=None):
        if self.fail_on == (method, path):
            self.fail_on = None
            raise RuntimeError("injected failure")
        if method == "GET" and path == "/api/channel/?p=0&page_size=100":
            return {"items": [dict(c) for c in self.channels.values()]}
        if method == "GET" and path.startswith("/api/channel/"):
            return dict(self.channels[int(path.rstrip("/").split("/")[-1])])
        if method == "PUT" and path == "/api/channel/":
            item = self.channels[int(payload["id"])]
            item["priority"] = int(payload["priority"])
            item["weight"] = int(payload["weight"])
            return {}
        if method == "POST" and path.endswith("/status"):
            channel_id = int(path.split("/")[3])
            self.channels[channel_id]["status"] = int(payload["status"])
            return {}
        if method == "DELETE" and path == "/api/option/channel_affinity_cache?all=true":
            self.clears += 1
            return {"cleared": True}
        raise AssertionError((method, path, payload))


class RoutingPolicyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state_file = Path(self.tmp.name) / "routing-policy.json"
        self.fake = FakeNewApi()
        self.patches = [
            patch.object(gateway_api, "ROUTING_POLICY_STATE_FILE", self.state_file, create=True),
            patch.object(gateway_api, "request", self.fake),
        ]
        for item in self.patches:
            item.start()

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.tmp.cleanup()

    def test_preferred_snapshots_original_and_only_raises_target_priority(self):
        state = gateway_api.apply_routing_policy(2, "preferred")

        self.assertTrue(state["active"])
        self.assertEqual("preferred", state["mode"])
        self.assertEqual(2, state["channel_id"])
        self.assertEqual(100, self.fake.channels[2]["priority"])
        self.assertEqual(4, self.fake.channels[2]["weight"])
        self.assertEqual(3, self.fake.channels[1]["priority"])
        self.assertEqual(1, self.fake.clears)
        saved = json.loads(self.state_file.read_text())
        self.assertEqual(2, saved["channels"]["2"]["priority"])
        self.assertEqual(1, saved["channels"]["2"]["status"])

    def test_preferred_enables_disabled_target_then_restore_disables_it_again(self):
        state = gateway_api.apply_routing_policy(3, "preferred")
        self.assertTrue(state["active"])
        self.assertEqual(1, self.fake.channels[3]["status"])
        self.assertEqual(100, self.fake.channels[3]["priority"])
        gateway_api.restore_routing_policy()
        self.assertEqual(2, self.fake.channels[3]["status"])
        self.assertEqual(1, self.fake.channels[3]["priority"])

    def test_exclusive_enables_target_and_disables_every_other_channel(self):
        state = gateway_api.apply_routing_policy(3, "exclusive")

        self.assertEqual("exclusive", state["mode"])
        self.assertEqual(1, self.fake.channels[3]["status"])
        self.assertEqual(100, self.fake.channels[3]["priority"])
        self.assertEqual(2, self.fake.channels[1]["status"])
        self.assertEqual(2, self.fake.channels[2]["status"])
        self.assertEqual(1, self.fake.clears)

    def test_active_snapshot_cannot_be_overwritten(self):
        gateway_api.apply_routing_policy(1, "preferred")
        with self.assertRaisesRegex(RuntimeError, "恢复"):
            gateway_api.apply_routing_policy(2, "exclusive")
        self.assertEqual(1, json.loads(self.state_file.read_text())["channel_id"])

    def test_restore_returns_all_saved_fields_and_removes_snapshot(self):
        gateway_api.apply_routing_policy(2, "exclusive")
        restored = gateway_api.restore_routing_policy()

        self.assertFalse(restored["active"])
        self.assertEqual({1: (1, 3, 2), 2: (1, 2, 4), 3: (2, 1, 1)}, {
            cid: (c["status"], c["priority"], c["weight"]) for cid, c in self.fake.channels.items()
        })
        self.assertFalse(self.state_file.exists())
        self.assertEqual(2, self.fake.clears)

    def test_unknown_channel_and_mode_do_not_create_snapshot(self):
        with self.assertRaises(ValueError):
            gateway_api.apply_routing_policy(999, "preferred")
        with self.assertRaises(ValueError):
            gateway_api.apply_routing_policy(1, "bad")
        self.assertFalse(self.state_file.exists())

    def test_apply_failure_rolls_back_and_leaves_recovery_snapshot_if_needed(self):
        self.fake.fail_on = ("POST", "/api/channel/2/status")
        with self.assertRaisesRegex(RuntimeError, "应用失败"):
            gateway_api.apply_routing_policy(1, "exclusive")
        # Rollback succeeds after the one-shot failure is cleared by implementation retry path.
        self.assertEqual(1, self.fake.channels[1]["status"])
        self.assertEqual(1, self.fake.channels[2]["status"])


if __name__ == "__main__":
    unittest.main()
