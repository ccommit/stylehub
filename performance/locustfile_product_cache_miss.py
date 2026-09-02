"""
시나리오: 상품 조회 (캐시 cold-start / cache miss 측정)

측정 목표
  - 캐시가 비어있는 상태에서 Heavy 부하가 들어왔을 때 DB 가 버티는지
  - cache hit 상태 측정(locustfile_product.py)과 별개로 DB 처리 한계 검증
  - thundering herd 발생 시 응답 시간 분포·실패율·DB 응답 패턴

차이점 vs locustfile_product.py
  - test_start 시 Redis 의 products:* 캐시 키를 모두 삭제 → cold-start 상태로 시작
  - 부하 시작 시점의 모든 요청이 cache miss → DB 직격
  - 시간이 지나면 캐시가 채워져 hit 으로 전환되므로, 짧은 시간(1~2 분) 측정이 의미 있음

실행 방법
  # 사전 조건: Redis 가 localhost:6379 에 떠 있어야 함, redis-py 설치 필요 (pip install redis)

  # cold-start spike: 0→500 users 빠르게 spawn → 빈 캐시에 부하 전체 투입
  locust -f performance/locustfile_product_cache_miss.py \\
         --host=http://localhost:8080 \\
         --headless -u 500 -r 200 -t 1m \\
         --csv=performance/results/product_cache_miss_500

  # cold-start heavy: 1000 users 빠른 spawn
  locust -f performance/locustfile_product_cache_miss.py \\
         --host=http://localhost:8080 \\
         --headless -u 1000 -r 500 -t 1m \\
         --csv=performance/results/product_cache_miss_1000

해석 가이드
  - 측정 시작 직후 P95 가 hit 상태(30~70ms)보다 크게 튀면 정상 — 그 폭이 DB 가 받는 부담
  - 1~2 분 지나면 캐시가 채워져 hit 응답 시간으로 수렴
  - DB 가 못 버틸 때 시그널: 5xx 발생, P99 가 시간이 지나도 안 떨어짐, HikariCP 고갈
  - 같은 부하를 hit 시나리오와 비교했을 때의 P95 격차가 = 캐시가 가려놓던 진짜 DB 부하
"""

import random
import requests
from locust import HttpUser, task, between, events

PRODUCT_IDS: list[int] = []


@events.test_start.add_listener
def setup_cold_start_state(environment, **kwargs):
    """
    테스트 시작 전 1회 실행:
      1) productId 목록 확보 (raw requests 호출 → Locust stats 격리)
      2) Redis 의 products:* 캐시 키를 모두 삭제 → 부하 시작 시점이 cold-start 상태
    유저별 on_start 가 아니므로 N명이어도 init 호출은 1번뿐이다.
    """
    host = environment.host
    if not host:
        print("[setup] host 가 설정되지 않아 productId 목록을 비워둔다.")
        return
    try:
        response = requests.get(f"{host}/api/v1/products?pageSize=50", timeout=5)
        if response.status_code == 200:
            for item in response.json()["items"]:
                PRODUCT_IDS.append(item["productId"])
            print(f"[setup] productId 목록 확보: {len(PRODUCT_IDS)}개")
        else:
            print(f"[setup] 상품 조회 실패 status={response.status_code}, 빈 목록으로 진행")
    except requests.RequestException as e:
        print(f"[setup] 상품 조회 예외: {e}")

    try:
        import redis
        r = redis.Redis(host="localhost", port=6379, decode_responses=True)
        deleted = 0
        for pattern in ("products:firstPage*", "products:detail*"):
            for key in r.scan_iter(pattern, count=500):
                r.delete(key)
                deleted += 1
        print(f"[setup] 캐시 무효화 완료: {deleted}개 키 삭제 (cold-start 상태로 부하 시작)")
    except ImportError:
        print("[setup] redis 패키지 미설치 — pip install redis 필요. 캐시 무효화 건너뜀 (측정 의미 약화)")
    except Exception as e:
        print(f"[setup] 캐시 무효화 예외: {e} (측정 의미 약화)")


class ProductReadUser(HttpUser):
    wait_time = between(0.5, 2.0)

    @task(5)
    def list_products_first_page(self):
        """첫 페이지 조회 — cache miss 시 DB 쿼리 발생, 1~2회 후 캐시 적재되어 hit 으로 전환"""
        self.client.get("/api/v1/products", name="GET /products (first page)")

    @task(5)
    def list_products_next_page(self):
        """다음 페이지 조회 — cursor 가 있어 캐시되지 않음 → 항상 DB 직격"""
        if not PRODUCT_IDS:
            return
        cursor = random.choice(PRODUCT_IDS)
        self.client.get(f"/api/v1/products?cursor={cursor}", name="GET /products (next page)")

    @task(2)
    def list_products_by_store(self):
        """스토어 필터 조회 — 필터 조합별로 캐시 키가 분리되어 cold-start miss 발생 가능성 ↑"""
        store_id = random.randint(1, 3)
        self.client.get(f"/api/v1/products?storeId={store_id}", name="GET /products (by store)")

    @task(2)
    def list_products_by_category(self):
        """카테고리 필터 조회 — by store 와 동일하게 cold-start miss 빈도 ↑"""
        self.client.get(
            "/api/v1/products?mainCategory=TOP&subCategory=T_SHIRT",
            name="GET /products (by category)",
        )

    @task(3)
    def get_product_detail(self):
        """단건 상세 조회 — productId 별로 캐시 키 분리되어 cold-start 시 대부분 miss"""
        if not PRODUCT_IDS:
            return
        product_id = random.choice(PRODUCT_IDS)
        self.client.get(f"/api/v1/products/{product_id}", name="GET /products/{id}")
