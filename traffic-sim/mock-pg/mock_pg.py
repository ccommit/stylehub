#!/usr/bin/env python3
# 토스페이먼츠 승인·취소·조회 API 를 흉내 내는 Mock PG. 트래픽 시뮬레이션에서 운영 서버 로컬(127.0.0.1)에서 실제 PG 대신 돈다.
import json
import math
import os
import random
import sys
import threading
import time
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

# 표준정규분포의 99번째 백분위수
Z_P99 = 2.326


class Behavior:

    def __init__(self, median_ms, p99_ms, decline_rate, loss_rate, loss_delay_s, rng=None):
        self.mu = math.log(median_ms / 1000)
        self.sigma = math.log(p99_ms / median_ms) / Z_P99 if p99_ms > median_ms else 0.0
        self.decline_rate = decline_rate
        self.loss_rate = loss_rate
        self.loss_delay_s = loss_delay_s
        self.rng = rng or random.Random()

    def latency_s(self):
        return self.rng.lognormvariate(self.mu, self.sigma)

    def outcome(self):
        roll = self.rng.random()
        if roll < self.loss_rate:
            return "loss"
        if roll < self.loss_rate + self.decline_rate:
            return "decline"
        return "approve"


class ApprovalStore:

    def __init__(self, max_records):
        self.max_records = max_records
        self._records = OrderedDict()
        self._lock = threading.Lock()

    def record(self, order_id, payment_key, amount):
        with self._lock:
            self._records[order_id] = {"paymentKey": payment_key, "totalAmount": amount}
            self._records.move_to_end(order_id)
            while len(self._records) > self.max_records:
                self._records.popitem(last=False)

    def get(self, order_id):
        with self._lock:
            return self._records.get(order_id)


def make_handler(behavior, store, sleep=time.sleep):

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_POST(self):
            path = urlsplit(self.path).path
            body = self._read_json()
            if path == "/v1/payments/confirm":
                self._confirm(body)
            elif path.startswith("/v1/payments/") and path.endswith("/cancel"):
                payment_key = path[len("/v1/payments/"):-len("/cancel")]
                self._send(200, {"paymentKey": payment_key, "status": "CANCELED"}, "cancel")
            else:
                self._send(404, {"code": "NOT_FOUND"}, "unknown")

        def do_GET(self):
            path = urlsplit(self.path).path
            prefix = "/v1/payments/orders/"
            if not path.startswith(prefix):
                self._send(404, {"code": "NOT_FOUND"}, "unknown")
                return
            record = store.get(path[len(prefix):])
            if record:
                self._send(200, {"status": "DONE", **record}, "find-done")
            else:
                self._send(404, {"code": "NOT_FOUND_PAYMENT", "message": "모의 PG 에 승인 기록이 없음"}, "find-none")

        def _confirm(self, body):
            sleep(behavior.latency_s())
            outcome = behavior.outcome()
            order_id, payment_key, amount = body.get("orderId"), body.get("paymentKey"), body.get("amount")
            if outcome == "decline":
                self._send(400, {"code": "REJECT_CARD_PAYMENT", "message": "모의 거절"}, "confirm-decline")
                return
            store.record(order_id, payment_key, amount)
            if outcome == "loss":
                # 승인은 기록해 두고 응답만 서버의 읽기 타임아웃(10초)보다 늦게 보낸다
                sleep(behavior.loss_delay_s)
            self._send(200, {"paymentKey": payment_key, "orderId": order_id, "status": "DONE", "totalAmount": amount},
                       "confirm-" + outcome)

        def _read_json(self):
            length = int(self.headers.get("Content-Length") or 0)
            if length == 0:
                return {}
            try:
                return json.loads(self.rfile.read(length))
            except ValueError:
                return {}

        def _send(self, status, payload, outcome):
            data = json.dumps(payload, ensure_ascii=False).encode()
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                # 응답 유실 흉내에서 클라이언트가 먼저 끊은 경우
                pass
            self.log_message("%s %s %d %s", self.command, urlsplit(self.path).path, status, outcome)

        def log_request(self, code="-", size="-"):
            # 기본 접근 로그 대신 _send 가 결과까지 한 줄로 남긴다
            pass

    return Handler


def main():
    behavior = Behavior(
        median_ms=float(os.environ.get("MOCK_PG_LATENCY_MEDIAN_MS", 300)),
        p99_ms=float(os.environ.get("MOCK_PG_LATENCY_P99_MS", 2000)),
        decline_rate=float(os.environ.get("MOCK_PG_DECLINE_RATE", 0.02)),
        loss_rate=float(os.environ.get("MOCK_PG_LOSS_RATE", 0.002)),
        loss_delay_s=float(os.environ.get("MOCK_PG_LOSS_DELAY_S", 12)),
    )
    store = ApprovalStore(int(os.environ.get("MOCK_PG_MAX_RECORDS", 100000)))
    bind = os.environ.get("MOCK_PG_BIND", "127.0.0.1")
    port = int(os.environ.get("MOCK_PG_PORT", 18090))
    server = ThreadingHTTPServer((bind, port), make_handler(behavior, store))
    server.daemon_threads = True
    print(f"mock pg listening on {bind}:{port}", file=sys.stderr, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
