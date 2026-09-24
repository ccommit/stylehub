import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

from sim.api import Api, ApiError, check_allowed


class FakeStyleHub(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if self.path == "/api/v1/users/login":
            self._send(200, {"userId": 7}, cookie="SESSION=abc; Path=/; HttpOnly")
        elif self.path == "/api/v1/users/sign-up":
            self._send(409, {"code": "U001", "message": "이미 가입된 이메일"})
        elif self.path == "/api/v1/orders":
            self._send(201, {"orderId": 1, "pgOrderId": "ORD-1", "finalAmount": 1000})
        else:
            self._send(404, {"code": "NOT_FOUND"})

    def do_GET(self):
        if self.path == "/api/v1/users/me/addresses":
            if "SESSION=abc" in (self.headers.get("Cookie") or ""):
                self._send(200, [{"addressId": 3}])
            else:
                self._send(401, {"code": "A001", "message": "로그인이 필요합니다"})
        elif self.path.startswith("/api/v1/payments/success?"):
            self._send(200, {"echo": self.path})
        elif self.path == "/api/v1/proxy-error":
            self._send_html(502, "<html><body>BAD_GATEWAY_FROM_PROXY</body></html>")
        else:
            self._send(404, {"code": "NOT_FOUND"})

    def _send(self, status, payload, cookie=None):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(data)

    def _send_html(self, status, html):
        data = html.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


class ApiTest(unittest.TestCase):

    def setUp(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), FakeStyleHub)
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.base = f"http://127.0.0.1:{server.server_address[1]}"

    def test_session_cookie_from_login_is_sent_on_next_request(self):
        api = Api(self.base)
        self.assertEqual(api.login("a@sim.stylehub.test", "pw")["userId"], 7)
        self.assertEqual(api.addresses(), [{"addressId": 3}])

    def test_request_without_session_raises_api_error_with_code(self):
        with self.assertRaises(ApiError) as caught:
            Api(self.base).addresses()
        self.assertEqual((caught.exception.status, caught.exception.code), (401, "A001"))

    def test_conflict_is_reported_with_server_error_code(self):
        with self.assertRaises(ApiError) as caught:
            Api(self.base).sign_up({"name": "simb00001", "email": "x@sim.stylehub.test", "birthDate": "1995-05-05"}, "Sim000001!a")
        self.assertEqual((caught.exception.status, caught.exception.code), (409, "U001"))

    def test_pay_success_sends_pg_order_id_as_order_id_query(self):
        echoed = Api(self.base).pay_success("mock_1", "ORD-20260919-abc", 39000)["echo"]
        self.assertIn("paymentKey=mock_1", echoed)
        self.assertIn("orderId=ORD-20260919-abc", echoed)
        self.assertIn("amount=39000", echoed)

    def test_place_order_posts_to_orders_path(self):
        order = Api(self.base).place_order(3, [{"productOptionId": 9, "quantity": 1}])
        self.assertEqual(order, {"orderId": 1, "pgOrderId": "ORD-1", "finalAmount": 1000})

    def test_non_json_error_body_is_reported_with_snippet_message(self):
        with self.assertRaises(ApiError) as caught:
            Api(self.base).request("GET", "/proxy-error")
        self.assertEqual(caught.exception.status, 502)
        self.assertEqual(caught.exception.code, "")
        self.assertIn("BAD_GATEWAY_FROM_PROXY", caught.exception.message)

    def test_construction_checks_allow_list(self):
        # Api.__init__ 이 check_allowed 를 직접 부르므로, 진입점을 거치지 않은 임시 호출도 막힌다
        with mock.patch.dict(os.environ, {"SIM_ALLOWED_HOSTS": ""}):
            with self.assertRaises(SystemExit):
                Api("http://example.invalid:8080")


class CheckAllowedTest(unittest.TestCase):

    def test_local_hosts_are_allowed(self):
        check_allowed("http://localhost:18080")
        check_allowed("http://127.0.0.1:8080")

    def test_other_host_is_refused_unless_listed(self):
        with mock.patch.dict(os.environ, {"SIM_ALLOWED_HOSTS": ""}):
            with self.assertRaises(SystemExit):
                check_allowed("http://203.0.113.10:8080")
        with mock.patch.dict(os.environ, {"SIM_ALLOWED_HOSTS": "203.0.113.10"}):
            check_allowed("http://203.0.113.10:8080")

    def test_mixed_case_allowed_host_entry_matches(self):
        with mock.patch.dict(os.environ, {"SIM_ALLOWED_HOSTS": "Staging.Example.COM"}):
            check_allowed("http://staging.example.com:8080")

    def test_allowed_host_entry_with_port_matches(self):
        with mock.patch.dict(os.environ, {"SIM_ALLOWED_HOSTS": "203.0.113.10:8080"}):
            check_allowed("http://203.0.113.10:8080")

    def test_schemeless_base_url_raises_clear_error(self):
        with self.assertRaises(SystemExit) as caught:
            check_allowed("203.0.113.10:8080")
        self.assertIn("http://", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
