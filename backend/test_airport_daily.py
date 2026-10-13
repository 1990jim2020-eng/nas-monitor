import json
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

from airport_daily import SHANGHAI, update_usage
import api_server


class AirportDailyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "daily.json"

    def sample(self, value, clock="2026-09-28T12:00:00", key="a", total=100):
        return update_usage(key, value * 1024**3, total * 1024**3,
                            datetime.fromisoformat(clock).replace(tzinfo=SHANGHAI), self.path)

    def test_initial_baseline_and_persisted_increment(self):
        first = self.sample(10)
        self.assertEqual(0, first["today_used_gb"])
        self.assertTrue(first["today_usage_partial"])
        self.assertEqual(2, self.sample(12, "2026-09-28T12:01:00")["today_used_gb"])
        self.assertEqual(2, self.sample(12, "2026-09-28T12:02:00")["today_used_gb"])

    def test_midnight_rollover_and_next_day_does_not_keep_yesterday_usage(self):
        self.sample(10, "2026-09-28T23:58:00")
        self.sample(12, "2026-09-28T23:59:00")
        result = self.sample(13, "2026-09-29T00:00:00")
        self.assertEqual(1, result["today_used_gb"])
        self.assertFalse(result["today_usage_partial"])
        self.assertEqual("2026-09-29", result["today_usage_date"])

    def test_missing_midnight_baseline_does_not_count_previous_days(self):
        self.sample(10)
        result = self.sample(30, "2026-09-30T12:00:00")
        self.assertEqual(0, result["today_used_gb"])
        self.assertTrue(result["today_usage_partial"])

    def test_reset_or_plan_change_keeps_known_usage_without_spike(self):
        self.sample(10)
        self.sample(12)
        self.assertEqual(2, self.sample(1)["today_used_gb"])
        self.assertEqual(3, self.sample(2)["today_used_gb"])
        result = self.sample(50, total=200)
        self.assertEqual(3, result["today_used_gb"])
        self.assertTrue(result["today_usage_partial"])

    def test_concurrent_identical_samples_and_separate_providers(self):
        self.sample(10)
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: self.sample(12), range(12)))
        self.assertTrue(all(r["today_used_gb"] == 2 for r in results))
        self.assertEqual(0, self.sample(99, key="b")["today_used_gb"])
        self.assertEqual(2, self.sample(12)["today_used_gb"])

    def test_corrupt_file_is_not_overwritten(self):
        self.path.write_text("broken")
        with self.assertRaises(json.JSONDecodeError):
            self.sample(10)
        self.assertEqual("broken", self.path.read_text())

    def test_api_fallback_does_not_fabricate_daily_zero(self):
        source = Path(self.tmp.name) / "airport.yaml"
        source.write_text("剩余流量：80 GB")
        with patch.object(api_server, "_AIRPORT_FILES", {"airport1": str(source)}), \
             patch.object(api_server, "_AIRPORT_CACHE", {"ts": 0, "data": None}), \
             patch.object(api_server, "_mihomo_secret", return_value="test"), \
             patch.object(api_server, "_provider_subscription_info", return_value=None), \
             patch.object(api_server, "update_airport_daily_usage") as update:
            item = api_server.get_airport_traffic()["items"][0]
            self.assertIsNone(item["today_used_gb"])
            self.assertEqual(80, item["remaining_gb"])
            update.assert_not_called()

    def test_api_uses_exact_counter_and_returns_new_fields(self):
        source = Path(self.tmp.name) / "airport.yaml"
        source.write_text("剩余流量：80 GB")
        expected = {"today_used_gb": 1.25, "today_usage_partial": False,
                    "today_usage_date": "2026-09-28"}
        with patch.object(api_server, "_AIRPORT_FILES", {"airport1": str(source)}), \
             patch.object(api_server, "_AIRPORT_CACHE", {"ts": 0, "data": None}), \
             patch.object(api_server, "_mihomo_secret", return_value="test"), \
             patch.object(api_server, "_provider_subscription_info", return_value={"Total": 1000, "Upload": 10, "Download": 20}), \
             patch.object(api_server, "update_airport_daily_usage", return_value=expected) as update:
            item = api_server.get_airport_traffic()["items"][0]
            update.assert_called_once_with("airport1", 30, 1000)
            self.assertEqual(1.25, item["today_used_gb"])
            self.assertFalse(item["today_usage_partial"])


if __name__ == "__main__":
    unittest.main()
