"""runs/<v>-<k>-r<i>/summary.txt (RUNS_DIR 로 바꿀 수 있다) 를 모아 지표별 회차 값과 중앙값을 표로 낸다."""
import json
import os
import statistics
import sys
from pathlib import Path

H = Path(os.environ.get("RUNS_DIR", Path(__file__).parent / "runs"))

METRICS = [
    ("발급 수(DB)", lambda d, c: c.get("user_coupons")),
    ("5xx", lambda d, c: d["server_5xx"]),
    ("재시도", lambda d, c: d.get("client_retries")),
    ("마지막 발급 응답까지(ms, 보낸 시각 기준)", lambda d, c: d.get("client_last_success_after_send_ms")),
    ("마지막 쿠폰 응답까지(ms)", lambda d, c: d.get("client_last_coupon_response_after_send_ms")),
    ("성공 P50(사용자 기준)", lambda d, c: d["client_first_try_ms"]["success"]["p50"]),
    ("성공 P99(사용자 기준)", lambda d, c: d["client_first_try_ms"]["success"]["p99"]),
    ("거절 P50(사용자 기준)", lambda d, c: d["client_first_try_ms"]["reject"]["p50"]),
    ("거절 P99(사용자 기준)", lambda d, c: d["client_first_try_ms"]["reject"]["p99"]),
    ("성공 P99(서버 처리)", lambda d, c: d["success_ms"]["p99"]),
    ("거절 P99(서버 처리)", lambda d, c: d["reject_ms"]["p99"]),
    ("상품 조회 P50 평소", lambda d, c: d["client_product_baseline_ms"]["p50"]),
    ("상품 조회 P99 평소", lambda d, c: d["client_product_baseline_ms"]["p99"]),
    ("상품 조회 P50 몰리는 중", lambda d, c: d["client_product_during_spike_ms"]["p50"]),
    ("상품 조회 P99 몰리는 중", lambda d, c: d["client_product_during_spike_ms"]["p99"]),
    ("DB 연결 대기 최대", lambda d, c: d["metrics_peak"]["hikaricp_connections_pending"]),
    ("Tomcat 바쁜 스레드 최대", lambda d, c: d["metrics_peak"]["tomcat_threads_busy_threads"]),
    ("외부 CPU 최대(%)", lambda d, c: d.get("foreign_cpu_max")),
]


def consistency(p):
    out = {}
    for tok in p.read_text().split():
        if "=" in tok:
            k, v = tok.split("=", 1)
            out[k] = int(v) if v.isdigit() else v
    return out


# 사용: aggregate.py [왼쪽 접두어] [오른쪽 접두어]  예) old+old2 new / new diag-prewarm  (+ 로 여러 묶음을 합친다)
LEFT, RIGHT = (sys.argv[1:3] + ["old", "new"])[:2] if len(sys.argv) > 2 else ("old", "new")
for k in ["100", "1000"]:
    print(f"\n## {k}장 / 1,000명")
    rows = {}
    for v in (LEFT, RIGHT):
        for r in sorted(x for pre in v.split("+") for x in H.glob(f"{pre}-{k}-r*")):
            d = json.loads((r / "summary.txt").read_text())
            c = consistency(r / "consistency.txt")
            rows.setdefault(v, []).append([f(d, c) for _, f in METRICS])
    print(f"| 지표 | {LEFT} (회차) | 중앙값 | {RIGHT} (회차) | 중앙값 |")
    print("|---|---|---|---|---|")
    for i, (name, _) in enumerate(METRICS):
        cells = []
        for v in (LEFT, RIGHT):
            vals = [row[i] for row in rows.get(v, [])]
            nums = [x for x in vals if isinstance(x, (int, float))]
            cells.append(", ".join("-" if x is None else f"{x:g}" if isinstance(x, (int, float)) else str(x) for x in vals))
            cells.append(f"{statistics.median(nums):g}" if nums else "-")
        print(f"| {name} | " + " | ".join(cells) + " |")
