import http.client
import json
import threading
import unittest
from unittest.mock import patch

import api_server


class RoutingPolicyHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = api_server.ThreadingHTTPServer(("127.0.0.1", 0), api_server.APIHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def request(self, method, path, payload=None, authorized=True):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {"Content-Type": "application/json"}
        if authorized:
            headers["Authorization"] = "Bearer test-monitor-token"
        body = None if payload is None else json.dumps(payload)
        conn.request(method, path, body=body, headers=headers)
        response = conn.getresponse()
        data = json.loads(response.read().decode())
        conn.close()
        return response.status, data

    def test_get_policy_state_requires_auth_and_returns_state(self):
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"), \
             patch.object(api_server.gateway_api, "routing_policy_state", return_value={"active": False, "mode": "normal"}):
            status, _ = self.request("GET", "/api/newapi/routing-policy", authorized=False)
            self.assertEqual(401, status)
            status, data = self.request("GET", "/api/newapi/routing-policy")
            self.assertEqual(200, status)
            self.assertFalse(data["active"])

    def test_apply_policy_validates_body_and_returns_readback(self):
        state = {"active": True, "mode": "preferred", "channel_id": 2}
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"), \
             patch.object(api_server.gateway_api, "apply_routing_policy", return_value=state) as apply:
            status, data = self.request("POST", "/api/newapi/routing-policy", {"channel_id": 2, "mode": "preferred"})
            self.assertEqual(200, status)
            self.assertEqual(state, data["policy"])
            apply.assert_called_once_with(2, "preferred")

    def test_restore_policy_returns_readback(self):
        state = {"active": False, "mode": "normal", "restored_count": 12}
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"), \
             patch.object(api_server.gateway_api, "restore_routing_policy", return_value=state) as restore:
            status, data = self.request("POST", "/api/newapi/routing-policy/restore", {})
            self.assertEqual(200, status)
            self.assertEqual(state, data["policy"])
            restore.assert_called_once_with()


    def test_gateway_queries_preserve_rate_limit_status(self):
        for path, function in [("overview", "overview"), ("logs", "logs"),
                               ("log-tokens", "log_tokens"), ("affinity", "affinity_state"),
                               ("models", "list_models")]:
            with self.subTest(path=path), \
                 patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"), \
                 patch.object(api_server.gateway_api, function,
                              side_effect=api_server.gateway_api.GatewayRateLimited(12)):
                status, data = self.request("GET", "/api/newapi/" + path)
                self.assertEqual(429, status)
                self.assertEqual("gateway_rate_limited", data["code"])
                self.assertEqual(12, data["retry_after"])

    def test_rate_limit_sets_retry_after_header(self):
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"), \
             patch.object(api_server.gateway_api, "logs", side_effect=api_server.gateway_api.GatewayRateLimited(9)):
            conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
            try:
                conn.request("GET", "/api/newapi/logs", headers={"Authorization": "Bearer test-monitor-token"})
                response = conn.getresponse()
                self.assertEqual(429, response.status)
                self.assertEqual("9", response.getheader("Retry-After"))
                response.read()
            finally:
                conn.close()

    def test_other_gateway_failure_stays_bad_gateway(self):
        with patch.object(api_server, "APP_API_TOKEN", "test-monitor-token"), \
             patch.object(api_server.gateway_api, "logs", side_effect=RuntimeError("unavailable")):
            status, data = self.request("GET", "/api/newapi/logs")
            self.assertEqual(502, status)
            self.assertEqual("unavailable", data["error"])


if __name__ == "__main__":
    unittest.main()
