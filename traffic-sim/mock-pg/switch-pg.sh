#!/bin/bash
# 운영 서버에서 실행한다. StyleHub 의 결제 호출 대상을 mock(로컬 Mock PG) 또는 toss(실제 토스)로 바꾸고 재시작한다. 재시작 동안 약 24초 공백이 생긴다.
set -euo pipefail
cd "$(dirname "$0")"
DROP_IN_DIR=/etc/systemd/system/stylehub.service.d

case "${1:-}" in
    mock)
        systemctl is-active --quiet stylehub-mock-pg || { echo "Mock PG 가 떠 있지 않습니다. install.sh 를 먼저 실행하세요"; exit 1; }
        sudo install -d -m 755 "$DROP_IN_DIR"
        sudo install -m 644 mock-pg.conf "$DROP_IN_DIR/mock-pg.conf"
        ;;
    toss)
        sudo rm -f "$DROP_IN_DIR/mock-pg.conf"
        ;;
    *)
        echo "사용법: $0 mock|toss"
        exit 2
        ;;
esac

sudo systemctl daemon-reload
sudo systemctl restart stylehub
for _ in $(seq 1 20); do
    curl -fsS http://127.0.0.1:9081/actuator/health > /dev/null 2>&1 && break
    sleep 3
done
curl -fsS http://127.0.0.1:9081/actuator/health
echo
echo "--- 실행 중인 프로세스가 받은 결제 주소"
MAIN_PID=$(systemctl show -p MainPID --value stylehub)
sudo cat "/proc/$MAIN_PID/environ" | tr '\0' '\n' | grep '^TOSS_PAYMENTS_' || echo "(TOSS_PAYMENTS_* 없음, 토스 기본 주소)"
