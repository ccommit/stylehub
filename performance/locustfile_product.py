"""
시나리오 1: 상품 조회 (읽기 부하)

측정 목표
  - P95 < 300ms, P99 < 500ms, 에러율 < 0.1%
  - 커서 페이지네이션 + JOIN FETCH 의 실제 성능
  - 필터 조합(스토어/카테고리)에 따른 쿼리 플랜 변화 관찰

실행 방법
  # 웹 UI 모드 (부하 단계별로 수동 제어)
  locust -f performance/locustfile_product.py --host=http://localhost:8080
  → 브라우저 http://localhost:8089 접속

  # Headless 모드 (CSV 결과 저장 — Warm-up 예시)
  locust -f performance/locustfile_product.py --host=http://localhost:8080 \\
         --headless -u 10 -r 2 -t 30s \\
         --csv=performance/results/product_warmup

부하 단계 (시나리오 문서 기준)
  단계      Users  Spawn  Duration
  Warm-up    10    2/s     30s
  Light      50    10/s    2m
  Medium    200    20/s    3m
  Heavy     500    50/s    5m
  Spike    1000   100/s    1m
"""
import random
import requests
from locust import HttpUser, task, between, events


# 모든 가상 유저가 공유하는 productId 목록.
# test_start 리스너가 테스트 시작 시 1번만 채우므로 read-only 로 안전하게 공유 가능하다.
PRODUCT_IDS: list[int] = []


@events.test_start.add_listener
def fetch_product_ids(environment, **kwargs):
    """
    모든 유저가 시작되기 전 1번만 실행되는 셋업 단계.
    Locust client 가 아닌 raw requests 로 호출해 측정 stats 에서 격리한다.
    유저별 on_start 에 두면 N명 = N번 init 호출이 stats 에 섞여 RPS/응답시간을 왜곡한다.
    """
    global PRODUCT_IDS
    host = environment.host
    if not host:
        print("[setup] host 가 설정되지 않아 productId 목록을 비워둔다.")
        PRODUCT_IDS = []
        return

    try:
        response = requests.get(f"{host}/api/v1/products?pageSize=50", timeout=5)
        if response.status_code == 200:
            PRODUCT_IDS = [item["productId"] for item in response.json().get("items", [])]
            print(f"[setup] productId 목록 확보: {len(PRODUCT_IDS)}개")
        else:
            print(f"[setup] 상품 조회 실패 status={response.status_code}, 빈 목록으로 진행")
            PRODUCT_IDS = []
    except requests.RequestException as e:
        print(f"[setup] 상품 조회 예외: {e}, 빈 목록으로 진행")
        PRODUCT_IDS = []


class ProductReadUser(HttpUser):
    # 가상 유저가 요청 간 대기하는 시간 (초). 실제 사용자의 클릭/스크롤 간격을 흉내낸다.
    # 너무 짧으면 비현실적 봇 부하가 되어 측정 왜곡이 생긴다.
    wait_time = between(0.5, 2.0)

    @task(5)   # 가중치 5: 가장 빈번한 호출 패턴
    def list_products_first_page(self):
        """첫 페이지 조회 (커서 없음)"""
        self.client.get("/api/v1/products", name="GET /products (first page)")

    @task(5)
    def list_products_next_page(self):
        """다음 페이지 조회 (커서 기반)"""
        if not PRODUCT_IDS:
            return
        cursor = random.choice(PRODUCT_IDS)
        self.client.get(
            f"/api/v1/products?cursor={cursor}",
            name="GET /products (next page)",
        )

    @task(2)
    def list_products_by_store(self):
        """스토어 필터 조회"""
        # 실제 존재하는 store_id 범위로 조정 필요 (시드 데이터의 스토어 유저 id)
        store_id = random.randint(1, 3)
        self.client.get(
            f"/api/v1/products?storeId={store_id}",
            name="GET /products (by store)",
        )

    @task(2)
    def list_products_by_category(self):
        """카테고리 필터 조회 (대·소분류 조합)"""
        self.client.get(
            "/api/v1/products?mainCategory=TOP&subCategory=T_SHIRT",
            name="GET /products (by category)",
        )

    @task(3)
    def get_product_detail(self):
        """단건 상세 조회 (JOIN FETCH 성능)"""
        if not PRODUCT_IDS:
            return
        product_id = random.choice(PRODUCT_IDS)
        self.client.get(
            f"/api/v1/products/{product_id}",
            name="GET /products/{id}",
        )
