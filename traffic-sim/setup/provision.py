# 트래픽 시뮬레이션 준비 단계(설계 4.2). 공개 API 만으로 봇 스토어 입점·승인, 상품, 쿠폰 이벤트, 구매자와 배송지를 만든다.
# 결과는 상태 파일(기본 ~/.stylehub-sim/)에 남기고, 다시 실행하면 이미 만든 것은 건너뛴다. 7일이 지난 뒤 다시 실행하면 새 날짜의 쿠폰만 더 만든다.
import argparse
import fcntl
import getpass
import json
import os
import time
import urllib.parse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from sim import data
from sim.api import Api, ApiError, check_allowed


def resolve_state_dir(env_value, repo_root=None):
    # 상태 파일엔 봇 계정 비밀번호가 그대로 들어가므로 저장소 밖이 기본값이다.
    # 빈 값·상대경로·저장소 내부 경로를 안전하게 처리하기 위해 별도 함수로 뽑아 테스트한다
    if repo_root is None:
        repo_root = Path(__file__).resolve().parents[2]
    else:
        repo_root = Path(repo_root).resolve()
    raw = env_value or ""
    if not raw:
        return Path.home() / ".stylehub-sim"
    resolved = Path(raw).expanduser().resolve()
    if resolved == repo_root or repo_root in resolved.parents:
        raise SystemExit(f"SIM_STATE_DIR 이 저장소 안입니다({resolved}). 봇 비밀번호가 공개 저장소에 커밋될 수 있어 거부합니다")
    return resolved


# SIM_STATE_DIR 로 바꿀 수 있다(비우면 사용자 홈 아래 .stylehub-sim)
STATE_DIR = resolve_state_dir(os.environ.get("SIM_STATE_DIR"))


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
    # 대소문자만 다른 host 를 다른 파일로 만들지 않도록 소문자로 통일한다
    netloc = urllib.parse.urlsplit(base_url).netloc.lower().replace(":", "_")
    return STATE_DIR / f"provision-{netloc}.json"


def load_state(path):
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"password": data.new_account_password(), "stores": {}, "buyers": {}}


def ensure_state_dir(path):
    # 이미 있는 디렉터리 권한은 건드리지 않는다 — 우리가 새로 만들 때만 0700 으로 만든다
    if not path.parent.exists():
        path.parent.mkdir(parents=True, mode=0o700)


def acquire_lock(path):
    # 준비는 7분 넘게 걸린다. 같은 대상에 두 번 동시에 실행하면 둘 다 빠진 상품·쿠폰을 보고 중복으로 만든다
    ensure_state_dir(path)
    lock_path = path.with_suffix(".lock")
    file = open(lock_path, "w")
    try:
        fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        file.close()
        raise SystemExit(f"같은 대상에 provision 이 이미 실행 중입니다({lock_path}). 끝난 뒤 다시 실행하세요") from None
    return file


def save_state(path, state):
    ensure_state_dir(path)
    temp = path.with_suffix(".tmp")
    payload = json.dumps(state, ensure_ascii=False, indent=1).encode("utf-8")
    # 쓰다 죽어도 절반짜리 파일이 남지 않도록 임시 파일에 쓰고, 짧은 쓰기로 잘리지 않게 fdopen 으로 감싸 flush·fsync 한 뒤 원자적으로 교체한다
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as file:
        file.write(payload)
        file.flush()
        os.fsync(file.fileno())
    os.replace(temp, path)


# 이메일 중복(U001)·이메일·이름 동시경합(U003)만 "이미 가입됨"으로 본다. 이름 중복(U002) 등은 진짜 문제라 그대로 올린다
ALREADY_SIGNED_UP_CODES = {"U001", "U003"}


def sign_up_and_login(api, sign_up, email, password):
    # 이미 가입돼 있으면(재실행) 가입은 건너뛰고 로그인으로 userId 를 얻는다
    conflict_code = None
    try:
        sign_up()
    except ApiError as error:
        if error.status != 409 or error.code not in ALREADY_SIGNED_UP_CODES:
            raise
        conflict_code = error.code
    try:
        return api.login(email, password)["userId"]
    except ApiError as error:
        if conflict_code and error.status == 401:
            # 계정은 있는데 로그인이 안 되면 상태 파일 비밀번호가 서버 실제 비밀번호와 다르다는 뜻이다
            raise SystemExit(f"{email} 계정은 이미 있지만({conflict_code}) 상태 파일의 비밀번호로 로그인할 수 없습니다 — "
                              "상태 파일이 유실되었거나 바뀐 것 같습니다") from None
        raise


def existing_products_by_name(api):
    # 이전 실행이 응답만 못 받았을 수 있으니, 없는 번호가 하나라도 있으면 서버 목록을 먼저 확인해 중복 생성을 막는다
    by_name, cursor = {}, None
    while True:
        page = api.store_products(cursor)
        for item in page["items"]:
            by_name[item["name"]] = item["productId"]
        if not page.get("hasNext"):
            break
        cursor = page.get("nextCursor")
    return by_name


def run(cfg, api_factory, state, save, ask_admin_password, state_file=None):
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
            status = admin.get_store(entry["userId"])["status"]
            if status == "PENDING":
                admin.approve_store(entry["userId"])
            elif status != "APPROVED":
                raise SystemExit(f"스토어 {index}(userId={entry['userId']}) 상태가 {status} 라 승인할 수 없습니다")
            entry["approved"] = True
            save()

        products = entry.setdefault("products", {})
        missing_numbers = [number for number in range(1, cfg.products_per_store + 1) if str(number) not in products]
        if missing_numbers:
            existing_by_name = existing_products_by_name(api)
            for number in missing_numbers:
                info_product = data.product(index, number)
                product_id = existing_by_name.get(info_product["name"])
                if product_id is not None:
                    # 서버엔 이미 만들어져 있다(이전 실행이 응답을 못 받았을 뿐) — 다시 만들지 않고 그대로 채택한다
                    fetched = api.get_product(product_id)
                    products[str(number)] = {"productId": product_id,
                                             "optionIds": [option["productOptionId"] for option in fetched["options"]]}
                else:
                    created = api.register_product(info_product)
                    products[str(number)] = {"productId": created["productId"],
                                             "optionIds": [option["productOptionId"] for option in created["options"]]}
                save()

        coupons = entry.setdefault("coupons", {})
        for event in schedule:
            name = event["name"]
            if name in coupons:
                if coupons[name] == "pending":
                    location = f" ({state_file})" if state_file else ""
                    print(f"경고: 스토어 {index} 쿠폰 '{name}' 이 pending 상태입니다{location} — 이전 실행이 응답을 받지 못했을 수 있습니다. "
                          "DB 에서 해당 쿠폰을 확인해 상태 파일의 이 키를 지우거나 실제 couponEventId 로 바꾸세요", flush=True)
                continue
            coupons[name] = "pending"
            save()
            try:
                coupons[name] = api.create_coupon_event(event)["couponEventId"]
            except ApiError as error:
                if 400 <= error.status < 500:
                    # 명확한 거절이라 쿠폰이 만들어지지 않았다 — pending 표시를 남기지 않는다
                    del coupons[name]
                    save()
                raise
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
        "coupon_events": sum(1 for s in stores for value in s["coupons"].values() if value != "pending"),
        "coupon_pending": sum(1 for s in stores for value in s["coupons"].values() if value == "pending"),
        "buyers_ready": sum(1 for i in range(1, cfg.buyers + 1) if "addressId" in state["buyers"].get(str(i), {})),
    }


def positive_float(value):
    parsed = float(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("0보다 커야 합니다")
    return parsed


def main():
    parser = argparse.ArgumentParser(description="트래픽 시뮬레이션 봇 데이터 준비")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--admin-email")
    parser.add_argument("--stores", type=int, default=5)
    parser.add_argument("--products-per-store", type=int, default=40)
    parser.add_argument("--buyers", type=int, default=2000)
    parser.add_argument("--coupon-days", type=int, default=7)
    parser.add_argument("--signup-rate", type=positive_float, default=5.0)
    parser.add_argument("--server-utc-offset", type=int, default=0)
    args = parser.parse_args()
    check_allowed(args.base_url)

    cfg = Config(stores=args.stores, products_per_store=args.products_per_store, buyers=args.buyers,
                 coupon_days=args.coupon_days, signup_rate=args.signup_rate,
                 server_tz=timezone(timedelta(hours=args.server_utc_offset)), admin_email=args.admin_email,
                 now_kst=datetime.now(data.KST))

    path = state_path(args.base_url)
    lock = acquire_lock(path)  # 이 변수가 살아 있는 동안(main 끝까지)만 잠금이 유지된다
    is_new = not path.exists()
    state = load_state(path)
    if is_new:
        # 첫 가입을 부르기 전에 비밀번호를 먼저 디스크에 남긴다
        save_state(path, state)
        print(f"상태 파일 생성: {path}")

    if not cfg.admin_email:
        unapproved = [i for i in range(1, cfg.stores + 1) if not state["stores"].get(str(i), {}).get("approved")]
        if unapproved:
            raise SystemExit(f"승인되지 않은 스토어가 있어 --admin-email 이 필요합니다: {unapproved}")

    def ask_admin_password():
        if not cfg.admin_email:
            raise SystemExit("승인할 스토어가 있어 --admin-email 이 필요합니다")
        return getpass.getpass(f"관리자({cfg.admin_email}) 비밀번호: ")

    summary = run(cfg, lambda: Api(args.base_url), state, lambda: save_state(path, state), ask_admin_password, state_file=path)
    print(json.dumps(summary, ensure_ascii=False))
    print(f"상태 파일: {path}")


if __name__ == "__main__":
    main()
