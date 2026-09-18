#!/bin/bash
# 운영 서버에서 한 번 실행한다. node_exporter 를 설치하고 127.0.0.1:9100 에만 열어 서버 밖으로 노출하지 않는다.
set -euo pipefail

sudo apt-get update -qq
# 설치 직후 몇 초는 패키지 기본값(모든 인터페이스)으로 열린다. 아래 restart 가 127.0.0.1 로 바꿀 때까지의 짧은 창이며, 보안 그룹이 9100을 막고 있어 서버 밖에서는 닿지 않는다.
sudo apt-get install -y --no-install-recommends prometheus-node-exporter

echo 'ARGS="--web.listen-address=127.0.0.1:9100"' | sudo tee /etc/default/prometheus-node-exporter > /dev/null
sudo systemctl restart prometheus-node-exporter
sleep 2

echo "--- 수신 주소 (127.0.0.1:9100 만 나와야 한다)"
ss -tln | grep ':9100'
echo "--- 지표 확인"
curl -fsS http://127.0.0.1:9100/metrics | grep '^node_memory_MemAvailable_bytes'
echo "--- 메모리 사용량"
ps -o rss=,comm= -p "$(systemctl show -p MainPID --value prometheus-node-exporter)"
