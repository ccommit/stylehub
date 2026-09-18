#!/bin/bash
# 운영 서버에서 실행한다. Mock PG 를 설치해 127.0.0.1:18090 으로 띄운다. StyleHub 의 결제 주소는 바꾸지 않는다(switch-pg.sh 가 맡는다).
set -euo pipefail
cd "$(dirname "$0")"
command -v python3 > /dev/null || { echo "python3 가 없습니다"; exit 1; }

sudo install -d -m 755 /opt/stylehub-mock-pg
sudo install -m 644 mock_pg.py /opt/stylehub-mock-pg/mock_pg.py
sudo install -m 644 stylehub-mock-pg.service /etc/systemd/system/stylehub-mock-pg.service
# switch-pg.sh 와 mock-pg.conf 도 /opt 에 둔다 — /tmp 는 재부팅·정리로 비워질 수 있어 되돌리는 도구가 사라지면 안 된다
sudo install -m 755 switch-pg.sh /opt/stylehub-mock-pg/switch-pg.sh
sudo install -m 644 mock-pg.conf /opt/stylehub-mock-pg/mock-pg.conf
sudo systemctl daemon-reload
# 다시 설치할 때도 새 mock_pg.py 를 읽도록 항상 재시작한다
sudo systemctl enable stylehub-mock-pg
sudo systemctl restart stylehub-mock-pg
sleep 2

echo "--- 수신 주소 (127.0.0.1:18090 만 나와야 한다)"
ss -tln | grep ':18090' || { echo "18090 에서 수신하지 않습니다. journalctl -u stylehub-mock-pg 를 확인하세요"; exit 1; }
echo "--- 조회 확인 (404 가 정상)"
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18090/v1/payments/orders/install-check
echo "--- 메모리 사용량(KB)"
ps -o rss=,comm= -p "$(systemctl show -p MainPID --value stylehub-mock-pg)"
