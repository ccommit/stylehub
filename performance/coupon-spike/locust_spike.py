"""
선착순 쿠폰 오픈 순간 부하.

- CouponSpikeUser 1,000명: 모두 START_AT(epoch ms) 시각에 동시에 첫 요청을 보낸다.
  발급 성공(200)이나 거절(4xx)을 받으면 멈춘다. 5xx·연결 오류면 0.1초 뒤 다시 누르고 최대 3번까지 시도한다.
- ProductBrowseUser 50명: 처음부터 끝까지 사용자당 초당 2건씩 상품 목록(캐시를 타지 않는 커서 페이지)을 조회한다.
  쿠폰이 몰리기 전과 몰리는 동안의 조회 지연을 비교하기 위한 배경 부하.

환경 변수: SPIKE_SESSIONS(세션 파일), SPIKE_EVENT_ID, SPIKE_START_AT(epoch ms), SPIKE_COUNTER_FILE(세션 분배용), SPIKE_MAX_PRODUCT_ID, SPIKE_REQLOG_DIR
"""
import fcntl
import json
import os
import random
import time

import gevent
from locust import FastHttpUser, constant_throughput, events, task
from locust.exception import StopUser

SESSIONS = json.load(open(os.environ["SPIKE_SESSIONS"]))
EVENT_ID = os.environ["SPIKE_EVENT_ID"]
START_AT = int(os.environ["SPIKE_START_AT"]) / 1000.0
MAX_PRODUCT_ID = int(os.environ["SPIKE_MAX_PRODUCT_ID"])
MAX_ATTEMPTS = 3

REQLOG_DIR = os.environ.get("SPIKE_REQLOG_DIR")
COUNTER_FILE = os.environ["SPIKE_COUNTER_FILE"]

_records = []


# 요청마다 보낸 시각과 클라이언트가 잰 지연을 남긴다. 서버 스레드를 기다린 시간까지 포함한 사용자 기준 값이다.
@events.request.add_listener
def _on_request(request_type, name, response_time, response, exception, start_time=None, **kwargs):
    status = getattr(response, "status_code", 0) if response is not None else 0
    _records.append(f"{int((start_time or time.time()) * 1000)},{name},{status},{response_time:.1f}")


@events.test_stop.add_listener
def _dump(environment, **kwargs):
    if REQLOG_DIR and _records:
        with open(os.path.join(REQLOG_DIR, f"reqlog-{os.getpid()}.csv"), "w") as f:
            f.write("\n".join(_records) + "\n")


# Locust 프로세스마다 사용자 수가 고르게 나뉘지 않을 수 있어, 파일 잠금으로 전체 프로세스에 걸쳐 세션을 한 번씩만 나눠 준다.
def _take_session():
    with open(COUNTER_FILE, "a+") as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        f.seek(0)
        n = int(f.read() or 0)
        f.seek(0)
        f.truncate()
        f.write(str(n + 1))
        f.flush()
        fcntl.flock(f, fcntl.LOCK_UN)
    if n >= len(SESSIONS):
        raise RuntimeError(f"session pool exhausted: {n}")
    return SESSIONS[n]


class CouponSpikeUser(FastHttpUser):
    fixed_count = 1000
    wait_time = constant_throughput(1000)

    def on_start(self):
        self.cookie = _take_session()
        delay = START_AT - time.time()
        if delay > 0:
            gevent.sleep(delay)

    @task
    def issue(self):
        for attempt in range(1, MAX_ATTEMPTS + 1):
            done = False
            with self.client.post(f"/api/v1/coupon-events/{EVENT_ID}/issue",
                                  headers={"Cookie": self.cookie},
                                  name=f"issue attempt{min(attempt, 2)}",
                                  catch_response=True) as r:
                if r.status_code == 200 or 400 <= r.status_code < 500:
                    r.success()
                    done = True
                else:
                    r.failure(f"status={r.status_code}")
            if done:
                break
            gevent.sleep(0.1)
        raise StopUser()


class ProductBrowseUser(FastHttpUser):
    fixed_count = 50
    wait_time = constant_throughput(2)

    @task
    def browse(self):
        cursor = random.randint(100, MAX_PRODUCT_ID)
        self.client.get(f"/api/v1/products?cursor={cursor}&pageSize=20", name="products cursor")
