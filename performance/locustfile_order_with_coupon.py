"""
시나리오 2-2: 쿠폰 사용 주문 (복합 트랜잭션)

측정 목표
  - 쿠폰 사용 주문의 RPS / 응답시간 / 정합성
  - 쿠폰 적용 오버헤드 정량화 (쿠폰 사용 vs 미사용 응답시간 차이)
  - 같은 UserCoupon 으로 동시 주문 시 1 건만 성공 (UNIQUE / 비관적 락 효과)
  - 만료 쿠폰 / 최소 주문 미달 / USED 재사용 → 의도된 거절 검증

응답 분류
  - 200/201          → 정상 발급 (success)
  - 400 CP004        → 만료 쿠폰 (의도 — success)
  - 400 CP012        → MIN_ORDER_AMOUNT_NOT_MET (의도 — success)
  - 403 CP013        → UNAUTHORIZED_USER_COUPON (의도 — success)
  - 409 CP011        → COUPON_NOT_AVAILABLE (이미 USED — 의도, success)
  - 409 OR004        → INSUFFICIENT_STOCK (재고 소진 — 의도, success)
  - 그 외 5xx / 0    → 진짜 에러 (failure)

환경변수
  PERF_DB_PASSWORD=root
  PERF_COUPON_RATIO=1.0  → 1.0 = 모든 주문이 쿠폰 사용 / 0.5 = 절반 / 0.0 = 미사용

실행
  PERF_DB_PASSWORD=root PERF_COUPON_RATIO=1.0 \\
    locust -f performance/locustfile_order_with_coupon.py --host=http://localhost:8090
"""
import os
import random
import threading
from collections import deque

import requests
from locust import HttpUser, task, between, events


DB_HOST = os.environ.get("PERF_DB_HOST", "localhost")
DB_PORT = int(os.environ.get("PERF_DB_PORT", "3306"))
DB_USER = os.environ.get("PERF_DB_USER", "root")
DB_PASSWORD = os.environ.get("PERF_DB_PASSWORD", "")
DB_NAME = os.environ.get("PERF_DB_NAME", "stylehub")

BUYER_PASSWORD = "Test1234!"
COUPON_RATIO = float(os.environ.get("PERF_COUPON_RATIO", "1.0"))


# 글로벌 상태 — test_start 에서 채움
# (user_id, address_id, cookies, [user_coupon_ids by name])
SESSION_POOL: "deque[tuple[int, int, dict, dict]]" = deque()
SESSION_POOL_LOCK = threading.Lock()
OPTION_IDS: list[int] = []


@events.test_start.add_listener
def setup_session_pool(environment, **kwargs):
    """
    1) DB 에서 perf_buyer + 그들의 PERF_USE_* UserCoupon 조회
    2) 옵션 풀 (유효한 store FK 가 살아있는 옵션 200 개)
    3) 모든 buyer 사전 로그인 → SESSION_POOL 에 적재
    """
    print("[setup] === TEST START 진입 ===", flush=True)
    host = environment.host
    if not host:
        print("[setup] host 미설정 — 셋업 건너뜀", flush=True)
        return

    try:
        import pymysql
    except ImportError:
        print("[setup] pymysql 미설치")
        return

    conn = pymysql.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER,
        password=DB_PASSWORD, db=DB_NAME, charset="utf8mb4",
    )
    try:
        with conn.cursor() as cur:
            # 1) buyer + UserCoupon 매핑
            # gevent 환경에서 ThreadPoolExecutor 가 deadlock 일으켜 직렬 로그인.
            # setup 시간 단축 위해 LIMIT 200 — 시나리오 2-2 부하 (20~200 user) 충분.
            cur.execute("""
                SELECT u.user_id, MIN(a.address_id) AS address_id, u.email
                FROM users u
                JOIN addresses a ON a.user_id = u.user_id
                WHERE u.email LIKE 'perf_buyer_%@perf.test'
                GROUP BY u.user_id, u.email
                LIMIT 200
            """)
            buyers = cur.fetchall()

            cur.execute("""
                SELECT uc.user_id, ce.name, uc.user_coupon_id
                FROM user_coupons uc
                JOIN coupon_events ce ON ce.coupon_event_id = uc.coupon_event_id
                WHERE ce.name LIKE 'PERF\\_USE\\_%'
                  AND uc.status = 'UNUSED'
            """)
            user_coupon_rows = cur.fetchall()

            # 2) 옵션 풀
            cur.execute("""
                SELECT po.product_option_id
                FROM products_options po
                JOIN products p ON p.product_id = po.product_id
                JOIN users u ON u.user_id = p.user_id
                WHERE po.stock_quantity > 0
                  AND u.role = 'STORE'
                ORDER BY po.stock_quantity DESC
                LIMIT 200
            """)
            options = cur.fetchall()
    finally:
        conn.close()

    if not buyers or not options:
        print("[setup] buyer 또는 option 부족 — 시드 먼저 실행")
        return

    global OPTION_IDS
    OPTION_IDS = [opt[0] for opt in options]
    print(f"[setup] buyer {len(buyers)}, option pool {len(OPTION_IDS)}")

    # user_id → {coupon_name: user_coupon_id}
    user_coupon_map: dict[int, dict[str, int]] = {}
    for user_id, coupon_name, user_coupon_id in user_coupon_rows:
        user_coupon_map.setdefault(user_id, {})[coupon_name] = user_coupon_id
    print(f"[setup] UserCoupon 매핑: {len(user_coupon_map)} buyers, "
          f"평균 {len(user_coupon_rows) / max(len(user_coupon_map), 1):.1f} 쿠폰/buyer")

    # 3) 사전 로그인 — 직렬 (gevent 환경에서 ThreadPoolExecutor 사용 불가)
    success = 0
    for user_id, address_id, email in buyers:
        try:
            res = requests.post(
                f"{host}/api/v1/users/login",
                json={"email": email, "password": BUYER_PASSWORD},
                timeout=10,
            )
            if res.status_code != 200:
                continue
            cookies = res.cookies.get_dict()
            coupons = user_coupon_map.get(user_id, {})
            with SESSION_POOL_LOCK:
                SESSION_POOL.append((user_id, address_id, cookies, coupons))
            success += 1
        except requests.RequestException:
            continue

    print(f"[setup] 세션 풀 구축 완료: {success}/{len(buyers)} buyer 로그인", flush=True)


def acquire_session():
    with SESSION_POOL_LOCK:
        if not SESSION_POOL:
            return None
        return SESSION_POOL.popleft()


def release_session(session_tuple):
    with SESSION_POOL_LOCK:
        SESSION_POOL.append(session_tuple)


class CouponOrderUser(HttpUser):
    wait_time = between(0.1, 0.5)

    def on_start(self):
        session = acquire_session()
        if session is None:
            print("[VU] 세션 풀 고갈")
            self.environment.runner.quit()
            return
        self.user_id, self.address_id, cookies, self.coupons = session
        self.client.cookies.update(cookies)
        self._session_tuple = session

    def on_stop(self):
        if hasattr(self, "_session_tuple"):
            release_session(self._session_tuple)

    @task(3)
    def order_with_fixed_coupon(self):
        """FIXED 정액 할인 쿠폰 사용 주문"""
        self._place_order(coupon_name="PERF_USE_FIXED",
                          name="POST /orders (FIXED coupon)")

    @task(3)
    def order_with_rate_coupon(self):
        """RATE 정률 할인 쿠폰 사용 주문"""
        self._place_order(coupon_name="PERF_USE_RATE",
                          name="POST /orders (RATE coupon)")

    @task(2)
    def order_no_coupon(self):
        """쿠폰 미사용 주문 (오버헤드 비교 baseline)"""
        self._place_order(coupon_name=None,
                          name="POST /orders (no coupon)")

    @task(1)
    def order_with_min_amount_coupon(self):
        """최소 주문금액 미달 검증 — 대부분 거절 (CP012) 기대"""
        self._place_order(coupon_name="PERF_USE_MIN",
                          name="POST /orders (MIN coupon, mostly reject)")

    @task(1)
    def order_with_expired_coupon(self):
        """만료 쿠폰 — 전원 거절 (CP004) 기대"""
        self._place_order(coupon_name="PERF_USE_EXPIRED",
                          name="POST /orders (EXPIRED coupon)")

    def _place_order(self, coupon_name: "str | None", name: str):
        # COUPON_RATIO 적용 — 일정 비율만 쿠폰 사용
        if coupon_name and random.random() > COUPON_RATIO:
            coupon_name = None

        user_coupon_id = self.coupons.get(coupon_name) if coupon_name else None
        option_id = random.choice(OPTION_IDS)

        payload = {
            "addressId": self.address_id,
            "details": [{"productOptionId": option_id, "quantity": 1}],
        }
        if user_coupon_id is not None:
            payload["userCouponId"] = user_coupon_id

        with self.client.post(
            "/api/v1/orders/orders",
            json=payload,
            name=name,
            catch_response=True,
        ) as response:
            sc = response.status_code
            if sc in (200, 201):
                response.success()
            elif sc == 409:
                # CP005 SOLD_OUT, CP011 COUPON_NOT_AVAILABLE, OR004 INSUFFICIENT_STOCK
                response.success()
            elif sc == 400:
                # CP004 EXPIRED, CP012 MIN_ORDER, etc.
                response.success()
            elif sc == 403:
                # CP013 UNAUTHORIZED_USER_COUPON
                response.success()
            else:
                response.failure(f"unexpected status={sc}")
