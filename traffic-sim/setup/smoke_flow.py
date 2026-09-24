# 준비 단계가 만든 구매자와 상품으로 주문 → Mock 결제 → 배송 완료까지 API 만으로 한 번 흘려 본다(2단계 완료 조건).
import argparse
import sys
import uuid

from sim import data
from sim.api import Api, ApiError, check_allowed
from setup.provision import load_state, state_path

DELIVERY_STEPS = ["PREPARING", "SHIPPING", "DELIVERED"]
MAX_PAYMENT_ATTEMPTS = 3


def pick_buyer(state):
    # 배송지까지 준비된 첫 구매자를 쓴다
    for index in sorted(int(key) for key in state["buyers"]):
        entry = state["buyers"][str(index)]
        if "addressId" in entry:
            return index, entry
    return None, None


def pick_in_stock_option(api, state):
    # 1번 스토어부터 순서대로, 재고가 있는 옵션을 가진 첫 상품을 찾는다
    for store_index in sorted(int(key) for key in state["stores"]):
        store = state["stores"][str(store_index)]
        for number in sorted(int(key) for key in store.get("products", {})):
            product = api.get_product(store["products"][str(number)]["productId"])
            option = next((o for o in product["options"] if o["stockQuantity"] > 0), None)
            if option:
                return store_index, store, option
    return None, None, None


def main():
    parser = argparse.ArgumentParser(description="주문·결제·배송 스모크 흐름")
    parser.add_argument("--base-url", required=True)
    args = parser.parse_args()
    check_allowed(args.base_url)

    path = state_path(args.base_url)
    if not path.exists():
        raise SystemExit(f"상태 파일이 없습니다: {path}. provision 을 먼저 실행하세요")
    state = load_state(path)
    if not state["stores"] or not state["buyers"]:
        raise SystemExit("상태 파일에 스토어나 구매자가 없습니다. provision 을 먼저 실행하세요")
    password = state["password"]

    buyer_index, buyer = pick_buyer(state)
    if buyer is None:
        raise SystemExit("배송지가 준비된 구매자가 없습니다. provision 을 먼저 실행하세요")

    buyer_api = Api(args.base_url)
    buyer_api.login(data.buyer(buyer_index)["email"], password)

    store_index, store, option = pick_in_stock_option(buyer_api, state)
    if option is None:
        print("실패: 재고가 있는 상품 옵션을 찾지 못했습니다")
        sys.exit(1)

    order = buyer_api.place_order(buyer["addressId"], [{"productOptionId": option["productOptionId"], "quantity": 1}])

    payment = None
    for attempt in range(1, MAX_PAYMENT_ATTEMPTS + 1):
        try:
            payment = buyer_api.pay_success(f"mock_{uuid.uuid4().hex}", order["pgOrderId"], order["finalAmount"])
            break
        except ApiError as error:
            if error.code == "PM011":
                print("실패: 승인 결과를 알 수 없습니다(PM011). 결제는 IN_PROGRESS 로 남아 있고, "
                      "서버가 주문 만료 시점에 PG 와 대사합니다. 나중에 다시 실행하세요")
                sys.exit(1)
            if error.code != "PM004":
                raise
            # Mock PG 는 설정상 2% 를 거절한다. 거절되면 결제는 READY 로 되돌아가므로 같은 주문에 새 paymentKey 로 다시 시도한다
            print(f"시도 {attempt}: Mock PG 거절, 같은 주문으로 다시 시도", flush=True)
    if payment is None or payment["status"] != "DONE":
        print(f"실패: 결제 승인되지 않음 {payment}")
        sys.exit(1)
    print(f"주문 {order['orderId']} ({order['pgOrderId']}) 결제 {payment['paymentId']} 승인, {order['finalAmount']}원")

    store_api = Api(args.base_url)
    store_api.login(data.store(store_index)["email"], password)
    for status in DELIVERY_STEPS:
        store_api.update_delivery(order["orderId"], status)
        print(f"배송 상태 → {status}")

    final = buyer_api.get_order(order["orderId"])["orderStatus"]
    if final != "DELIVERED":
        print(f"실패: 최종 주문 상태 {final}")
        sys.exit(1)
    print("성공: 주문 → Mock 결제 → 배송 완료")


if __name__ == "__main__":
    main()
