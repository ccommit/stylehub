#!/bin/bash
# 운영서버(EC2)에서 실행되는 배포 스크립트.
# Jenkins가 SCP로 옮겨온 뒤 ssh로 직접 실행한다 — heredoc으로 넘기면 로컬 쉘이
# $(...) 를 먼저 해석해버려 문법이 깨지는 문제가 있어 파일로 분리했다.
set -e

DEPLOY_DIR=/home/ubuntu/stylehub
JAR_NAME=stylehub-0.0.1-SNAPSHOT.jar

mv "$DEPLOY_DIR/$JAR_NAME.new" "$DEPLOY_DIR/$JAR_NAME"
sudo systemctl restart stylehub
sudo systemctl is-active stylehub

for i in $(seq 1 20); do
    if curl -fsS http://localhost:8080/actuator/health; then
        exit 0
    fi
    sleep 3
done

echo "헬스체크 타임아웃"
exit 1
