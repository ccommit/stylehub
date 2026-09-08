#!/bin/bash
# 운영서버(EC2)에서 실행되는 배포 스크립트.
# Jenkins가 SCP로 옮겨온 뒤 ssh로 직접 실행한다 — heredoc으로 넘기면 로컬 쉘이
# $(...) 를 먼저 해석해버려 문법이 깨지는 문제가 있어 파일로 분리했다.
#
# 배포가 실패하면 이전 버전으로 되돌린다.
# 이전에는 새 산출물을 그대로 덮어써서, 헬스체크가 통과하지 못하면 문제 있는 산출물만 남고
# 서비스가 내려간 상태로 방치됐다. 배포 실패를 알리는 것과 실패했을 때 어떤 상태로 남는지는
# 다른 문제라, 후자를 정하지 않으면 복구를 사람이 손으로 해야 한다.
#
# set -e 를 쓰지 않는 이유: 실패를 감지한 뒤 롤백을 이어서 실행해야 하는데,
# set -e 는 첫 실패에서 스크립트를 끝내버려 롤백까지 도달하지 못한다.
# 대신 set -u 로 오타나 미정의 변수는 계속 잡는다.
set -u

DEPLOY_DIR=/home/ubuntu/stylehub
JAR_NAME=stylehub-0.0.1-SNAPSHOT.jar

JAR="$DEPLOY_DIR/$JAR_NAME"
NEW_JAR="$JAR.new"
PREV_JAR="$JAR.prev"

HEALTH_URL=http://localhost:8080/actuator/health
HEALTH_RETRIES=20
HEALTH_INTERVAL=3

# 헬스체크가 통과할 때까지 재시도한다. 통과하면 0, 끝내 실패하면 1을 반환한다.
# systemctl 이 active 를 보고하는 시점과 애플리케이션이 요청을 받을 수 있는 시점은 다르므로,
# 기동 완료 판정은 systemctl 이 아니라 이 헬스체크가 담당한다.
wait_for_health() {
    for _ in $(seq 1 "$HEALTH_RETRIES"); do
        if curl -fsS "$HEALTH_URL" > /dev/null 2>&1; then
            return 0
        fi
        sleep "$HEALTH_INTERVAL"
    done
    return 1
}

if [ ! -f "$NEW_JAR" ]; then
    echo "배포할 산출물이 없습니다: $NEW_JAR"
    exit 1
fi

# 되돌릴 수 있도록 현재 버전을 보관한다. 최초 배포라면 보관할 대상이 없다.
if [ -f "$JAR" ]; then
    cp -p "$JAR" "$PREV_JAR"
    HAS_PREV=1
else
    echo "이전 버전이 없습니다. 최초 배포로 진행합니다."
    HAS_PREV=0
fi

mv "$NEW_JAR" "$JAR"
sudo systemctl restart stylehub

if wait_for_health; then
    echo "배포 완료 — 헬스체크 통과"
    exit 0
fi

echo "헬스체크 실패 — $((HEALTH_RETRIES * HEALTH_INTERVAL))초 안에 응답하지 않았습니다."

if [ "$HAS_PREV" -eq 0 ]; then
    echo "되돌릴 이전 버전이 없습니다. 서비스가 내려간 상태이므로 수동 확인이 필요합니다."
    exit 1
fi

echo "이전 버전으로 되돌립니다."
mv "$PREV_JAR" "$JAR"
sudo systemctl restart stylehub

if wait_for_health; then
    echo "롤백 완료 — 이전 버전으로 서비스가 복구되었습니다."
else
    echo "롤백 후에도 헬스체크가 실패했습니다. 수동 확인이 필요합니다."
fi

# 롤백에 성공했더라도 이번 배포는 실패다. 파이프라인이 성공으로 표시되면
# 새 버전이 반영된 것으로 오해하게 된다.
exit 1
