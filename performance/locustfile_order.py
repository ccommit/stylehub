"""
시나리오 2: 주문 → 결제 (비관적 락 한계 측정)

측정 목표
  - 비관적 락(SELECT FOR UPDATE) 기반 재고 차감의 TPS 한계
  - 동일 옵션 동시 주문 시 락 대기 시간 분포
  - 동시성 100/300 user 구간에서 HikariCP 풀 사용률 추이
  - 정합성: 주문 수량 합 == 차감된 재고

측정 범위 (placeOrder 만)
  - 결제 승인(GET /payments/success) 은 토스 sandbox API 호출이 발생하므로 부하 테스트 부적합
  - 시나리오 2 의 핵심(비관적 락 + 재고 차감) 은 placeOrder 단계에 모두 있어 의미 있는 측정 가능
  - PG mock 모드(todo.md) 가 도입되면 confirmPayment 까지 확장 가능

사전 준비
  1. seed-test-buyers.sql 실행
       mysql -u root -p stylehub < performance/seed-test-buyers.sql
  2. 상품/옵션 데이터 존재 확인 (product-bulk-insert.sql 로 사전 생성 가정)
  3. pymysql / requests / locust 설치
       pip install pymysql locust requests

실행 방법
  # 웹 UI
  locust -f performance/locustfile_order.py --host=http://localhost:8080

  # Headless 부하 단계 (시나리오 문서 기준)
  # 기본 — 정합성 확인
  locust -f performance/locustfile_order.py --host=http://localhost:8080 \\
         --headless -u 10 -r 5 -t 2m \\
         --csv=performance/results/order_baseline_10

  # 같은 옵션 집중 — 락 경합
  locust -f performance/locustfile_order.py --host=http://localhost:8080 \\
         --headless -u 50 -r 25 -t 3m \\
         --csv=performance/results/order_concentrated_50

  # 옵션 분산 — 한계 TPS 탐색
  locust -f performance/locustfile_order.py --host=http://localhost:8080 \\
         --headless -u 100 -r 50 -t 5m \\
         --csv=performance/results/order_spread_100

  # 스트레스
  locust -f performance/locustfile_order.py --host=http://localhost:8080 \\
         --headless -u 300 -r 100 -t 5m \\
         --csv=performance/results/order_stress_300

검증 쿼리 (테스트 후)
  -- 주문 수량 합 vs 차감된 재고 (정합성)
  SELECT po.product_option_id, po.stock_quantity,
         (SELECT SUM(od.quantity) FROM order_details od
          WHERE od.product_option_id = po.product_option_id) AS sold
  FROM products_options po
  WHERE po.product_option_id IN ({테스트 옵션 ID 목록});
  -- 기대: stock_quantity + sold == 초기 재고

  -- 주문 상태별 카운트
  SELECT order_status, COUNT(*) FROM orders
  WHERE created_at >= '{테스트 시작 시각}'
  GROUP BY order_status;

  -- 락 대기로 인한 timeout/실패 점검 (HikariCP 메트릭)
  -- /actuator/metrics/hikaricp.connections.timeout
"""
import os
import random
import threading
from collections import deque

import requests
from locust import HttpUser, task, between, events


# ============================================
# 환경 변수 (실행 시 override 가능)
# ============================================
DB_HOST = os.environ.get("PERF_DB_HOST", "localhost")
DB_PORT = int(os.environ.get("PERF_DB_PORT", "3306"))
DB_USER = os.environ.get("PERF_DB_USER", "root")
DB_PASSWORD = os.environ.get("PERF_DB_PASSWORD", "")
DB_NAME = os.environ.get("PERF_DB_NAME", "stylehub")

BUYER_PASSWORD = "Test1234!"
HOT_OPTION_RATIO = float(os.environ.get("PERF_HOT_OPTION_RATIO", "0.5"))
# 0.5 = 절반의 주문은 hot option(1개) 으로 몰아서 락 경합 유도
# 1.0 = 모든 주문이 단일 hot option (가장 강한 경합)
# 0.0 = 모든 주문이 무작위 옵션 (분산)


# ============================================
# 글로벌 상태 — test_start 에서 채움
# ============================================
SESSION_POOL: "deque[tuple[int, int, dict]]" = deque()
SESSION_POOL_LOCK = threading.Lock()
OPTION_IDS: list[int] = []
HOT_OPTION_ID: int | None = None


@events.test_start.add_listener
def setup_session_pool(environment, **kwargs):
    """
    테스트 시작 전 1회 실행 — 모든 setup 호출은 raw requests / pymysql 로
    Locust stats 격리. 부하 측정에는 placeOrder 만 카운트된다.

    절차:
      1) DB 에서 perf buyer 의 (user_id, address_id) 쌍 조회
      2) 사용 가능한 product_option_id 목록 + 가장 재고 많은 hot option 선정
      3) 모든 buyer 를 raw POST /users/login 으로 동시 로그인 → 세션 쿠키 확보
      4) (user_id, address_id, cookies) 튜플을 SESSION_POOL deque 에 적재
    """
    host = environment.host
    if not host:
        print("[setup] host 가 설정되지 않아 셋업을 건너뜁니다.")
        return

    try:
        import pymysql
    except ImportError:
        print("[setup] pymysql 미설치 — pip install pymysql 필요. 셋업 중단.")
        return

    # 1) DB 에서 buyer / option 정보 수집
    conn = pymysql.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER,
        password=DB_PASSWORD, db=DB_NAME, charset="utf8mb4",
    )
    try:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT u.user_id, MIN(a.address_id) AS address_id, u.email
                FROM users u
                JOIN addresses a ON a.user_id = u.user_id
                WHERE u.email LIKE 'perf_buyer_%@perf.test'
                GROUP BY u.user_id, u.email
            """)
            buyers = cur.fetchall()  # [(user_id, address_id, email), ...]

            # 옵션 풀은 store(user) FK 가 살아있는 옵션만 선택한다.
            # 시드 데이터에 dangling FK 가 섞여 있어 (products.user_id → users 미존재) 이를 거르지 않으면
            # OrderService 가 store 정보를 LAZY 로드할 때 5xx 가 발생해 시스템 한계가 아닌 데이터 결함이 측정에 노출된다.
            cur.execute("""
                SELECT po.product_option_id, po.stock_quantity
                FROM products_options po
                JOIN products p ON p.product_id = po.product_id
                JOIN users u ON u.user_id = p.user_id
                WHERE po.stock_quantity > 0
                  AND u.role = 'STORE'
                ORDER BY po.stock_quantity DESC
                LIMIT 200
            """)
            options = cur.fetchall()  # [(option_id, stock), ...]
    finally:
        conn.close()

    if not buyers:
        print("[setup] perf buyer 가 0명입니다. seed-test-buyers.sql 을 먼저 실행해주세요.")
        return
    if not options:
        print("[setup] 재고 있는 product option 이 없습니다. product/option 시드 필요.")
        return

    global OPTION_IDS, HOT_OPTION_ID
    OPTION_IDS = [opt[0] for opt in options]
    HOT_OPTION_ID = options[0][0]  # 재고 가장 많은 옵션을 hot option 으로
    print(f"[setup] buyer {len(buyers)}명, option pool {len(OPTION_IDS)}개, "
          f"hot option_id={HOT_OPTION_ID} (stock={options[0][1]})")

    # 2) 모든 buyer 를 미리 로그인 → 세션 쿠키 풀 구축
    success = 0
    for user_id, address_id, email in buyers:
        try:
            res = requests.post(
                f"{host}/api/v1/users/login",
                json={"email": email, "password": BUYER_PASSWORD},
                timeout=10,
            )
            if res.status_code != 200:
                print(f"[setup] login 실패 user_id={user_id} status={res.status_code}")
                continue
            cookies = res.cookies.get_dict()
            with SESSION_POOL_LOCK:
                SESSION_POOL.append((user_id, address_id, cookies))
            success += 1
        except requests.RequestException as e:
            print(f"[setup] login 예외 user_id={user_id} err={e}")

    print(f"[setup] 세션 풀 구축 완료: {success}/{len(buyers)}명 로그인 성공")


def acquire_session() -> "tuple[int, int, dict] | None":
    """SESSION_POOL 에서 한 사용자 분량을 꺼낸다 (thread-safe)."""
    with SESSION_POOL_LOCK:
        if not SESSION_POOL:
            return None
        return SESSION_POOL.popleft()


def release_session(session_tuple) -> None:
    """사용자 종료 시 세션을 풀에 반납해 다음 VU 가 재사용한다."""
    with SESSION_POOL_LOCK:
        SESSION_POOL.append(session_tuple)


class OrderUser(HttpUser):
    # wait_time 단축 측정용: 부하 클라이언트의 wait_time 천장 제거 → 시스템의 진짜 RPS 한계 측정
    # 기존 between(0.5, 2.0) 은 50 users 시 이론 RPS 40 천장에 갇혀 락 제거 효과가 안 보였음
    # 0.05~0.2 로 단축하면 50 users 가 full throttle 에 가까워져 시스템 한계 측정 가능
    wait_time = between(0.05, 0.2)

    def on_start(self):
        """각 VU 가 시작 시 세션 풀에서 자기 credentials 를 받아온다."""
        session = acquire_session()
        if session is None:
            print("[VU] 세션 풀 고갈 — buyer 수보다 많은 VU 를 띄우셨거나 setup 실패")
            self.environment.runner.quit()
            return
        self.user_id, self.address_id, cookies = session
        self.client.cookies.update(cookies)
        # on_stop 에서 반납하기 위해 보관
        self._session_tuple = session

    def on_stop(self):
        if hasattr(self, "_session_tuple"):
            release_session(self._session_tuple)

    @task(8)
    def place_order_hot_option(self):
        """가중치 8: hot option 에 집중 → 락 경합 유도"""
        if HOT_OPTION_ID is None:
            return
        # HOT_OPTION_RATIO 에 따라 hot option vs 무작위 분배
        if random.random() < HOT_OPTION_RATIO:
            option_id = HOT_OPTION_ID
        else:
            option_id = random.choice(OPTION_IDS)

        self._post_order(option_id, name="POST /orders (hot)")

    @task(2)
    def place_order_random_option(self):
        """가중치 2: 무작위 옵션 → 분산 부하 (한계 TPS 측정)"""
        option_id = random.choice(OPTION_IDS)
        self._post_order(option_id, name="POST /orders (random)")

    def _post_order(self, option_id: int, name: str):
        payload = {
            "addressId": self.address_id,
            "details": [
                {"productOptionId": option_id, "quantity": 1}
            ],
        }
        with self.client.post(
            "/api/v1/orders/orders",
            json=payload,
            name=name,
            catch_response=True,
        ) as response:
            # 의도된 실패(409 INSUFFICIENT_STOCK) 와 진짜 에러(5xx, timeout) 분리
            if response.status_code == 200 or response.status_code == 201:
                response.success()
            elif response.status_code == 409:
                # 재고 소진은 비즈니스 정상 응답 — Locust 통계에서 success 처리
                response.success()
            else:
                response.failure(f"unexpected status={response.status_code}")
