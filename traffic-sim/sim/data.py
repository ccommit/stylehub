# 봇이 만드는 데이터의 생성 규칙. 서버 입력 검증(ValidationPatterns, 요청 DTO 제약)을 통과하는 값만 만든다.
import secrets
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


def new_account_password():
    # 봇 계정 공유 비밀번호를 매번 새로 뽑는다 — 고정값이면 추측되기 쉬우므로 secrets 로 무작위 생성
    letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    digits = "0123456789"
    specials = "@$!%*?&"
    pool = letters + digits + specials
    required = [secrets.choice(letters), secrets.choice(digits), secrets.choice(specials)]
    chars = required + [secrets.choice(pool) for _ in range(14 - len(required))]
    secrets.SystemRandom().shuffle(chars)
    return "".join(chars)


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
