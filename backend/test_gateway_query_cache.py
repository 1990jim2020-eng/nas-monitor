import io
import threading
import unittest
import urllib.error
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock, patch

import gateway_api


class GatewayQueryTests(unittest.TestCase):
    def setUp(self):
        for replacement in (
            patch.object(gateway_api, "NEW_API_URL", "http://upstream.example.invalid"),
            patch.object(gateway_api, "_read_cache", OrderedDict()),
            patch.object(gateway_api, "_retry_at", 0.0),
        ):
            replacement.start()
            self.addCleanup(replacement.stop)

    def log(self, created_at, token="A", identifier=1):
        return {"created_at": created_at, "token_name": token, "id": identifier, "type": 2}

    def test_upstream_ignoring_time_filter_stops_at_old_records(self):
        batch = [self.log(9900, identifier=index) for index in range(99)] + [self.log(6399)]
        with patch.object(gateway_api.time, "time", return_value=10000), \
             patch.object(gateway_api, "request", return_value={"items": batch, "total": 9999}) as request:
            result = gateway_api.logs()
        self.assertEqual(99, result["total"])
        self.assertFalse(result["truncated"])
        request.assert_called_once_with("GET", "/api/log/?p=0&page_size=100&start_timestamp=6400")

    def test_tokens_filters_and_pages_share_one_scan(self):
        batch = [self.log(9800, "A"), self.log(9700, "B"), self.log(9600, "A", 3)]
        with patch.object(gateway_api.time, "time", return_value=10000), \
             patch.object(gateway_api, "request", return_value={"items": batch, "total": 3}) as request:
            self.assertEqual(["A", "B"], gateway_api.log_tokens()["tokens"])
            self.assertEqual(3, gateway_api.logs()["total"])
            result = gateway_api.logs(1, 1, "A")
            self.assertEqual(2, result["total"])
            self.assertEqual(3, result["items"][0]["id"])
            self.assertEqual(3600, result["window_seconds"])
            request.assert_called_once()

    def test_cache_rechecks_sliding_hour_boundary(self):
        with patch.object(gateway_api.time, "time", return_value=10000) as clock, \
             patch.object(gateway_api, "request", return_value={"items": [self.log(6400)], "total": 1}) as request:
            self.assertEqual(1, gateway_api.logs()["total"])
            clock.return_value = 10001
            self.assertEqual(0, gateway_api.logs()["total"])
            self.assertEqual([], gateway_api.log_tokens()["tokens"])
            request.assert_called_once()

    def test_scan_limit_is_explicit_when_hour_is_busy(self):
        batch = [self.log(9900, identifier=index) for index in range(100)]
        with patch.object(gateway_api.time, "time", return_value=10000), \
             patch.object(gateway_api, "request", return_value={"items": batch, "total": 9999}) as request:
            result = gateway_api.logs()
        self.assertTrue(result["truncated"])
        self.assertEqual(1000, result["total"])
        self.assertEqual(1000, result["scan_limit"])
        self.assertEqual(10, request.call_count)

    def test_single_flight_and_defensive_copy(self):
        started, release = threading.Event(), threading.Event()

        def build():
            started.set()
            self.assertTrue(release.wait(3))
            return {"items": [1]}

        loader = Mock(side_effect=build)
        with ThreadPoolExecutor(max_workers=6) as pool:
            futures = [pool.submit(gateway_api._cached_read, "shared", loader) for _ in range(6)]
            self.assertTrue(started.wait(2))
            release.set()
            results = [future.result(3) for future in futures]
        loader.assert_called_once()
        results[0]["items"].append(2)
        self.assertEqual([1], gateway_api._cached_read("shared", loader)["items"])

    def test_expired_cache_refreshes(self):
        loader = Mock(side_effect=[{"value": 1}, {"value": 2}])
        with patch.object(gateway_api.time, "monotonic", return_value=100) as clock:
            self.assertEqual(1, gateway_api._cached_read("shared", loader)["value"])
            clock.return_value = 159
            self.assertEqual(1, gateway_api._cached_read("shared", loader)["value"])
            clock.return_value = 160
            self.assertEqual(2, gateway_api._cached_read("shared", loader)["value"])
        self.assertEqual(2, loader.call_count)

    def test_failures_are_not_cached_as_success(self):
        loader = Mock(side_effect=[RuntimeError("unavailable"), {"ok": True}])
        with self.assertRaises(RuntimeError):
            gateway_api._cached_read("shared", loader)
        self.assertTrue(gateway_api._cached_read("shared", loader)["ok"])

    def test_writes_invalidate_cache_even_if_readback_fails(self):
        gateway_api._cached_read("overview", lambda: {"old": True})
        with patch.object(gateway_api, "_request_upstream", side_effect=RuntimeError("uncertain")):
            with self.assertRaises(RuntimeError):
                gateway_api.request("POST", "/api/channel/1/status", {"status": 2})
        self.assertFalse(gateway_api._read_cache)

    def test_overview_retains_24_hour_statistics(self):
        with patch.object(gateway_api.time, "time", return_value=10000), \
             patch.object(gateway_api, "_build_overview", return_value={"window": "24h"}) as build:
            self.assertEqual("24h", gateway_api.overview()["window"])
            gateway_api.overview()
            build.assert_called_once()

    def test_rate_limit_cooldown_prevents_upstream_requests(self):
        error = urllib.error.HTTPError("http://upstream", 429, "limited", {"Retry-After": "20"}, io.BytesIO(b"limited"))
        with patch.object(gateway_api, "_token", return_value="test"), \
             patch.object(gateway_api.time, "monotonic", return_value=100) as clock, \
             patch.object(gateway_api.urllib.request, "urlopen", side_effect=error) as upstream:
            with self.assertRaises(gateway_api.GatewayRateLimited) as raised:
                gateway_api.request("GET", "/api/log/")
            self.assertEqual(20, raised.exception.retry_after)
            clock.return_value = 110
            with self.assertRaises(gateway_api.GatewayRateLimited) as raised:
                gateway_api.request("GET", "/api/channel/")
            self.assertEqual(10, raised.exception.retry_after)
            upstream.assert_called_once()
            response = Mock()
            response.__enter__ = Mock(return_value=response)
            response.__exit__ = Mock(return_value=False)
            response.read.return_value = b'{"success":true,"data":{}}'
            upstream.side_effect = None
            upstream.return_value = response
            clock.return_value = 120
            self.assertEqual({}, gateway_api.request("GET", "/api/log/"))

    def test_performance_includes_logs_older_than_one_hour(self):
        current = Mock()
        current.timestamp.return_value = 100000
        batch = [self.log(90000)]
        with patch.object(gateway_api.datetime, "datetime") as clock, \
             patch.object(gateway_api, "request", return_value={"items": batch}) as request:
            clock.now.return_value = current
            result = gateway_api._performance_logs(max_pages=1)
        self.assertEqual(batch, result)
        request.assert_called_once_with("GET", "/api/log/?p=0&page_size=100&start_timestamp=13600")

    def test_rate_limit_invalid_header_uses_default(self):
        error = urllib.error.HTTPError("http://upstream", 429, "limited", {"Retry-After": "invalid"}, io.BytesIO())
        with patch.object(gateway_api, "_token", return_value="test"), \
             patch.object(gateway_api.urllib.request, "urlopen", side_effect=error):
            with self.assertRaises(gateway_api.GatewayRateLimited) as raised:
                gateway_api.request("GET", "/api/log/")
        self.assertEqual(60, raised.exception.retry_after)

    def test_other_upstream_errors_are_not_rate_limits(self):
        error = urllib.error.HTTPError("http://upstream", 500, "failure", {}, io.BytesIO())
        with patch.object(gateway_api, "_token", return_value="test"), \
             patch.object(gateway_api.urllib.request, "urlopen", side_effect=error):
            with self.assertRaises(urllib.error.HTTPError):
                gateway_api.request("GET", "/api/log/")


if __name__ == "__main__":
    unittest.main()
