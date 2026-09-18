# 2단계 봇이 쓸 서버 준비 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 서버에서 봇이 API만으로 입점 신청부터 주문·결제·배송 완료까지 할 수 있게, Mock PG와 봇 데이터(스토어·상품·쿠폰·구매자·배송지)를 준비한다.

**Architecture:** 토스 호출은 운영 서버 로컬의 Python Mock PG(`127.0.0.1:18090`)로 돌린다. 방법은 systemd 드롭인이 `TOSS_PAYMENTS_*` 환경변수를 넣는 것이고, 자바 코드는 바꾸지 않는다. 준비 스크립트(`provision.py`)는 공개 API만 쓰고, 결과를 저장소 밖 상태 파일(`~/.stylehub-sim/`)에 남겨 다시 실행해도 이미 만든 것은 건너뛴다. 전부 로컬 compose에서 먼저 끝까지 돌려 본 뒤 운영에 적용한다.

**Tech Stack:** Python 3.12 표준 라이브러리만(Mock PG, 준비·스모크 스크립트), systemd, Docker Compose(로컬 검증: mysql:8.0, redis:7-alpine, python:3.12-slim, eclipse-temurin:17-jre), JUnit 5 + Spring Boot `Binder`(드롭인 바인딩 테스트)

**Spec:** `docs/traffic-sim/design.md` (3장 Mock PG, 4.1~4.2 준비 단계, 7장 2단계). PR #128이 병합되기 전에는 `111-feat-grafana-관측` 브랜치에 있다. API 규격 조사 결과는 SDD 작업 공간의 `phase2-api-contracts.md`에 있다.

## Global Constraints

- 모든 API 경로는 `/api/v1`로 시작한다. 로그인 세션 쿠키 이름은 `SESSION`이다.
- 서버 입력 검증(그대로 지켜야 한다):
  - 회원 이름 `^[가-힣a-zA-Z0-9]+$` 2~10자
  - 비밀번호 `^(?=.*[A-Za-z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,15}$`
  - 전화 `^\d{10,11}$`, 우편번호 `^\d{5}$`
  - 스토어 이름 2~20자, 상품 이름 20자 이하, 쿠폰 이벤트 이름 20자 이하
- 봇 데이터 식별: 이메일은 `@sim.stylehub.test`, 스토어 이름은 `[SIM]`으로 시작한다(설계 4.1).
- 쿠폰 이벤트: 시작은 "지금 − 1분" 이후여야 하고, 종료는 시작 + 하루 이상이어야 한다. 매일 한국 시간 20:00에 열고, 서버 JVM은 UTC다(0단계 확인). 그래서 `startedAt`은 UTC 11:00으로 보낸다.
- 스토어 ID는 곧 그 스토어 사용자의 `userId`다. 입점 승인(ADMIN)이 끝나야 상품·쿠폰·배송 처리가 된다.
- 배송지는 사용자당 최대 5개다. 첫 배송지가 자동으로 기본이 된다.
- 결제 승인 콜백은 `GET /payments/success?paymentKey=&orderId=<pgOrderId>&amount=<finalAmount>`이다. 로그인이 필요 없고, `paymentId`는 이 응답에서만 받을 수 있다.
- 배송 상태 전이는 `PAID → PREPARING → SHIPPING → DELIVERED`만 허용된다.
- Mock PG 기본값(설계 3.2의 가정): 지연은 로그정규분포(중앙값 300ms, p99 2초), 거절 2%, 응답 유실 0.2%(유실 지연 12초 > 서버 읽기 타임아웃 10초), 승인 기록은 최대 100,000건
- Python 스크립트는 표준 라이브러리만 쓴다. 운영 서버에는 `python3`만 있으면 된다.
- 대상 서버 허용 목록: `localhost`, `127.0.0.1`에 더해 환경변수 `SIM_ALLOWED_HOSTS`에 적은 호스트만 허용한다. 공개 저장소라 운영 서버 주소는 파일에 쓰지 않는다. 문서에는 `<EC2 공인 IP>` 자리표시자를 쓴다.
- 관리자 비밀번호는 `getpass`로만 받는다. 인자, 파일, 로그에 남기지 않는다.
- 운영 서버에 대한 명령(SSH, SQL, 서비스 재시작)과 운영을 대상으로 한 스크립트 실행은 사용자가 한다.
- 커밋, push, PR, 운영 배포는 사용자 승인 후에 한다. 시크릿 스캔: `git diff --cached --name-only -- . ':!docs/traffic-sim/plans' | xargs grep -lE "GOCSPX-|AKIA[0-9A-Z]{16}|sk_live_|hooks\.slack\.com/services/T|-----BEGIN.*PRIVATE KEY-----"`. 결과가 비어 있어야 하고, 계획 파일은 정규식 문자열 때문에 제외한다.
- Java: wildcard import 금지. 새 파일은 헤더(`@author WonJin Bae`, `@created`, 역할 1~2줄)를 둔다. 코드 주석은 `//` 한 줄. 테스트는 `@DisplayName`과 `// given`, `// when`, `// then`을 쓴다. Python 주석은 `#` 한 줄이다.

---

## 실행하면서 바뀐 점 (2026-09-19)

Task 1, 3~5를 리뷰하면서 아래를 고쳤다. Task 3~5의 코드 블록은 처음 계획한 모양이고, 실제 동작은 저장소의 코드가 기준이다.

- 주문 API 경로는 `/api/v1/orders/orders`, `/api/v1/orders/orders/{id}`, `/api/v1/orders/stores/{storeId}/orders/{orderId}/delivery`다. 주문 컨트롤러의 클래스 경로 `/orders`와 메서드 경로 `/orders`가 겹쳐 붙는다. API 규격 조사가 이걸 놓쳤고, 운영 서버에서 `POST /api/v1/orders`가 404, `POST /api/v1/orders/orders`가 401인 것으로 확인했다. Task 4 코드 블록도 고쳤다.
- 봇 계정 비밀번호는 상태 파일을 처음 만들 때 무작위 14자(`data.new_account_password()`)로 정한다. 처음 계획한 `account_password(token)`은 경우의 수가 100만이라, 운영 계정 2,005개가 추측할 수 있는 비밀번호 하나를 나눠 쓰게 된다. 그래서 테스트용으로만 남겼다.
- 상태 파일은 저장소 밖 `~/.stylehub-sim/provision-<host>_<port>.json`이다(디렉터리 700, 파일 600, 쓰고 나서 fsync 후 교체). `SIM_STATE_DIR`로 바꿀 수 있지만 저장소 안을 가리키면 거부한다. 워크트리를 지우거나 `git clean`을 해도 봇 비밀번호의 유일한 사본이 사라지지 않고, 공개 저장소에 올라가지도 않게 하려는 것이다. 그래서 `.gitignore`는 바꾸지 않는다.
- 응답을 못 받은 뒤 다시 실행할 때 중복을 만들지 않는다. 상품은 스토어 상품 목록에서 같은 이름을 찾아 그대로 쓴다. 쿠폰은 요청 전에 `pending` 표시를 남기고, 다시 실행할 때 그 표시가 있으면 경고만 하고 다시 만들지 않는다(미래 쿠폰 이벤트를 조회하는 API가 없다). 서버가 4xx로 거절하면 표시를 지운다.
- 거절·정지된 스토어가 있으면 멈춘다. 가입이 409인데 로그인이 401이면 상태 파일과 비밀번호가 어긋난 것이라 멈춘다(`U001`, `U003`만 이미 가입한 것으로 본다). 스모크 흐름은 Mock 거절(`PM004`)이면 같은 주문에 새 `paymentKey`로 최대 3번 시도하고, 결과를 알 수 없음(`PM011`)이면 안내를 출력하고 끝낸다.
- 요약 JSON에 `coupon_pending`이 추가됐다.
- 최종 전체 리뷰(final-review.md)를 반영해 고쳤다: 되돌리는 도구(`switch-pg.sh`, `mock-pg.conf`)를 `/tmp`가 아니라 `/opt/stylehub-mock-pg/`에도 설치해 재부팅·정리 뒤에도 남긴다. 결제 주소 확인은 유닛 설정이 아니라 실행 중인 프로세스의 환경(`/proc/$PID/environ`)을 본다. Task 7에 관리자 권한 회수 단계를 추가했다. `provision.py`는 같은 대상에 동시 실행을 막는 파일 잠금을 건다. `Api` 클래스 자체도 생성 시점에 허용 목록을 검사한다.
- 테스트 수: `sim` 19개, `setup` 13개, `mock-pg` 7개

---

## 사전 조건

- [ ] 릴리스 PR #129(#114, #116~#126)가 운영에 반영됨. 서버 밖에서 `GET /api/v1/users/me/addresses`가 404가 아니라 401이면 반영된 것이다.
- [ ] PR #128(관측 스택, 설계 문서)이 develop에 병합됨. 이 계획의 브랜치를 그 위로 옮긴다.
- [ ] 로컬: Docker Desktop 실행 중이고, 127.0.0.1:18080이 비어 있다.

## 파일 구조

| 파일 | 역할 | Task |
|---|---|---|
| `traffic-sim/mock-pg/mock_pg.py` | Mock PG HTTP 서버(승인·취소·조회) | 1 |
| `traffic-sim/mock-pg/test_mock_pg.py` | Mock PG 테스트 | 1 |
| `traffic-sim/mock-pg/stylehub-mock-pg.service` | 운영 서버 systemd 유닛 | 2 |
| `traffic-sim/mock-pg/mock-pg.conf` | `stylehub.service` 드롭인(`TOSS_PAYMENTS_*` 3개) | 2 |
| `traffic-sim/mock-pg/install.sh` | 운영 서버에 Mock PG 설치·기동 | 2 |
| `traffic-sim/mock-pg/switch-pg.sh` | 결제 대상을 mock 또는 toss로 전환하고 재시작 | 2 |
| `src/test/java/ccommit/stylehub/payment/config/TossPaymentPropertiesEnvBindingTest.java` | 드롭인 변수 이름이 토스 주소를 덮어쓰는지 확인 | 2 |
| `traffic-sim/sim/__init__.py`, `traffic-sim/setup/__init__.py` | 패키지 표시(빈 파일) | 3, 5 |
| `traffic-sim/sim/data.py` | 봇 데이터 생성 규칙(순수 함수) | 3 |
| `traffic-sim/sim/test_data.py` | 생성 규칙이 서버 검증을 통과하는지 | 3 |
| `traffic-sim/sim/api.py` | 세션 쿠키를 들고 다니는 API 클라이언트, 대상 허용 목록 | 4 |
| `traffic-sim/sim/test_api.py` | 가짜 서버로 쿠키·오류 처리·허용 목록 확인 | 4 |
| `traffic-sim/setup/provision.py` | 준비 단계 실행(재실행 안전) | 5 |
| `traffic-sim/setup/test_provision.py` | 가짜 API로 첫 실행과 재실행 확인 | 5 |
| `traffic-sim/setup/smoke_flow.py` | 주문 → Mock 결제 → 배송 완료 한 번 흘리기 | 5 |
| `traffic-sim/local/docker-compose.yml` | 로컬 검증 스택(mysql, redis, mock-pg, app) | 6 |
| `traffic-sim/README.md` | 로컬 → 운영 적용 절차 | 6 |

---

### Task 1: Mock PG 서버

**Files:**
- Create: `traffic-sim/mock-pg/mock_pg.py`
- Test: `traffic-sim/mock-pg/test_mock_pg.py`

**Interfaces:**
- Produces:
  - `Behavior(median_ms, p99_ms, decline_rate, loss_rate, loss_delay_s, rng=None)`: `.latency_s() -> float`, `.outcome() -> "approve" | "decline" | "loss"`
  - `ApprovalStore(max_records)`: `.record(order_id, payment_key, amount)`, `.get(order_id) -> dict | None`
  - `make_handler(behavior, store, sleep=time.sleep) -> type[BaseHTTPRequestHandler]`
  - HTTP 경로
    - `POST /v1/payments/confirm`: 200은 `{"status":"DONE",...}`, 400은 `{"code":"REJECT_CARD_PAYMENT"}`
    - `POST /v1/payments/{paymentKey}/cancel`: 200
    - `GET /v1/payments/orders/{orderId}`: 200은 `{"status":"DONE","paymentKey","totalAmount"}`, 기록이 없으면 404
  - 환경변수: `MOCK_PG_BIND`(기본 127.0.0.1), `MOCK_PG_PORT`(18090), `MOCK_PG_LATENCY_MEDIAN_MS`(300), `MOCK_PG_LATENCY_P99_MS`(2000), `MOCK_PG_DECLINE_RATE`(0.02), `MOCK_PG_LOSS_RATE`(0.002), `MOCK_PG_LOSS_DELAY_S`(12), `MOCK_PG_MAX_RECORDS`(100000)

- [ ] **Step 1: 실패하는 테스트 작성**

`traffic-sim/mock-pg/test_mock_pg.py`:

```python
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
```

- [ ] **Step 2: 실패 확인**

Run: `cd traffic-sim && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s mock-pg -v`
Expected: `ModuleNotFoundError: No module named 'mock_pg'`

- [ ] **Step 3: 구현**

`traffic-sim/mock-pg/mock_pg.py`:

```python
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
```

- [ ] **Step 4: 통과 확인**

Run: `cd traffic-sim && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s mock-pg -v`
Expected: `Ran 7 tests ... OK`

- [ ] **Step 5: 스테이징**

```bash
git add traffic-sim/mock-pg/mock_pg.py traffic-sim/mock-pg/test_mock_pg.py
```

---

### Task 2: 운영 서버 설치·전환 파일과 드롭인 바인딩 테스트

**Files:**
- Create: `src/test/java/ccommit/stylehub/payment/config/TossPaymentPropertiesEnvBindingTest.java`
- Create: `traffic-sim/mock-pg/mock-pg.conf`
- Create: `traffic-sim/mock-pg/stylehub-mock-pg.service`
- Create: `traffic-sim/mock-pg/install.sh`
- Create: `traffic-sim/mock-pg/switch-pg.sh`

**Interfaces:**
- Consumes: Task 1의 `mock_pg.py`, 환경변수 이름
- Produces:
  - 설치 경로 `/opt/stylehub-mock-pg/mock_pg.py`, 유닛 `stylehub-mock-pg.service`
  - 드롭인 `/etc/systemd/system/stylehub.service.d/mock-pg.conf`
  - `switch-pg.sh mock|toss`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/ccommit/stylehub/payment/config/TossPaymentPropertiesEnvBindingTest.java`:

```java
package ccommit.stylehub.payment.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/19
 *
 * <p>
 * Mock PG 드롭인(traffic-sim/mock-pg/mock-pg.conf)의 환경변수가 운영 설정 파일의 토스 주소를 덮어쓰는지 확인한다.
 * </p>
 */
class TossPaymentPropertiesEnvBindingTest {

    private static final Path DROP_IN = Path.of("traffic-sim/mock-pg/mock-pg.conf");

    @Test
    @DisplayName("드롭인 환경변수가 운영 설정 파일의 토스 주소보다 우선한다")
    void dropInEnvironmentOverridesProdUrls() throws IOException {
        // given
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new SystemEnvironmentPropertySource("systemEnvironment", readDropInEnvironment()));
        sources.addLast(new MapPropertySource("application-prod.properties", Map.of(
                "toss.payments.confirm-url", "https://api.tosspayments.com/v1/payments/confirm",
                "toss.payments.cancel-url", "https://api.tosspayments.com/v1/payments",
                "toss.payments.find-by-order-id-url", "https://api.tosspayments.com/v1/payments/orders")));

        // when
        TossPaymentProperties bound = new Binder(ConfigurationPropertySources.from(sources))
                .bind("toss.payments", TossPaymentProperties.class)
                .get();

        // then
        assertThat(bound.getConfirmUrl()).isEqualTo("http://127.0.0.1:18090/v1/payments/confirm");
        assertThat(bound.getCancelUrl()).isEqualTo("http://127.0.0.1:18090/v1/payments");
        assertThat(bound.getFindByOrderIdUrl()).isEqualTo("http://127.0.0.1:18090/v1/payments/orders");
    }

    // 드롭인의 "Environment=KEY=VALUE" 줄만 읽는다
    private Map<String, Object> readDropInEnvironment() throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        for (String line : Files.readAllLines(DROP_IN)) {
            if (line.startsWith("Environment=")) {
                String pair = line.substring("Environment=".length());
                int separator = pair.indexOf('=');
                env.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
        return env;
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'ccommit.stylehub.payment.config.TossPaymentPropertiesEnvBindingTest'`
Expected: FAIL with `NoSuchFileException: traffic-sim/mock-pg/mock-pg.conf`

- [ ] **Step 3: 드롭인 작성**

`traffic-sim/mock-pg/mock-pg.conf`:

```ini
# stylehub.service 드롭인. 토스 결제 호출을 운영 서버 로컬 Mock PG 로 돌린다(docs/traffic-sim/design.md 3장).
# 설치·제거는 switch-pg.sh 가 한다. 변수 이름은 TossPaymentPropertiesEnvBindingTest 가 검증한다.
[Service]
Environment=TOSS_PAYMENTS_CONFIRMURL=http://127.0.0.1:18090/v1/payments/confirm
Environment=TOSS_PAYMENTS_CANCELURL=http://127.0.0.1:18090/v1/payments
Environment=TOSS_PAYMENTS_FINDBYORDERIDURL=http://127.0.0.1:18090/v1/payments/orders
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests 'ccommit.stylehub.payment.config.TossPaymentPropertiesEnvBindingTest'`
Expected: PASS

- [ ] **Step 5: 유닛과 스크립트 작성**

`traffic-sim/mock-pg/stylehub-mock-pg.service`:

```ini
# 운영 서버에서 Mock PG 를 127.0.0.1:18090 으로 띄운다. install.sh 가 /etc/systemd/system 에 설치한다.
[Unit]
Description=StyleHub Mock PG (traffic simulation)
After=network.target

[Service]
ExecStart=/usr/bin/python3 /opt/stylehub-mock-pg/mock_pg.py
Environment=PYTHONUNBUFFERED=1
Environment=MOCK_PG_BIND=127.0.0.1
Environment=MOCK_PG_PORT=18090
Environment=MOCK_PG_LATENCY_MEDIAN_MS=300
Environment=MOCK_PG_LATENCY_P99_MS=2000
Environment=MOCK_PG_DECLINE_RATE=0.02
Environment=MOCK_PG_LOSS_RATE=0.002
Environment=MOCK_PG_LOSS_DELAY_S=12
Environment=MOCK_PG_MAX_RECORDS=100000
DynamicUser=yes
MemoryMax=64M
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

`traffic-sim/mock-pg/install.sh`:

```bash
#!/bin/bash
# 운영 서버에서 실행한다. Mock PG 를 설치해 127.0.0.1:18090 으로 띄운다. StyleHub 의 결제 주소는 바꾸지 않는다(switch-pg.sh 가 맡는다).
set -euo pipefail
cd "$(dirname "$0")"
command -v python3 > /dev/null || { echo "python3 가 없습니다"; exit 1; }

sudo install -d -m 755 /opt/stylehub-mock-pg
sudo install -m 644 mock_pg.py /opt/stylehub-mock-pg/mock_pg.py
sudo install -m 644 stylehub-mock-pg.service /etc/systemd/system/stylehub-mock-pg.service
sudo systemctl daemon-reload
sudo systemctl enable --now stylehub-mock-pg
sudo systemctl restart stylehub-mock-pg
sleep 2

echo "--- 수신 주소 (127.0.0.1:18090 만 나와야 한다)"
ss -tln | grep ':18090'
echo "--- 조회 확인 (404 가 정상)"
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18090/v1/payments/orders/install-check
echo "--- 메모리 사용량(KB)"
ps -o rss=,comm= -p "$(systemctl show -p MainPID --value stylehub-mock-pg)"
```

`traffic-sim/mock-pg/switch-pg.sh`:

```bash
#!/bin/bash
# 운영 서버에서 실행한다. StyleHub 의 결제 호출 대상을 mock(로컬 Mock PG) 또는 toss(실제 토스)로 바꾸고 재시작한다. 재시작 동안 약 24초 공백이 생긴다.
set -euo pipefail
cd "$(dirname "$0")"
DROP_IN_DIR=/etc/systemd/system/stylehub.service.d

case "${1:-}" in
    mock)
        systemctl is-active --quiet stylehub-mock-pg || { echo "Mock PG 가 떠 있지 않습니다. install.sh 를 먼저 실행하세요"; exit 1; }
        sudo install -d -m 755 "$DROP_IN_DIR"
        sudo install -m 644 mock-pg.conf "$DROP_IN_DIR/mock-pg.conf"
        ;;
    toss)
        sudo rm -f "$DROP_IN_DIR/mock-pg.conf"
        ;;
    *)
        echo "사용법: $0 mock|toss"
        exit 2
        ;;
esac

sudo systemctl daemon-reload
sudo systemctl restart stylehub
for _ in $(seq 1 20); do
    curl -fsS http://127.0.0.1:9081/actuator/health > /dev/null 2>&1 && break
    sleep 3
done
curl -fsS http://127.0.0.1:9081/actuator/health
echo
echo "--- 적용된 결제 주소"
systemctl show stylehub -p Environment | tr ' ' '\n' | grep TOSS_PAYMENTS_ || echo "(드롭인 없음, 토스 기본 주소)"
```

Run: `bash -n traffic-sim/mock-pg/install.sh && bash -n traffic-sim/mock-pg/switch-pg.sh && chmod +x traffic-sim/mock-pg/*.sh`
Expected: 출력 없음

- [ ] **Step 6: 전체 테스트와 스테이징**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

```bash
git add src/test/java/ccommit/stylehub/payment/config/TossPaymentPropertiesEnvBindingTest.java traffic-sim/mock-pg/
```

---

### Task 3: 봇 데이터 생성 규칙

**Files:**
- Create: `traffic-sim/sim/__init__.py` (빈 파일)
- Create: `traffic-sim/sim/data.py`
- Test: `traffic-sim/sim/test_data.py`

**Interfaces:**
- Produces (`sim.data`):
  - 상수: `EMAIL_DOMAIN = "sim.stylehub.test"`, `STORE_NAME_PREFIX = "[SIM]"`, `KST`
  - `buyer(index) -> {"name","email","birthDate"}`
  - `store(index) -> {"name","email","storeName","storeDescription"}`
  - `address(index) -> AddressCreateRequest dict`
  - `product(store_index, number) -> ProductCreateRequest dict`
  - `account_password(token: int) -> str`
  - `first_coupon_day(now_kst: datetime) -> date`
  - `coupon_events(first_day: date, days: int, server_tz: timezone) -> list[CouponEventCreateRequest dict]`

- [ ] **Step 1: 실패하는 테스트 작성**

`traffic-sim/sim/test_data.py`:

```python
import re
import unittest
from datetime import date, datetime, timedelta, timezone

from sim import data

# 서버 ValidationPatterns 와 같은 규칙
NAME = re.compile(r"^[가-힣a-zA-Z0-9]+$")
PASSWORD = re.compile(r"^(?=.*[A-Za-z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,15}$")
PHONE = re.compile(r"^\d{10,11}$")
ZIP = re.compile(r"^\d{5}$")
VALID_PAIRS = {
    "SHOES": {"SNEAKERS", "DRESS_SHOES", "RUNNING_SHOES"},
    "TOP": {"JACKET", "SWEATSHIRT", "T_SHIRT"},
    "BOTTOM": {"DENIM_PANTS", "SKIRT", "SHORT_PANTS"},
    "ACCESSORY": {"NECKLACE", "RING", "GLASSES"},
}


class AccountDataTest(unittest.TestCase):

    def test_buyer_and_store_pass_server_validation(self):
        for index in (1, 42, 99999):
            buyer = data.buyer(index)
            self.assertRegex(buyer["name"], NAME)
            self.assertTrue(2 <= len(buyer["name"]) <= 10)
            self.assertTrue(buyer["email"].endswith("@sim.stylehub.test"))
        for index in (1, 5, 99):
            store = data.store(index)
            self.assertRegex(store["name"], NAME)
            self.assertTrue(store["storeName"].startswith("[SIM]"))
            self.assertTrue(2 <= len(store["storeName"]) <= 20)

    def test_password_matches_server_pattern(self):
        for token in (0, 123456, 999999):
            self.assertRegex(data.account_password(token), PASSWORD)

    def test_address_passes_server_validation(self):
        for index in (1, 2000, 99999):
            address = data.address(index)
            self.assertRegex(address["phone"], PHONE)
            self.assertRegex(address["zipCode"], ZIP)
            self.assertTrue(2 <= len(address["recipientName"]) <= 20)
            self.assertLessEqual(len(address["streetAddress"]), 40)


class ProductDataTest(unittest.TestCase):

    def test_products_use_valid_category_pairs_and_limits(self):
        for store_index in range(1, 6):
            for number in range(1, 41):
                product = data.product(store_index, number)
                self.assertIn(product["subCategory"], VALID_PAIRS[product["mainCategory"]])
                self.assertLessEqual(len(product["name"]), 20)
                self.assertGreater(product["price"], 0)
                self.assertTrue(product["options"])
                for option in product["options"]:
                    self.assertLessEqual(len(option["size"]), 10)
                    self.assertGreaterEqual(option["stockQuantity"], 0)


class CouponScheduleTest(unittest.TestCase):

    def test_opens_at_20_kst_expressed_in_server_utc(self):
        events = data.coupon_events(date(2026, 9, 19), 2, timezone.utc)
        self.assertEqual(events[0]["startedAt"], "2026-09-19T11:00:00")
        self.assertEqual(events[1]["startedAt"], "2026-09-20T11:00:00")
        for event in events:
            started = datetime.fromisoformat(event["startedAt"])
            expired = datetime.fromisoformat(event["expiredAt"])
            self.assertGreaterEqual(expired - started, timedelta(days=1))
            self.assertLessEqual(len(event["name"]), 20)

    def test_first_day_skips_today_when_opening_is_within_30_minutes(self):
        self.assertEqual(data.first_coupon_day(datetime(2026, 9, 19, 4, 0, tzinfo=data.KST)), date(2026, 9, 19))
        self.assertEqual(data.first_coupon_day(datetime(2026, 9, 19, 19, 45, tzinfo=data.KST)), date(2026, 9, 20))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 실패 확인**

Run: `cd traffic-sim && touch sim/__init__.py && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s sim -t . -v`
Expected: `ImportError: cannot import name 'data'`

- [ ] **Step 3: 구현**

`traffic-sim/sim/data.py`:

```python
# 봇이 만드는 데이터의 생성 규칙. 서버 입력 검증(ValidationPatterns, 요청 DTO 제약)을 통과하는 값만 만든다.
from datetime import date, datetime, time, timedelta, timezone

EMAIL_DOMAIN = "sim.stylehub.test"
STORE_NAME_PREFIX = "[SIM]"
KST = timezone(timedelta(hours=9))
COUPON_OPEN_KST = time(20, 0)
# 서버 규칙: 쿠폰 이벤트 기간은 최소 하루
COUPON_DURATION = timedelta(hours=25)

CATEGORY_PAIRS = [
    ("SHOES", "SNEAKERS"), ("SHOES", "DRESS_SHOES"), ("SHOES", "RUNNING_SHOES"),
    ("TOP", "JACKET"), ("TOP", "SWEATSHIRT"), ("TOP", "T_SHIRT"),
    ("BOTTOM", "DENIM_PANTS"), ("BOTTOM", "SKIRT"), ("BOTTOM", "SHORT_PANTS"),
    ("ACCESSORY", "NECKLACE"), ("ACCESSORY", "RING"), ("ACCESSORY", "GLASSES"),
]
SIZES = {"SHOES": ["250", "260", "270"], "TOP": ["S", "M", "L"], "BOTTOM": ["S", "M", "L"], "ACCESSORY": ["FREE"]}
COLORS = ["블랙", "화이트"]


def buyer(index):
    return {"name": f"simb{index:05d}", "email": f"sim-buyer-{index:05d}@{EMAIL_DOMAIN}", "birthDate": "1995-05-05"}


def store(index):
    return {
        "name": f"sims{index:02d}",
        "email": f"sim-store-{index:02d}@{EMAIL_DOMAIN}",
        "storeName": f"{STORE_NAME_PREFIX} 스토어{index:02d}",
        "storeDescription": "트래픽 시뮬레이션용 봇 스토어",
    }


def address(index):
    return {
        "label": "집",
        "recipientName": f"봇구매자{index:05d}",
        "phone": f"010{index:08d}",
        "zipCode": f"{10000 + index % 90000:05d}",
        "streetAddress": "서울시 시뮬레이션로 1",
        "detailAddress": f"{index}호",
    }


def product(store_index, number):
    main, sub = CATEGORY_PAIRS[(store_index * 7 + number) % len(CATEGORY_PAIRS)]
    return {
        "name": f"SIM 상품 {store_index:02d}-{number:03d}",
        "mainCategory": main,
        "subCategory": sub,
        "description": "트래픽 시뮬레이션용 상품",
        "price": 19000 + (number * 7919 % 180) * 1000,
        "imageUrl": f"https://example.com/sim/{store_index:02d}/{number:03d}.jpg",
        "options": [{"color": color, "size": size, "stockQuantity": 200, "maxPointAmount": 0}
                    for color in COLORS for size in SIZES[main]],
    }


def account_password(token):
    # 서버 규칙: 8~15자, 영문·숫자·특수문자(@$!%*?&) 각각 하나 이상
    return f"Sim{token:06d}!a"


def first_coupon_day(now_kst):
    # 서버는 시작 시각이 지금보다 1분 넘게 과거면 거절하므로, 오늘 오픈까지 30분이 안 남았으면 다음 날부터 만든다
    today_open = datetime.combine(now_kst.date(), COUPON_OPEN_KST, KST)
    if now_kst < today_open - timedelta(minutes=30):
        return now_kst.date()
    return now_kst.date() + timedelta(days=1)


def coupon_events(first_day, days, server_tz):
    events = []
    for offset in range(days):
        day = first_day + timedelta(days=offset)
        start = datetime.combine(day, COUPON_OPEN_KST, KST).astimezone(server_tz).replace(tzinfo=None)
        events.append({
            "name": f"SIM {day:%m%d} 20시 쿠폰",
            "discountType": "RATE",
            "discountValue": 10,
            "minOrderAmount": 30000,
            "issueCount": 300,
            "startedAt": start.isoformat(timespec="seconds"),
            "expiredAt": (start + COUPON_DURATION).isoformat(timespec="seconds"),
        })
    return events
```

- [ ] **Step 4: 통과 확인**

Run: `cd traffic-sim && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s sim -t . -v`
Expected: `Ran 6 tests ... OK`

- [ ] **Step 5: 스테이징**

```bash
git add traffic-sim/sim/__init__.py traffic-sim/sim/data.py traffic-sim/sim/test_data.py
```

---

### Task 4: API 클라이언트와 대상 허용 목록

**Files:**
- Create: `traffic-sim/sim/api.py`
- Test: `traffic-sim/sim/test_api.py`

**Interfaces:**
- Consumes: 없음
- Produces (`sim.api`):
  - `ApiError(status: int, code: str, message: str)`
  - `check_allowed(base_url: str) -> None`: 허용되지 않은 대상이면 `SystemExit`
  - `Api(base_url, timeout=15.0)`
    - 기본 호출: `.request(method, path, body=None, query=None)`
    - 회원·스토어: `sign_up(user, password)`, `sign_up_store(store, password)`, `login(email, password)`, `get_store(store_id)`, `approve_store(store_id)`
    - 상품·쿠폰: `register_product(store_id, product)`, `get_product(product_id)`, `create_coupon_event(store_id, event)`
    - 배송지·주문·결제: `add_address(address)`, `addresses()`, `place_order(address_id, details, user_coupon_id=None)`, `get_order(order_id)`, `pay_success(payment_key, pg_order_id, amount)`, `update_delivery(store_id, order_id, status)`
    - 각 메서드는 파싱한 JSON을 돌려준다. 4xx·5xx 응답은 `ApiError`로 올린다.

- [ ] **Step 1: 실패하는 테스트 작성**

`traffic-sim/sim/test_api.py`:

```python
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


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 실패 확인**

Run: `cd traffic-sim && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s sim -t . -v`
Expected: `ModuleNotFoundError: No module named 'sim.api'`

- [ ] **Step 3: 구현**

`traffic-sim/sim/api.py`:

```python
# StyleHub API 를 부르는 최소 HTTP 클라이언트. 세션 쿠키(SESSION)를 들고 다니고, 오류 응답은 ApiError 로 올린다. 표준 라이브러리만 쓴다.
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar

API_PREFIX = "/api/v1"
LOCAL_HOSTS = {"localhost", "127.0.0.1"}


class ApiError(Exception):

    def __init__(self, status, code, message):
        super().__init__(f"{status} {code} {message}")
        self.status = status
        self.code = code
        self.message = message


def check_allowed(base_url):
    # 허용 목록에 없는 서버로는 보내지 않는다(설계 4.7). 운영 서버 주소는 저장소가 아니라 SIM_ALLOWED_HOSTS 로 준다
    host = urllib.parse.urlsplit(base_url).hostname
    listed = {h.strip() for h in os.environ.get("SIM_ALLOWED_HOSTS", "").split(",") if h.strip()}
    if host not in LOCAL_HOSTS | listed:
        raise SystemExit(f"허용되지 않은 대상입니다: {host}. SIM_ALLOWED_HOSTS 에 넣어야 보낼 수 있습니다")


class Api:

    def __init__(self, base_url, timeout=15.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))

    def request(self, method, path, body=None, query=None):
        url = self.base_url + API_PREFIX + path
        if query:
            url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(url, data=data, method=method,
                                         headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                payload = json.loads(raw) if raw else {}
            except ValueError:
                payload = {}
            raise ApiError(error.code, payload.get("code", ""), payload.get("message", "")) from None

    def sign_up(self, user, password):
        return self.request("POST", "/users/sign-up", {**user, "password": password})

    def sign_up_store(self, store, password):
        return self.request("POST", "/users/sign-up/store", {**store, "password": password})

    def login(self, email, password):
        return self.request("POST", "/users/login", {"email": email, "password": password})

    def get_store(self, store_id):
        return self.request("GET", f"/admin/stores/{store_id}")

    def approve_store(self, store_id):
        return self.request("PATCH", f"/admin/stores/{store_id}/approve")

    def register_product(self, store_id, product):
        return self.request("POST", f"/stores/{store_id}/products", product)

    def get_product(self, product_id):
        return self.request("GET", f"/products/{product_id}")

    def create_coupon_event(self, store_id, event):
        return self.request("POST", f"/stores/{store_id}/coupon-events", event)

    def add_address(self, address):
        return self.request("POST", "/users/me/addresses", address)

    def addresses(self):
        return self.request("GET", "/users/me/addresses")

    def place_order(self, address_id, details, user_coupon_id=None):
        return self.request("POST", "/orders/orders", {"addressId": address_id, "details": details, "userCouponId": user_coupon_id})

    def get_order(self, order_id):
        return self.request("GET", f"/orders/orders/{order_id}")

    def pay_success(self, payment_key, pg_order_id, amount):
        return self.request("GET", "/payments/success",
                            query={"paymentKey": payment_key, "orderId": pg_order_id, "amount": amount})

    def update_delivery(self, store_id, order_id, status):
        return self.request("PATCH", f"/orders/stores/{store_id}/orders/{order_id}/delivery", {"orderStatus": status})
```

- [ ] **Step 4: 통과 확인**

Run: `cd traffic-sim && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s sim -t . -v`
Expected: `Ran 12 tests ... OK` (Task 3의 6개 + 이번 6개)

- [ ] **Step 5: 스테이징**

```bash
git add traffic-sim/sim/api.py traffic-sim/sim/test_api.py
```

---

### Task 5: 준비 스크립트와 스모크 흐름

**Files:**
- Create: `traffic-sim/setup/__init__.py` (빈 파일)
- Create: `traffic-sim/setup/provision.py`
- Test: `traffic-sim/setup/test_provision.py`
- Create: `traffic-sim/setup/smoke_flow.py`

**Interfaces:**
- Consumes: `sim.data`의 생성 함수(Task 3), `sim.api.Api`·`ApiError`·`check_allowed`(Task 4)
- Produces:
  - `setup.provision.Config`(dataclass): `stores, products_per_store, buyers, coupon_days, signup_rate, server_tz, admin_email, now_kst`
  - `setup.provision.run(cfg, api_factory, state, save, ask_admin_password) -> dict`
    - 반환 요약: `{"stores_approved", "products", "coupon_events", "coupon_pending", "buyers_ready"}`
  - `setup.provision.load_state(path) -> dict`, `save_state(path, state)`, `state_path(base_url) -> Path`
  - 상태 파일 구조

    ```json
    {"password": str,
     "stores": {"1": {"userId", "approved", "products": {"1": {"productId", "optionIds"}}, "coupons": {name: id}}},
     "buyers": {"1": {"userId", "addressId"}}}
    ```

  - 실행
    - `cd traffic-sim && python3 -m setup.provision --base-url URL [--admin-email E] [--stores 5] [--products-per-store 40] [--buyers 2000] [--coupon-days 7] [--signup-rate 5] [--server-utc-offset 0]`
    - `python3 -m setup.smoke_flow --base-url URL`

- [ ] **Step 1: 실패하는 테스트 작성**

`traffic-sim/setup/test_provision.py`:

```python
import unittest
from datetime import datetime, timezone
from itertools import count

from sim import data
from setup.provision import Config, run


class FakeServer:
    # 가입·승인·상품·쿠폰·배송지를 메모리에 두는 가짜 StyleHub

    def __init__(self):
        self.ids = count(1)
        self.users = {}
        self.store_status = {}
        self.addresses = {}
        self.calls = []


class FakeApi:

    def __init__(self, server):
        self.server = server
        self.user_id = None

    def _call(self, name):
        self.server.calls.append(name)

    def sign_up(self, user, password):
        self._call("sign_up")
        self.server.users[user["email"]] = next(self.server.ids)

    def sign_up_store(self, store, password):
        self._call("sign_up_store")
        user_id = next(self.server.ids)
        self.server.users[store["email"]] = user_id
        self.server.store_status[user_id] = "PENDING"

    def login(self, email, password):
        self.user_id = self.server.users[email]
        return {"userId": self.user_id}

    def get_store(self, store_id):
        return {"status": self.server.store_status[store_id]}

    def approve_store(self, store_id):
        self._call("approve_store")
        self.server.store_status[store_id] = "APPROVED"

    def register_product(self, store_id, product):
        self._call("register_product")
        return {"productId": next(self.server.ids),
                "options": [{"productOptionId": next(self.server.ids)} for _ in product["options"]]}

    def create_coupon_event(self, store_id, event):
        self._call("create_coupon_event")
        return {"couponEventId": next(self.server.ids)}

    def addresses(self):
        return self.server.addresses.get(self.user_id, [])

    def add_address(self, address):
        self._call("add_address")
        created = {"addressId": next(self.server.ids)}
        self.server.addresses.setdefault(self.user_id, []).append(created)
        return created


class ProvisionTest(unittest.TestCase):

    def setUp(self):
        self.server = FakeServer()
        # 관리자는 사람이 미리 만들어 둔 계정이다(Task 7 Step 2)
        self.server.users["admin@example.com"] = 0
        self.cfg = Config(stores=2, products_per_store=2, buyers=3, coupon_days=2, signup_rate=1000.0,
                          server_tz=timezone.utc, admin_email="admin@example.com",
                          now_kst=datetime(2026, 9, 19, 4, 0, tzinfo=data.KST))
        self.state = {"password": data.account_password(1), "stores": {}, "buyers": {}}
        self.asked = []

    def run_once(self):
        return run(self.cfg, lambda: FakeApi(self.server), self.state, lambda: None,
                   lambda: self.asked.append(True) or "admin-password")

    def test_first_run_creates_everything(self):
        summary = self.run_once()
        self.assertEqual(summary, {"stores_approved": 2, "products": 4, "coupon_events": 4, "buyers_ready": 3})
        self.assertEqual(set(self.server.store_status.values()), {"APPROVED"})
        self.assertEqual(len(self.asked), 1)

    def test_second_run_creates_nothing_and_does_not_ask_admin_password(self):
        self.run_once()
        self.server.calls.clear()
        self.asked.clear()
        summary = self.run_once()
        self.assertEqual(summary, {"stores_approved": 2, "products": 4, "coupon_events": 4, "buyers_ready": 3})
        self.assertEqual(self.server.calls, [])
        self.assertEqual(self.asked, [])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 실패 확인**

Run: `cd traffic-sim && touch setup/__init__.py && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s setup -t . -v`
Expected: `ModuleNotFoundError: No module named 'setup.provision'`

- [ ] **Step 3: 준비 스크립트 구현**

`traffic-sim/setup/provision.py`:

```python
# 트래픽 시뮬레이션 준비 단계(설계 4.2). 공개 API 만으로 봇 스토어 입점·승인, 상품, 쿠폰 이벤트, 구매자와 배송지를 만든다.
# 결과는 traffic-sim/.state/ 에 남기고, 다시 실행하면 이미 만든 것은 건너뛴다. 7일이 지난 뒤 다시 실행하면 새 날짜의 쿠폰만 더 만든다.
import argparse
import getpass
import json
import os
import secrets
import time
import urllib.parse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from sim import data
from sim.api import Api, ApiError, check_allowed

STATE_DIR = Path(__file__).resolve().parent.parent / ".state"


@dataclass
class Config:
    stores: int
    products_per_store: int
    buyers: int
    coupon_days: int
    signup_rate: float
    server_tz: timezone
    admin_email: str | None
    now_kst: datetime


def state_path(base_url):
    return STATE_DIR / f"provision-{urllib.parse.urlsplit(base_url).netloc.replace(':', '_')}.json"


def load_state(path):
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"password": data.account_password(secrets.randbelow(10 ** 6)), "stores": {}, "buyers": {}}


def save_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
    os.chmod(temp, 0o600)
    temp.replace(path)


def sign_up_and_login(api, sign_up, email, password):
    # 이미 가입돼 있으면(재실행) 가입은 건너뛰고 로그인으로 userId 를 얻는다
    try:
        sign_up()
    except ApiError as error:
        if error.status != 409:
            raise
    return api.login(email, password)["userId"]


def run(cfg, api_factory, state, save, ask_admin_password):
    password = state["password"]
    admin = None
    schedule = data.coupon_events(data.first_coupon_day(cfg.now_kst), cfg.coupon_days, cfg.server_tz)

    for index in range(1, cfg.stores + 1):
        info = data.store(index)
        entry = state["stores"].setdefault(str(index), {})
        api = api_factory()
        if "userId" in entry:
            api.login(info["email"], password)
        else:
            entry["userId"] = sign_up_and_login(api, lambda: api.sign_up_store(info, password), info["email"], password)
            save()

        if not entry.get("approved"):
            if admin is None:
                admin = api_factory()
                admin.login(cfg.admin_email, ask_admin_password())
            if admin.get_store(entry["userId"])["status"] == "PENDING":
                admin.approve_store(entry["userId"])
            entry["approved"] = True
            save()

        products = entry.setdefault("products", {})
        for number in range(1, cfg.products_per_store + 1):
            if str(number) in products:
                continue
            created = api.register_product(entry["userId"], data.product(index, number))
            products[str(number)] = {"productId": created["productId"],
                                     "optionIds": [option["productOptionId"] for option in created["options"]]}
            save()

        coupons = entry.setdefault("coupons", {})
        for event in schedule:
            if event["name"] in coupons:
                continue
            coupons[event["name"]] = api.create_coupon_event(entry["userId"], event)["couponEventId"]
            save()

    interval = 1.0 / cfg.signup_rate
    for index in range(1, cfg.buyers + 1):
        entry = state["buyers"].get(str(index), {})
        if "addressId" in entry:
            continue
        started = time.monotonic()
        info = data.buyer(index)
        api = api_factory()
        if "userId" in entry:
            api.login(info["email"], password)
        else:
            entry["userId"] = sign_up_and_login(api, lambda: api.sign_up(info, password), info["email"], password)
        existing = api.addresses()
        entry["addressId"] = existing[0]["addressId"] if existing else api.add_address(data.address(index))["addressId"]
        state["buyers"][str(index)] = entry
        if index % 50 == 0:
            save()
            print(f"구매자 {index}/{cfg.buyers}", flush=True)
        time.sleep(max(0.0, interval - (time.monotonic() - started)))
    save()

    stores = [state["stores"][str(i)] for i in range(1, cfg.stores + 1)]
    return {
        "stores_approved": sum(1 for s in stores if s.get("approved")),
        "products": sum(len(s["products"]) for s in stores),
        "coupon_events": sum(len(s["coupons"]) for s in stores),
        "buyers_ready": sum(1 for i in range(1, cfg.buyers + 1) if "addressId" in state["buyers"].get(str(i), {})),
    }


def main():
    parser = argparse.ArgumentParser(description="트래픽 시뮬레이션 봇 데이터 준비")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--admin-email")
    parser.add_argument("--stores", type=int, default=5)
    parser.add_argument("--products-per-store", type=int, default=40)
    parser.add_argument("--buyers", type=int, default=2000)
    parser.add_argument("--coupon-days", type=int, default=7)
    parser.add_argument("--signup-rate", type=float, default=5.0)
    parser.add_argument("--server-utc-offset", type=int, default=0)
    args = parser.parse_args()
    check_allowed(args.base_url)

    cfg = Config(stores=args.stores, products_per_store=args.products_per_store, buyers=args.buyers,
                 coupon_days=args.coupon_days, signup_rate=args.signup_rate,
                 server_tz=timezone(timedelta(hours=args.server_utc_offset)), admin_email=args.admin_email,
                 now_kst=datetime.now(data.KST))
    path = state_path(args.base_url)
    state = load_state(path)

    def ask_admin_password():
        if not cfg.admin_email:
            raise SystemExit("승인할 스토어가 있어 --admin-email 이 필요합니다")
        return getpass.getpass(f"관리자({cfg.admin_email}) 비밀번호: ")

    summary = run(cfg, lambda: Api(args.base_url), state, lambda: save_state(path, state), ask_admin_password)
    print(json.dumps(summary, ensure_ascii=False))
    print(f"상태 파일: {path}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: 통과 확인**

Run: `cd traffic-sim && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s setup -t . -v`
Expected: `Ran 2 tests ... OK`

- [ ] **Step 5: 스모크 흐름 작성**

`traffic-sim/setup/smoke_flow.py`:

```python
# 준비 단계가 만든 1번 구매자와 1번 스토어로 주문 → Mock 결제 → 배송 완료까지 API 만으로 한 번 흘려 본다(2단계 완료 조건).
import argparse
import sys
import uuid

from sim import data
from sim.api import Api, ApiError, check_allowed
from setup.provision import load_state, state_path

DELIVERY_STEPS = ["PREPARING", "SHIPPING", "DELIVERED"]


def main():
    parser = argparse.ArgumentParser(description="주문·결제·배송 스모크 흐름")
    parser.add_argument("--base-url", required=True)
    args = parser.parse_args()
    check_allowed(args.base_url)

    path = state_path(args.base_url)
    if not path.exists():
        raise SystemExit(f"상태 파일이 없습니다: {path}. provision 을 먼저 실행하세요")
    state = load_state(path)
    password = state["password"]
    buyer, store = state["buyers"]["1"], state["stores"]["1"]

    buyer_api = Api(args.base_url)
    buyer_api.login(data.buyer(1)["email"], password)
    product = buyer_api.get_product(store["products"]["1"]["productId"])
    option = next(o for o in product["options"] if o["stockQuantity"] > 0)

    payment, order = None, None
    for attempt in range(1, 4):
        order = buyer_api.place_order(buyer["addressId"], [{"productOptionId": option["productOptionId"], "quantity": 1}])
        try:
            payment = buyer_api.pay_success(f"mock_{uuid.uuid4().hex}", order["pgOrderId"], order["finalAmount"])
            break
        except ApiError as error:
            # Mock PG 는 설정상 2% 를 거절한다. 거절이면 새 주문으로 다시 시도한다
            if error.code != "PM004":
                raise
            print(f"시도 {attempt}: Mock PG 거절, 새 주문으로 다시 시도", flush=True)
    if payment is None or payment["status"] != "DONE":
        print(f"실패: 결제 승인되지 않음 {payment}")
        sys.exit(1)
    print(f"주문 {order['orderId']} ({order['pgOrderId']}) 결제 {payment['paymentId']} 승인, {order['finalAmount']}원")

    store_api = Api(args.base_url)
    store_api.login(data.store(1)["email"], password)
    for status in DELIVERY_STEPS:
        store_api.update_delivery(store["userId"], order["orderId"], status)
        print(f"배송 상태 → {status}")

    final = buyer_api.get_order(order["orderId"])["orderStatus"]
    if final != "DELIVERED":
        print(f"실패: 최종 주문 상태 {final}")
        sys.exit(1)
    print("성공: 주문 → Mock 결제 → 배송 완료")


if __name__ == "__main__":
    main()
```

- [ ] **Step 6: 스테이징**

Run: `cd traffic-sim && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s sim -t . && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s setup -t . && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s mock-pg`
Expected: 세 번 모두 OK(18, 11, 7개)

```bash
git add traffic-sim/setup/
```

---

### Task 6: 로컬에서 끝까지 돌려 보기

**Files:**
- Create: `traffic-sim/local/docker-compose.yml`
- Create: `traffic-sim/README.md`

**Interfaces:**
- Consumes: Task 1~5 전부, `build/libs/stylehub-0.0.1-SNAPSHOT.jar`
- Produces: 로컬 스택 `http://127.0.0.1:18080`(compose 프로젝트 `stylehub-sim-local`)

- [ ] **Step 1: 로컬 compose 작성**

`traffic-sim/local/docker-compose.yml`:

```yaml
# 2단계 로컬 검증용 스택: MySQL, Redis, Mock PG, 직접 빌드한 StyleHub jar(prod 프로파일).
# 운영과 같게 JVM 시간대는 UTC 이고, 결제는 Mock PG 로 보낸다. 밖으로는 127.0.0.1:18080 하나만 연다.
name: stylehub-sim-local

services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: ${SIM_LOCAL_DB_PASSWORD:-simlocal}
      MYSQL_DATABASE: stylehub
      MYSQL_USER: stylehub
      MYSQL_PASSWORD: ${SIM_LOCAL_DB_PASSWORD:-simlocal}
    command: ["--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci"]
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h localhost -p$$MYSQL_ROOT_PASSWORD"]
      interval: 5s
      retries: 30

  redis:
    image: redis:7-alpine
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 10

  mock-pg:
    image: python:3.12-slim
    command: ["python", "/app/mock_pg.py"]
    environment:
      MOCK_PG_BIND: 0.0.0.0
      MOCK_PG_PORT: "18090"
      PYTHONUNBUFFERED: "1"
    volumes:
      - ../mock-pg/mock_pg.py:/app/mock_pg.py:ro

  app:
    image: eclipse-temurin:17-jre
    command: ["java", "-jar", "/app/app.jar"]
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
      mock-pg:
        condition: service_started
    environment:
      SPRING_PROFILES_ACTIVE: prod
      TZ: UTC
      DB_HOST: mysql
      DB_PASSWORD: ${SIM_LOCAL_DB_PASSWORD:-simlocal}
      REDIS_HOST: redis
      JPA_DDL_AUTO: update
      GOOGLE_CLIENT_ID: local-dummy
      GOOGLE_CLIENT_SECRET: local-dummy
      TOSS_SECRET_KEY: local-dummy
      TOSS_PAYMENTS_CONFIRMURL: http://mock-pg:18090/v1/payments/confirm
      TOSS_PAYMENTS_CANCELURL: http://mock-pg:18090/v1/payments
      TOSS_PAYMENTS_FINDBYORDERIDURL: http://mock-pg:18090/v1/payments/orders
    volumes:
      - ../../build/libs/stylehub-0.0.1-SNAPSHOT.jar:/app/app.jar:ro
    ports:
      - "127.0.0.1:18080:8080"
```

- [ ] **Step 2: jar 빌드와 기동**

```bash
./gradlew bootJar -x test
cd traffic-sim/local && docker compose up -d
until curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/v1/products | grep -q 200; do sleep 3; done; echo 기동됨
```

Expected: `기동됨`(첫 기동은 스키마 생성 때문에 1분 안팎 걸린다)

- [ ] **Step 3: 로컬 관리자 계정**

```bash
cd traffic-sim
python3 -c 'from sim.api import Api; print(Api("http://127.0.0.1:18080").sign_up({"name": "simadmin", "email": "sim-admin@sim.stylehub.test", "birthDate": "1990-01-01"}, "Local000001!a"))'
docker compose -f local/docker-compose.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" stylehub -e "UPDATE users SET role=\"ADMIN\" WHERE email=\"sim-admin@sim.stylehub.test\"; SELECT email, role FROM users WHERE role=\"ADMIN\";"'
```

Expected: 마지막 줄에 `sim-admin@sim.stylehub.test ADMIN`. 로컬 전용 계정이라 비밀번호를 명령에 적는다(운영에서는 getpass만 쓴다).

- [ ] **Step 4: 작은 규모로 준비 스크립트 실행 (재실행까지)**

```bash
cd traffic-sim
printf 'Local000001!a\n' | python3 -m setup.provision --base-url http://127.0.0.1:18080 --admin-email sim-admin@sim.stylehub.test --stores 2 --products-per-store 5 --buyers 20 --coupon-days 2
python3 -m setup.provision --base-url http://127.0.0.1:18080 --stores 2 --products-per-store 5 --buyers 20 --coupon-days 2
```

Expected: 두 번 모두 `{"stores_approved": 2, "products": 10, "coupon_events": 4, "coupon_pending": 0, "buyers_ready": 20}`, 마지막 줄은 `상태 파일: ~/.stylehub-sim/provision-127.0.0.1_18080.json`. 두 번째 실행은 관리자 비밀번호를 묻지 않는다. `getpass`는 터미널이 아닐 때 표준 입력에서 읽으므로 첫 줄의 `printf`가 쓰인다. 경고 문구가 나와도 괜찮다.

- [ ] **Step 5: 스모크 흐름**

```bash
cd traffic-sim && python3 -m setup.smoke_flow --base-url http://127.0.0.1:18080
docker compose -f local/docker-compose.yml logs mock-pg | grep -c 'confirm-'
```

Expected: `성공: 주문 → Mock 결제 → 배송 완료`. Mock PG 로그의 `confirm-` 줄은 시도 횟수만큼(보통 1) 나온다.

- [ ] **Step 6: README 작성**

`traffic-sim/README.md`(처음 계획한 내용이다. "운영 서버에 적용" 절은 최종 리뷰 뒤 되돌리는 방법과 전환 시점 주의를 더해 바뀌었으니, 저장소의 파일이 기준이다):

````markdown
# 트래픽 시뮬레이션

설계: `docs/traffic-sim/design.md`. 이 디렉터리는 2단계(봇이 쓸 서버 준비)까지 들어 있다.

| 경로 | 역할 |
|---|---|
| `mock-pg/` | 운영 서버 로컬에서 토스 대신 도는 Mock PG, systemd 유닛, 결제 대상 전환 스크립트 |
| `sim/` | 봇 데이터 생성 규칙(`data.py`), API 클라이언트(`api.py`) |
| `setup/provision.py` | API만으로 봇 스토어·상품·쿠폰·구매자·배송지를 만든다. 결과는 저장소 밖 상태 파일(아래 참고) |
| `setup/smoke_flow.py` | 주문 → Mock 결제 → 배송 완료를 한 번 흘린다 |
| `local/docker-compose.yml` | 로컬 검증 스택(`127.0.0.1:18080`) |

모든 명령은 `traffic-sim/`에서 실행한다. 로컬이 아닌 대상은 `SIM_ALLOWED_HOSTS`에 호스트를 적어야 요청을 보낸다.

## 테스트

```bash
python3 -m unittest discover -s sim -t .
python3 -m unittest discover -s setup -t .
python3 -m unittest discover -s mock-pg
```

## 로컬 검증

1. 저장소 루트에서 `./gradlew bootJar -x test`
2. `docker compose -f local/docker-compose.yml up -d`
3. 관리자 계정을 가입시키고 SQL로 역할을 ADMIN으로 바꾼다(계획 Task 6 Step 3)
4. `python3 -m setup.provision --base-url http://127.0.0.1:18080 --admin-email <관리자 이메일> --stores 2 --products-per-store 5 --buyers 20 --coupon-days 2`
5. `python3 -m setup.smoke_flow --base-url http://127.0.0.1:18080`
6. 끝나면 `docker compose -f local/docker-compose.yml down -v`로 스택을 지우고, 로컬 상태 파일 `~/.stylehub-sim/provision-127.0.0.1_18080.json`도 지운다. 남겨 두면 다음 로컬 실행이 이미 없어진 계정으로 로그인하려다 멈춘다.

## 상태 파일

`provision.py`는 결과를 `~/.stylehub-sim/provision-<host>_<port>.json`(권한 600)에 남긴다. 봇 계정 비밀번호는 이 파일에만 있어서, 운영 대상 파일을 잃으면 봇 계정으로 로그인할 수 없다. 위치는 `SIM_STATE_DIR`로 바꿀 수 있지만, 공개 저장소에 올라가지 않도록 저장소 안은 거부한다.

## 운영 서버에 적용

계획 `docs/traffic-sim/plans/2026-09-19-phase2-server-prep.md` Task 7의 순서를 따른다. Mock 모드가 켜져 있는 동안에는 결제가 실제로 일어나지 않는다. 진짜 토스 결제를 시연할 때는 `switch-pg.sh toss`로 되돌린다. Jenkins 배포는 드롭인을 건드리지 않으니, 배포한 뒤에도 Mock 모드가 유지된다.
````

- [ ] **Step 7: 정리와 스테이징**

```bash
cd traffic-sim/local && docker compose down -v && docker ps --filter name=stylehub-sim-local -q | wc -l
rm -f ~/.stylehub-sim/provision-127.0.0.1_18080.json
cd ../.. && git add traffic-sim/local/docker-compose.yml traffic-sim/README.md
```

Expected: 남은 컨테이너 0. 스택과 함께 계정이 없어졌으므로 로컬 상태 파일도 지운다.

---

### Task 7: 운영 서버 적용 (사용자 실행)

**Files:**
- Modify: `docs/traffic-sim/design.md` (0단계 체크리스트, 7장 2단계 결과)

**Interfaces:**
- Consumes: Task 1~6 전부. 명령은 모두 사용자가 실행하고, Claude는 결과를 확인해 문서에 옮긴다. 아래 `<EC2 공인 IP>`, `<키 경로>`, `<관리자 이메일>`은 실행할 때 채운다.
- 모든 블록은 저장소 루트에서 실행한다. `traffic-sim/` 안 파일이 필요한 블록은 서브셸 `(cd traffic-sim && ...)`로 감싼다.

- [ ] **Step 1: 릴리스 반영 확인**

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://<EC2 공인 IP>:8080/api/v1/users/me/addresses
```

Expected: `401`

- [ ] **Step 2: 관리자 계정 (사용자)**

이름은 2~10자 한글·영문·숫자, 비밀번호는 8~15자이고 영문·숫자·특수문자(@$!%*?&)를 하나 이상씩 넣는다. 다른 곳에서 쓰지 않는 비밀번호를 쓴다. 공개 HTTP(평문)로 오가므로 Step 5 뒤에 권한을 회수한다.

```bash
(cd traffic-sim && SIM_ALLOWED_HOSTS=<EC2 공인 IP> python3 -c 'import getpass, sys; from sim.api import Api, check_allowed; check_allowed(sys.argv[1]); print(Api(sys.argv[1]).sign_up({"name": sys.argv[2], "email": sys.argv[3], "birthDate": "1990-01-01"}, getpass.getpass("관리자 비밀번호: ")))' http://<EC2 공인 IP>:8080 shadmin <관리자 이메일>)
```

```bash
ssh -i <키 경로> ubuntu@<EC2 공인 IP> 'sudo mysql stylehub -e "UPDATE users SET role=\"ADMIN\" WHERE email=\"<관리자 이메일>\"; SELECT user_id, email, role FROM users WHERE role=\"ADMIN\";"'
```

Expected: 관리자 한 줄

- [ ] **Step 3: 커서 페이징 인덱스 (사용자)**

```bash
ssh -i <키 경로> ubuntu@<EC2 공인 IP> 'sudo mysql stylehub -e "SHOW INDEX FROM products; SHOW INDEX FROM orders;"'
```

`idx_products_main_sub_product_id`, `idx_products_user_product_id`, `idx_orders_user_order_id`와 같은 컬럼 순서의 인덱스가 없을 때만 적용한다. `scripts/db/create-cursor-paging-indexes.sql`을 배치로 통째 넣지 않는다 — 일부만 없어도 mysql 배치 모드가 첫 오류에서 멈춰 뒤 인덱스와 확인 조회가 실행되지 않는다. 없는 것만 하나씩 실행한다.

```bash
ssh -i <키 경로> ubuntu@<EC2 공인 IP> 'sudo mysql stylehub -e "CREATE INDEX idx_products_main_sub_product_id ON products (main_category, sub_category, product_id) ALGORITHM=INPLACE LOCK=NONE;"'
ssh -i <키 경로> ubuntu@<EC2 공인 IP> 'sudo mysql stylehub -e "CREATE INDEX idx_products_user_product_id ON products (user_id, product_id) ALGORITHM=INPLACE LOCK=NONE;"'
ssh -i <키 경로> ubuntu@<EC2 공인 IP> 'sudo mysql stylehub -e "CREATE INDEX idx_orders_user_order_id ON orders (user_id, order_id) ALGORITHM=INPLACE LOCK=NONE;"'
```

```bash
ssh -i <키 경로> ubuntu@<EC2 공인 IP> 'sudo mysql stylehub -e "SHOW INDEX FROM products WHERE Key_name LIKE \"idx_%\"; SHOW INDEX FROM orders WHERE Key_name LIKE \"idx_%\";"'
```

- [ ] **Step 4: Mock PG 설치와 전환 (사용자)**

설치 전에 `stylehub.service`가 환경변수를 어디서 읽는지 확인한다(값은 출력하지 않는다 — `EnvironmentFile`이 있고 그 안에 `TOSS_PAYMENTS_*`가 있으면 드롭인보다 우선해 전환이 무효화된다).

```bash
ssh -i <키 경로> ubuntu@<EC2 공인 IP> 'systemctl cat stylehub | grep -E "^(# /|EnvironmentFile)"; sudo grep -c "^TOSS_PAYMENTS_" /home/ubuntu/stylehub/.env'
```

Expected: 두 번째 숫자(개수)가 0. 0이 아니면 멈추고 확인한다.

```bash
scp -i <키 경로> -r traffic-sim/mock-pg ubuntu@<EC2 공인 IP>:/tmp/ && ssh -i <키 경로> ubuntu@<EC2 공인 IP> 'bash /tmp/mock-pg/install.sh && bash /opt/stylehub-mock-pg/switch-pg.sh mock'
```

Expected
- 설치: `127.0.0.1:18090` 한 줄, 조회 확인 `404`, 메모리 사용량
- 전환: 헬스체크 `{"status":"UP"...}`, 실행 중인 프로세스가 받은 결제 주소 세 줄(`TOSS_PAYMENTS_*=http://127.0.0.1:18090/...`)
- 재시작 동안 약 24초 공백이 생긴다. 맥의 Grafana에는 수집 끊김이 2분 안에 해소되므로 알림은 오지 않아야 한다.

- [ ] **Step 5: 준비 스크립트 (사용자)**

```bash
(cd traffic-sim && SIM_ALLOWED_HOSTS=<EC2 공인 IP> python3 -m setup.provision --base-url http://<EC2 공인 IP>:8080 --admin-email <관리자 이메일>)
```

Expected: `{"stores_approved": 5, "products": 200, "coupon_events": 35, "coupon_pending": 0, "buyers_ready": 2000}`, 마지막 줄에 상태 파일 경로. 봇 비밀번호는 그 파일에만 있으니 지우지 않는다. 상태 파일 경로에는 서버 주소가 들어 있으니 문서에 옮기지 않는다. 구매자 가입이 초당 5건이라 약 7분 걸린다. 그동안 맥 Grafana에 요청 수와 CPU 변화가 보인다.

- [ ] **Step 6: 관리자 권한 회수 (사용자)**

모든 스토어가 승인된 뒤 ADMIN 권한을 되돌린다. 평문 HTTP로 두 번(가입·로그인) 오간 비밀번호를 가진 계정이 결제 취소·스토어 정지까지 할 수 있는 채로 남지 않게 한다.

```bash
ssh -i <키 경로> ubuntu@<EC2 공인 IP> 'sudo mysql stylehub -e "UPDATE users SET role=\"USER\" WHERE email=\"<관리자 이메일>\"; SELECT COUNT(*) FROM users WHERE role=\"ADMIN\";"'
```

Expected: `COUNT(*)` 0. provision을 다시 실행해도 승인되지 않은 스토어가 없으면 관리자를 묻지 않으므로(`main()`의 `unapproved` 검사) 3단계에는 영향이 없다. 스토어를 더 늘릴 때만 다시 ADMIN으로 올린다.

- [ ] **Step 7: 운영 스모크 흐름과 토스 미호출 확인 (사용자)**

```bash
(cd traffic-sim && SIM_ALLOWED_HOSTS=<EC2 공인 IP> python3 -m setup.smoke_flow --base-url http://<EC2 공인 IP>:8080)
ssh -i <키 경로> ubuntu@<EC2 공인 IP> 'sudo journalctl -u stylehub-mock-pg --since "-10 min" --no-pager | grep -c "confirm-"; sudo cat /proc/$(systemctl show -p MainPID --value stylehub)/environ | tr "\0" "\n" | grep -c "^TOSS_PAYMENTS_"'
```

Expected
- 스모크: `성공: 주문 → Mock 결제 → 배송 완료`
- Mock PG 확인 명령의 첫 숫자: 1 이상(승인이 Mock PG로 왔다)
- 두 번째 숫자: 3(실행 중인 프로세스에 드롭인이 적용됨)

- [ ] **Step 8: 설계 문서 갱신과 커밋·PR (사용자 승인 후)**

`docs/traffic-sim/design.md`를 이렇게 고친다.
- 상태 줄을 `2단계 완료(YYYY-MM-DD), 3단계 준비 중`으로 바꾼다.
- 7장 2단계 항목에 결과를 적는다: 관리자 계정 생성과 권한 회수 여부(이메일은 적지 않는다), 인덱스 적용 여부, Mock PG RSS, 준비 요약 JSON, 스모크 성공 시각, 준비에 걸린 시간.
- 4.2·4.8을 실제 구현에 맞게 고친다: 관리자 이메일·비밀번호는 환경변수가 아니라 `--admin-email` 인자와 getpass로 받는다. `--coupons-only` 옵션은 없고, 옵션 없이 다시 실행하면 새 날짜 쿠폰만 만든다. 상태는 `traffic-sim/.state/`가 아니라 `~/.stylehub-sim/`에 저장하고(저장소 안 경로는 거부), `.gitignore`는 바꾸지 않는다. 유닛은 `mock-pg/mock-pg.service`가 아니라 `stylehub-mock-pg.service`와 드롭인 `mock-pg.conf`다. 완료 조건 "토스로 나가는 요청 0건"은 앱 로그가 아니라 Step 7의 Mock PG 승인 로그와 실행 중인 프로세스 환경 확인으로 검증한다. PR #128이 병합되어 이 브랜치를 develop 위로 옮긴 뒤에 한다.

커밋 전에 시크릿 스캔에 더해 `git diff --cached | grep -cF "<EC2 공인 IP 실제 값>"`이 0인지 확인한다(문서에는 실제 값 대신 자리표시자만 남긴다).

이슈를 만들고(사용자 승인), 브랜치 이름을 `<이슈번호>-feat-봇-서버준비`로 바꾼 뒤 커밋·push·PR을 만든다(base `develop`).

---

## 이 계획에서 하지 않는 것

- 트래픽 봇(Locust, 페르소나, 시간대 곡선), 사용자 관점 대시보드, 봇 기준 알림: 3단계
- 가설 2의 전/후 비교(로컬 `74c9fc4` 대 `93e0f3d`): 봇이 생기는 3단계에서 한다.
- Mock PG 지표를 Prometheus로 노출하는 것: 필요해지면 3단계에서 한다(지금은 journald 한 줄 로그).
- `GET /api/v1/coupon-events`가 컨트롤러 주석("공개")과 달리 로그인을 요구하는 문제: 봇에는 영향이 없다(로그인해서 부른다). 따로 이슈로 남긴다.
- 봇 데이터 정리(삭제) 스크립트: 설계 4.1대로 만들지 않는다.
