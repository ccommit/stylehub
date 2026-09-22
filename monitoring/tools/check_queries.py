#!/usr/bin/env python3
# 대시보드 JSON 의 Prometheus 쿼리를 실제 Prometheus 에 던져, 지표 이름이 틀렸거나 데이터가 없는 패널을 찾는다.
import json
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PROMETHEUS_URL = "http://localhost:9090"
DASHBOARD_DIR = Path(__file__).resolve().parent.parent / "grafana" / "dashboards"


def extract_prometheus_queries(dashboard):
    queries = []
    _collect_from_panels(dashboard.get("panels", []), queries)
    return queries


def _collect_from_panels(panels, queries):
    for panel in panels:
        panel_datasource = panel.get("datasource") or {}
        for target in panel.get("targets", []):
            datasource = target.get("datasource") or panel_datasource
            # 옛/내보낸 대시보드는 datasource 가 문자열(데이터소스 이름)일 수 있다.
            # dict 가 아니면 어떤 타입인지 알 수 없으니 건너뛴다(크래시 방지)
            if not isinstance(datasource, dict):
                continue
            if datasource.get("type") == "prometheus" and target.get("expr"):
                queries.append((panel["title"], target["expr"]))
        nested_panels = panel.get("panels", [])
        if nested_panels:
            _collect_from_panels(nested_panels, queries)


def run_query(expr):
    url = f"{PROMETHEUS_URL}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            body = json.load(response)
    except urllib.error.HTTPError as error:
        try:
            body = json.load(error)
        except json.JSONDecodeError:
            return "ERROR", f"HTTP {error.code}"
    except (socket.timeout, TimeoutError):
        return "ERROR", "Prometheus 응답 시간 초과"
    except urllib.error.URLError as error:
        return "ERROR", f"Prometheus 연결 실패: {error.reason}"
    if body.get("status") != "success":
        return "ERROR", body.get("error", "unknown")
    series = len(body["data"]["result"])
    return ("OK" if series else "EMPTY"), series


def main():
    failed = 0
    for path in sorted(DASHBOARD_DIR.glob("*.json")):
        dashboard = json.loads(path.read_text(encoding="utf-8"))
        for title, expr in extract_prometheus_queries(dashboard):
            status, detail = run_query(expr)
            print(f"[{status}] {path.name} / {title}: {detail}")
            if status != "OK":
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
