import json
import math
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from mock_pg import ApprovalStore, Behavior, make_handler


class FixedRng:
    def __init__(self, value):
        self.value = value
        self.lognorm_args = None

    def random(self):
        return self.value

    def lognormvariate(self, mu, sigma):
        self.lognorm_args = (mu, sigma)
        return 0.0


class MockPgServerTest(unittest.TestCase):

    def start(self, rng_value):
        self.sleeps = []
        behavior = Behavior(300, 2000, decline_rate=0.02, loss_rate=0.002, loss_delay_s=12, rng=FixedRng(rng_value))
        self.store = ApprovalStore(10)
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(behavior, self.store, sleep=self.sleeps.append))
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.base = f"http://127.0.0.1:{server.server_address[1]}"

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method,
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def confirm(self):
        return self.call("POST", "/v1/payments/confirm", {"paymentKey": "mock_1", "orderId": "ORD-1", "amount": 39000})

    def test_approve_records_payment_found_as_done(self):
        self.start(rng_value=0.5)
        status, body = self.confirm()
        self.assertEqual((status, body["status"]), (200, "DONE"))
        status, body = self.call("GET", "/v1/payments/orders/ORD-1")
        self.assertEqual((status, body["status"], body["paymentKey"], body["totalAmount"]), (200, "DONE", "mock_1", 39000))

    def test_decline_returns_400_and_leaves_no_record(self):
        # 유실(0.002) 이상, 유실+거절(0.022) 미만이면 거절
        self.start(rng_value=0.01)
        status, body = self.confirm()
        self.assertEqual((status, body["code"]), (400, "REJECT_CARD_PAYMENT"))
        status, _ = self.call("GET", "/v1/payments/orders/ORD-1")
        self.assertEqual(status, 404)

    def test_loss_records_approval_before_delaying_response(self):
        self.start(rng_value=0.001)
        status, _ = self.confirm()
        self.assertEqual(status, 200)
        self.assertIn(12, self.sleeps)
        self.assertEqual(self.store.get("ORD-1")["paymentKey"], "mock_1")

    def test_cancel_returns_200(self):
        self.start(rng_value=0.5)
        status, body = self.call("POST", "/v1/payments/mock_1/cancel", {"cancelReason": "테스트"})
        self.assertEqual((status, body["status"], body["paymentKey"]), (200, "CANCELED", "mock_1"))

    def test_unknown_path_returns_404(self):
        self.start(rng_value=0.5)
        status, _ = self.call("GET", "/v1/unknown")
        self.assertEqual(status, 404)


class BehaviorTest(unittest.TestCase):

    def test_lognormal_parameters_follow_median_and_p99(self):
        rng = FixedRng(0.5)
        Behavior(300, 2000, 0.02, 0.002, 12, rng=rng).latency_s()
        mu, sigma = rng.lognorm_args
        self.assertAlmostEqual(mu, math.log(0.3))
        self.assertAlmostEqual(sigma, math.log(2000 / 300) / 2.326)


class ApprovalStoreTest(unittest.TestCase):

    def test_evicts_oldest_beyond_max(self):
        store = ApprovalStore(2)
        for i in range(3):
            store.record(f"ORD-{i}", f"k{i}", 1)
        self.assertIsNone(store.get("ORD-0"))
        self.assertEqual(store.get("ORD-2")["paymentKey"], "k2")


if __name__ == "__main__":
    unittest.main()
