"""회차 폴더의 access.log(서버 기준)와 metrics.log, foreign.log를 요약한다. 지연은 Tomcat 접근 로그의 %D(마이크로초)."""
import json
import re
import sys
from pathlib import Path

d = Path(sys.argv[1])
times = dict(kv.split("=") for kv in d.joinpath("times.txt").read_text().split())
start_at = int(times["start_at"])

coupon, product = [], []
for line in d.joinpath("access.log").read_text().splitlines():
    m = re.match(r'(\d+) (\d+) (\d+) (\d+) "(\w+) (\S+)', line)
    if not m:
        continue
    begin, end, status, us, method, path = int(m[1]), int(m[2]), int(m[3]), int(m[4]), m[5], m[6]
    rec = (begin, end, status, us / 1000.0)
    if method == "POST" and "/issue" in path:
        coupon.append(rec)
    elif method == "GET" and path.startswith("/api/v1/products"):
        product.append(rec)


def pct(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    k = max(0, min(len(xs) - 1, int(round(p / 100 * len(xs) + 0.5)) - 1))
    return round(xs[k], 1)


def stats(xs):
    return {"n": len(xs), "p50": pct(xs, 50), "p99": pct(xs, 99), "max": round(max(xs), 1) if xs else None}


out = {}
ok = [r for r in coupon if r[2] == 200]
rej = [r for r in coupon if 400 <= r[2] < 500]
err = [r for r in coupon if r[2] >= 500]
out["coupon_requests"] = len(coupon)
out["status_counts"] = {s: sum(1 for r in coupon if r[2] == s) for s in sorted({r[2] for r in coupon})}
out["success_ms"] = stats([r[3] for r in ok])
out["reject_ms"] = stats([r[3] for r in rej])
out["server_5xx"] = len(err)
if coupon:
    first = min(r[0] for r in coupon)
    out["first_request_vs_start_at_ms"] = first - start_at
    out["all_requests_arrived_within_ms"] = max(r[0] for r in coupon) - first
    # 요청은 모두 START_AT에 보냈으므로 매진·완료 시각은 START_AT 기준으로 센다(스레드를 기다린 시간 포함)
    if ok:
        out["last_success_after_send_ms"] = max(r[1] for r in ok) - start_at
    out["last_response_after_send_ms"] = max(r[1] for r in coupon) - start_at
    spike_end = max(r[1] for r in coupon)
else:
    spike_end = start_at

base = [r[3] for r in product if start_at - 20000 <= r[0] < start_at]
during = [r[3] for r in product if start_at <= r[0] <= spike_end]
after = [r[3] for r in product if spike_end + 5000 <= r[0]]
out["product_baseline_ms"] = stats(base)
out["product_during_spike_ms"] = stats(during)
out["product_after_ms"] = stats(after)

peak = {"hikaricp_connections_pending": 0, "hikaricp_connections_active": 0, "tomcat_threads_busy_threads": 0}
for line in d.joinpath("metrics.log").read_text().splitlines():
    for k in peak:
        for v in re.findall(k + r'\{[^}]*\}=([\d.E+-]+)', line):
            peak[k] = max(peak[k], float(v))
out["metrics_peak"] = peak

# 클라이언트(Locust) 기준 지연: 서버 스레드를 기다린 시간까지 포함한다
import csv
client = {}
for row in csv.DictReader(open(d / "locust_stats.csv")):
    if row["Name"] in ("issue attempt1", "issue attempt2", "products cursor"):
        client[row["Name"]] = {"n": int(row["Request Count"]), "fail": int(row["Failure Count"]),
                               "p50": float(row["50%"]), "p99": float(row["99%"]), "max": float(row["Max Response Time"])}
out["client_ms"] = client

# 요청 단위 클라이언트 기록: 쿠폰 첫 시도의 지연, 매진 시각, 구간별 상품 조회 지연
recs = []
for p in d.glob("reqlog-*.csv"):
    for line in p.read_text().splitlines():
        t, name, status, ms = line.split(",")
        recs.append((int(t), name, int(status), float(ms)))
if recs:
    c1 = [r for r in recs if r[1] == "issue attempt1"]
    out["client_first_try_ms"] = {
        "success": stats([r[3] for r in c1 if r[2] == 200]),
        "reject": stats([r[3] for r in c1 if 400 <= r[2] < 500]),
        "error": stats([r[3] for r in c1 if r[2] >= 500 or r[2] == 0]),
    }
    ci = [r for r in recs if r[1].startswith("issue")]
    out["client_retries"] = sum(1 for r in ci if r[1] == "issue attempt2")
    succ_done = [r[0] + r[3] for r in ci if r[2] == 200]
    if succ_done:
        out["client_last_success_after_send_ms"] = round(max(succ_done) - start_at)
    all_done = max(r[0] + r[3] for r in ci) if ci else start_at
    out["client_last_coupon_response_after_send_ms"] = round(all_done - start_at)
    pr = [r for r in recs if r[1] == "products cursor"]
    out["client_product_baseline_ms"] = stats([r[3] for r in pr if start_at - 20000 <= r[0] < start_at])
    out["client_product_during_spike_ms"] = stats([r[3] for r in pr if start_at <= r[0] <= all_done])
    out["client_product_after_ms"] = stats([r[3] for r in pr if all_done + 5000 <= r[0]])

f = [l.split() for l in d.joinpath("foreign.log").read_text().splitlines() if len(l.split()) == 3]
if f:
    out["foreign_cpu_max"] = max(int(x[1]) for x in f)
    out["jenkins_cpu_max"] = max(int(x[2]) for x in f)
print(json.dumps(out, ensure_ascii=False, indent=1))
