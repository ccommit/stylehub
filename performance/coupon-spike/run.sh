#!/bin/bash
# 사용: run.sh <old|new> <100|1000> <tag>
# 앱은 README의 방법대로 미리 띄워 두고, 띄운 JVM의 PID를 app-old.pid / app-new.pid 에 적어 둔다(간섭 게이트가 측정 앱을 빼고 세려고).
H=$(cd "$(dirname "$0")" && pwd)
V=$1; K=$2; TAG=$3
case $V in old) PORT=8190; MPORT=9191; DB=spike_old;; new) PORT=8180; MPORT=9181; DB=spike_new;; esac
OUT=$H/runs/$TAG; mkdir -p $OUT
PY=${PY:-python3}  # requests 가 설치된 파이썬. Locust 와 같은 환경을 쓰면 된다
m() { docker exec -i spike-mysql mysql -uroot -pperf $DB "$@" 2>&1 | grep -v "Using a password"; }

# 1) 쿠폰 상태 초기화 (Redis는 쿠폰 키만 지우고 세션은 남긴다)
m < $H/../seed-test-coupons.sql > /dev/null
docker exec spike-redis sh -c "redis-cli --scan --pattern 'coupon:*' | xargs -r redis-cli DEL" > /dev/null
ID=$(m -N -e "SELECT coupon_event_id FROM coupon_events WHERE name='PERF_C_$K'")
MAXP=$(m -N -e "SELECT MAX(product_id) FROM products")
# 진단용: PREWARM=1 이면 Redis 수량을 미리 만들어 둔다(지금 코드에는 없는 조건. 원인 확인용)
if [ -n "$PREWARM" ]; then
  docker exec spike-redis redis-cli SET coupon:counter:$ID $K EX 864000 > /dev/null
  echo "prewarm=1 counter=$(docker exec spike-redis redis-cli GET coupon:counter:$ID)" > $OUT/prewarm.txt
fi

# 2) 사전 로그인
$PY $H/login.py http://127.0.0.1:$PORT $OUT/sessions.json 1000 > $OUT/login.txt

# 3) 간섭 게이트: 외부 CPU 250% 미만, Jenkins 10% 미만이 5초 간격 3번 연속
ok=0; waited=0
while [ $ok -lt 3 ]; do
  read HC JC <<< "$($H/foreign.sh)"
  if [ "$HC" -lt 250 ] && [ "$JC" -lt 10 ]; then ok=$((ok+1)); else ok=0; fi
  [ $ok -lt 3 ] && { sleep 5; waited=$((waited+5)); }
  [ $waited -ge 1800 ] && { echo "GATE TIMEOUT"; exit 2; }
done
echo "gate_waited_sec=$waited" > $OUT/gate.txt

( while true; do echo "$(date +%s) $($H/foreign.sh)"; sleep 2; done ) > $OUT/foreign.log 2>&1 &
MON=$!
( while true; do echo "$($PY -c 'import time;print(int(time.time()*1000))') $(curl -s 127.0.0.1:$MPORT/actuator/prometheus | grep -E '^(hikaricp_connections_pending|hikaricp_connections_active|tomcat_threads_busy_threads)' | awk '{printf "%s=%s ", $1, $2}')"; sleep 1; done ) > $OUT/metrics.log 2>&1 &
MET=$!

# 4) 부하: 시작 25초 뒤 쿠폰 1,000명이 동시에 첫 요청, 전체 60초
T0=$($PY -c 'import time;print(int(time.time()*1000))')
START_AT=$((T0 + 25000))
echo "t0=$T0 start_at=$START_AT event_id=$ID" > $OUT/times.txt
SPIKE_SESSIONS=$OUT/sessions.json SPIKE_EVENT_ID=$ID SPIKE_START_AT=$START_AT SPIKE_MAX_PRODUCT_ID=$MAXP SPIKE_REQLOG_DIR=$OUT SPIKE_COUNTER_FILE=$OUT/session-counter \
  ${LOCUST:-locust} -f $H/locust_spike.py --host=http://127.0.0.1:$PORT --headless --processes 4 \
  -u 1050 -r 1050 -t 60s --only-summary --csv $OUT/locust --html $OUT/report.html > $OUT/locust.log 2>&1
T1=$($PY -c 'import time;print(int(time.time()*1000))')
echo "t1=$T1" >> $OUT/times.txt
kill $MON $MET 2>/dev/null
sleep 3

# 5) 정합성
{
  m -N -e "SELECT CONCAT('issue_count=',issue_count,' db_issued_count=',issued_count,' user_coupons=',(SELECT COUNT(*) FROM user_coupons uc WHERE uc.coupon_event_id=ce.coupon_event_id)) FROM coupon_events ce WHERE coupon_event_id=$ID"
  echo "duplicates=$(m -N -e "SELECT COUNT(*) FROM (SELECT user_id FROM user_coupons WHERE coupon_event_id=$ID GROUP BY user_id HAVING COUNT(*)>1) d")"
  echo "redis_counter=$(docker exec spike-redis redis-cli GET coupon:counter:$ID) redis_issued=$(docker exec spike-redis redis-cli SCARD coupon:issued_users:$ID)"
  cat $OUT/login.txt $OUT/gate.txt
} > $OUT/consistency.txt

# 6) 접근 로그에서 이번 회차 구간만 잘라 둔다
awk -v a=$T0 -v b=$T1 '$1>=a && $1<=b' $H/logs-$V/access_log.log > $OUT/access.log
$PY $H/analyze.py $OUT > $OUT/summary.txt
cat $OUT/consistency.txt $OUT/summary.txt
