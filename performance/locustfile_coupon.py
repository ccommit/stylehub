"""
시나리오 4: 선착순 쿠폰 발급 (극한 동시성)

측정 목표
  - 비관적 락 vs Redis 분산 락 (@DistributedLock) 의 TPS / 응답시간 / DB 커넥션 사용률 비교
  - 100 장 / 500 명 부하 시 정확히 100 장 발급 (SOLD_OUT 400 명) 정합성 검증
  - 1000 장 / 1000 명 부하 시 전원 성공 (경합만)
  - 같은 user 중복 호출 시 1번만 성공 (CP006 ALREADY_ISSUED) 검증

측정 범위
  - POST /api/v1/coupon-events/{couponEventId}/issue 한 단계만 측정 (다른 API 부하 X)

응답 분류 (Locust catch_response)
  - 200      → 정상 발급 (success)
  - 409 CP005 SOLD_OUT       → *의도된 실패* (success 처리, 정합성 보호)
  - 409 CP006 ALREADY_ISSUED → *의도된 실패* (success 처리, 중복 방어)
  - 400 CP002~CP004          → *의도된 실패* (만료/시작전/비활성)
  - 그 외 5xx / 0           → *진짜 에러* (failure)

사전 준비
  1. seed-test-buyers.sql 실행 (perf_buyer_* 500 명 + 세션)
  2. seed-test-coupons.sql 실행 (PERF_C_100 / 1000 / EXPIRED / FUTURE)
     - 매 측정 *시작 전* 에 다시 실행해 issued_count + user_coupons RESET
  3. 환경변수
     - PERF_DB_PASSWORD=root (DB 연결)
     - PERF_TARGET_COUPON=100  → 100장 / 1000 / EXPIRED / FUTURE 중 무엇을 hit 할지 (기본 100)
       100   = PERF_C_100      (정합성 검증)
       1000  = PERF_C_1000     (한계 TPS)
       EXPIRED = PERF_C_EXPIRED (전원 400 검증)
       FUTURE  = PERF_C_FUTURE  (전원 400 검증)

실행 (Web UI)
  PERF_DB_PASSWORD=root PERF_TARGET_COUPON=100 \\
    locust -f performance/locustfile_coupon.py --host=http://localhost:8080
"""
import os
import threading
from collections import deque

import requests
from locust import HttpUser, task, between, events


# 환경 변수
DB_HOST = os.environ.get("PERF_DB_HOST", "localhost")
DB_PORT = int(os.environ.get("PERF_DB_PORT", "3306"))
DB_USER = os.environ.get("PERF_DB_USER", "root")
DB_PASSWORD = os.environ.get("PERF_DB_PASSWORD", "")
DB_NAME = os.environ.get("PERF_DB_NAME", "stylehub")

BUYER_PASSWORD = "Test1234!"
TARGET_COUPON_KEY = os.environ.get("PERF_TARGET_COUPON", "100")
# 100 / 1000 / EXPIRED / FUTURE


# 글로벌 상태 — test_start 에서 채움
SESSION_POOL: "deque[tuple[int, dict]]" = deque()
SESSION_POOL_LOCK = threading.Lock()
TARGET_COUPON_EVENT_ID: int | None = None


@events.test_start.add_listener
def setup_session_pool(environment, **kwargs):
    """
    테스트 시작 전 1회 실행:
      1) 타겟 PERF_C_* 쿠폰 이벤트 ID 조회 (PERF_TARGET_COUPON 환경변수 기준)
      2) perf_buyer 전원 사전 로그인 → 세션 쿠키 풀 구축

    setup 호출은 raw requests / pymysql 로 Locust stats 격리.
    """
    host = environment.host
    if not host:
        print("[setup] host 미설정 — 셋업 건너뜀")
        return

    try:
        import pymysql
    except ImportError:
        print("[setup] pymysql 미설치 — pip install pymysql 필요. 셋업 중단.")
        return

    # 1) 타겟 쿠폰 이벤트 ID + buyer 목록 조회
    target_name = f"PERF_C_{TARGET_COUPON_KEY}"
    conn = pymysql.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER,
        password=DB_PASSWORD, db=DB_NAME, charset="utf8mb4",
    )
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT coupon_event_id, issue_count, issued_count "
                "FROM coupon_events WHERE name = %s",
                (target_name,),
            )
            row = cur.fetchone()
            if not row:
                print(f"[setup] 타겟 쿠폰 이벤트 '{target_name}' 가 DB 에 없습니다. "
                      f"seed-test-coupons.sql 을 먼저 실행해주세요.")
                return

            global TARGET_COUPON_EVENT_ID
            TARGET_COUPON_EVENT_ID = row[0]
            print(f"[setup] 타겟 쿠폰: name={target_name} id={row[0]} "
                  f"issue_count={row[1]} issued_count={row[2]}")

            cur.execute("""
                SELECT u.user_id, u.email
                FROM users u
                WHERE u.email LIKE 'perf_buyer_%@perf.test'
            """)
            buyers = cur.fetchall()
    finally:
        conn.close()

    if not buyers:
        print("[setup] perf_buyer 가 0명입니다. seed-test-buyers.sql 먼저 실행해주세요.")
        return

    # 2) 전원 사전 로그인 → 세션 풀 구축
    success = 0
    for user_id, email in buyers:
        try:
            res = requests.post(
                f"{host}/api/v1/users/login",
                json={"email": email, "password": BUYER_PASSWORD},
                timeout=10,
            )
            if res.status_code != 200:
                continue
            cookies = res.cookies.get_dict()
            with SESSION_POOL_LOCK:
                SESSION_POOL.append((user_id, cookies))
            success += 1
        except requests.RequestException as e:
            print(f"[setup] login 예외 user_id={user_id} err={e}")

    print(f"[setup] 세션 풀 구축 완료: {success}/{len(buyers)} 명 로그인")


def acquire_session() -> "tuple[int, dict] | None":
    with SESSION_POOL_LOCK:
        if not SESSION_POOL:
            return None
        return SESSION_POOL.popleft()


def release_session(session_tuple) -> None:
    with SESSION_POOL_LOCK:
        SESSION_POOL.append(session_tuple)


class CouponUser(HttpUser):
    # 부하 클라이언트 wait_time 천장을 낮춰 시스템의 진짜 한계 노출 (시나리오 2 의 교훈)
    wait_time = between(0.05, 0.2)

    def on_start(self):
        """각 VU 가 시작 시 세션 풀에서 자기 credentials 를 받아온다."""
        session = acquire_session()
        if session is None:
            print("[VU] 세션 풀 고갈 — buyer 수보다 많은 VU 입니다")
            self.environment.runner.quit()
            return
        self.user_id, cookies = session
        self.client.cookies.update(cookies)
        self._session_tuple = session

    def on_stop(self):
        if hasattr(self, "_session_tuple"):
            release_session(self._session_tuple)

    @task
    def issue_coupon(self):
        """
        타겟 쿠폰 이벤트에 발급 요청.

        의도된 실패 (200 / 400 / 409 CP005 / 409 CP006) 와 진짜 에러 (5xx, 0) 를
        구분해서 *진짜 에러만* failure 처리. 의도된 실패는 success — *비즈니스 동작*.
        """
        if TARGET_COUPON_EVENT_ID is None:
            return

        with self.client.post(
            f"/api/v1/coupon-events/{TARGET_COUPON_EVENT_ID}/issue",
            name="POST /coupon-events/{id}/issue",
            catch_response=True,
        ) as response:
            sc = response.status_code

            if sc == 200:
                response.success()
            elif sc == 409:
                # CP005 SOLD_OUT 또는 CP006 ALREADY_ISSUED — 둘 다 정합성 보호의 정상 응답
                response.success()
            elif sc == 400:
                # CP002~004 (비활성/시작전/만료) — EXPIRED/FUTURE 시나리오의 의도된 응답
                response.success()
            else:
                # 5xx / timeout (status=0) — 진짜 에러
                response.failure(f"unexpected status={sc}")
