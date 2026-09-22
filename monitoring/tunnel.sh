#!/bin/bash
# 운영 서버의 actuator(9081)와 node_exporter(9100)를 맥 19081, 19100 으로 잇는다. 끊기면 10초 뒤 다시 붙는다.
set -u
cd "$(dirname "$0")"

[ -f ./.env ] || { echo "monitoring/.env 가 없습니다. .env.example 을 복사해 채우세요" >&2; exit 1; }
set -a
source ./.env
set +a
: "${STYLEHUB_SSH_HOST:?monitoring/.env 에 STYLEHUB_SSH_HOST 필요}"
: "${STYLEHUB_SSH_KEY:?monitoring/.env 에 STYLEHUB_SSH_KEY 필요}"

SSH_PID=""

# 시그널을 받으면 ssh 자식을 정리하고 종료한다. 이게 없으면 Ctrl+C 한 번으로는
# while 루프만 끊기고 ssh 는 살아남아 19081/19100 을 계속 붙들 수 있다.
cleanup() {
    if [ -n "$SSH_PID" ] && kill -0 "$SSH_PID" 2>/dev/null; then
        kill "$SSH_PID" 2>/dev/null
        wait "$SSH_PID" 2>/dev/null
    fi
    exit "$1"
}
trap 'cleanup 130' INT
trap 'cleanup 143' TERM

while true; do
    ssh -N -i "$STYLEHUB_SSH_KEY" \
        -o ExitOnForwardFailure=yes \
        -o ServerAliveInterval=15 \
        -o ServerAliveCountMax=3 \
        -o StrictHostKeyChecking=accept-new \
        -o Compression=yes \
        -o BatchMode=yes \
        -o IdentitiesOnly=yes \
        -L 127.0.0.1:19081:127.0.0.1:9081 \
        -L 127.0.0.1:19100:127.0.0.1:9100 \
        "$STYLEHUB_SSH_HOST" &
    SSH_PID=$!
    wait "$SSH_PID"
    SSH_PID=""
    echo "$(date '+%F %T') 터널 끊김, 10초 뒤 다시 연결"
    sleep 10
done
