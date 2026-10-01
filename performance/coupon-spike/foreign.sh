#!/bin/bash
# 측정과 무관한 CPU 사용량(%)과 Jenkins 컨테이너 CPU(%)를 한 줄로 출력한다.
# 제외: 측정 앱 JVM, Locust/로그인 스크립트, Docker VM(측정 MySQL·Redis가 그 안에서 돈다), ps 자신.
H=$(cd "$(dirname "$0")" && pwd)
EXCL="$(cat $H/app-*.pid 2>/dev/null | tr '\n' '|')NONE"
HOST=$(ps -A -o %cpu=,pid=,comm= | awk -v ex="^($EXCL)$" '
  $2 ~ ex {next}
  /locust|Python|python|com.docker|Docker|Virtualization|qemu|vpnkit|ps$|awk$/ {next}
  {s+=$1} END {printf "%d", s}')
JEN=$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' 2>/dev/null | awk '$1=="jenkins"{gsub("%","",$2); printf "%d", $2}')
echo "$HOST ${JEN:-0}"
