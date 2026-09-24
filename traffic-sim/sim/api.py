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


def _normalize_host_entry(entry):
    # 대소문자와 ":포트" 표기를 흡수해서 host 비교가 항상 소문자 호스트만으로 이뤄지게 한다
    entry = entry.strip().lower()
    head, sep, tail = entry.rpartition(":")
    if sep and tail.isdigit():
        return head
    return entry


def check_allowed(base_url):
    # 허용 목록에 없는 서버로는 보내지 않는다(설계 4.7). 운영 서버 주소는 저장소가 아니라 SIM_ALLOWED_HOSTS 로 준다
    host = urllib.parse.urlsplit(base_url).hostname
    if host is None:
        raise SystemExit(f"base_url 은 http:// 또는 https:// 로 시작해야 합니다: {base_url}")
    listed = {_normalize_host_entry(h) for h in os.environ.get("SIM_ALLOWED_HOSTS", "").split(",") if h.strip()}
    if host not in LOCAL_HOSTS | listed:
        raise SystemExit(f"허용되지 않은 대상입니다: {host}. SIM_ALLOWED_HOSTS 에 넣어야 보낼 수 있습니다")


class Api:

    def __init__(self, base_url, timeout=15.0):
        # 진입점뿐 아니라 여기서도 검사해서, 미래의 임시 호출이나 새 진입점이 허용 목록을 빠뜨리지 않게 한다
        check_allowed(base_url)
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))

    def request(self, method, path, body=None, query=None):
        url = self.base_url + API_PREFIX + path
        if query:
            url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            code, message = self._parse_error_body(raw)
            raise ApiError(error.code, code, message) from None

    @staticmethod
    def _parse_error_body(raw):
        # 정상 케이스는 서버가 JSON 오류 바디를 주지만, 프록시가 끼면 HTML 오류 페이지가 올 수 있다 — 그래도 진단 정보는 남긴다
        try:
            payload = json.loads(raw) if raw else {}
            if not isinstance(payload, dict):
                raise ValueError("JSON body is not an object")
        except ValueError:
            snippet = " ".join(raw.decode("utf-8", errors="replace").split())[:200]
            return "", snippet
        return payload.get("code", ""), payload.get("message", "")

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

    # OrderController 는 클래스 레벨 @RequestMapping("/orders") 위에 메서드 레벨 "/orders" 를 또 붙여 실제 경로가 두 번 겹친다
    def place_order(self, address_id, details, user_coupon_id=None):
        return self.request("POST", "/orders/orders", {"addressId": address_id, "details": details, "userCouponId": user_coupon_id})

    def get_order(self, order_id):
        return self.request("GET", f"/orders/orders/{order_id}")

    def pay_success(self, payment_key, pg_order_id, amount):
        return self.request("GET", "/payments/success",
                            query={"paymentKey": payment_key, "orderId": pg_order_id, "amount": amount})

    def update_delivery(self, store_id, order_id, status):
        return self.request("PATCH", f"/orders/stores/{store_id}/orders/{order_id}/delivery", {"orderStatus": status})

    def store_products(self, store_id, cursor=None):
        return self.request("GET", f"/stores/{store_id}/products", query={"cursor": cursor, "pageSize": 100})
