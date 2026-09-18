# 관측 스택 (로컬 맥)

설계: `docs/traffic-sim/design.md` 2장. 운영 서버 지표를 SSH 터널로 가져와 맥의 Prometheus와 Grafana에서 본다.

아래 명령은 모두 `monitoring/` 안에서 실행한다(`cd monitoring`).

## 처음 한 번

1. `cp .env.example .env` 후 값을 채운다. `.env`는 커밋되지 않는다.
2. 운영 서버에 node_exporter를 설치한다: `server/install-node-exporter.sh`를 서버로 복사해 실행

`GRAFANA_ADMIN_PASSWORD`는 `grafana-data` 볼륨이 처음 만들어질 때만 적용된다. 나중에 `.env`를 고쳐도 이미 만들어진 비밀번호는 바뀌지 않는다.

## 실행

```bash
./tunnel.sh            # 별도 터미널에서 켜 둔다
docker compose up -d
```

- Prometheus 수집 대상: http://localhost:9090/targets
- Grafana: http://localhost:3000 (admin / `.env`의 `GRAFANA_ADMIN_PASSWORD`)

## 확인

```bash
python3 tools/check_queries.py      # 대시보드 쿼리가 모두 데이터를 돌려주는지
python3 -m unittest discover -s tools -v
```

## 끄기

`docker compose stop`으로 먼저 스택을 내리고, 그다음 터널을 끈다(`tunnel.sh` 터미널에서 Ctrl+C 한 번). 수집한 데이터는 볼륨에 남는다(보존 30일).

터널을 터미널이 아니라 백그라운드(`&`, `nohup`)로 띄웠다면 `pkill -f monitoring/tunnel.sh`(SIGTERM)로 끈다. 비대화형 셸이 백그라운드로 띄운 프로세스는 SIGINT를 무시한 채 시작하고, bash는 시작할 때 무시된 신호를 `trap`으로 되돌릴 수 없어서 `pkill -INT`나 Ctrl+C가 먹지 않는다.

## 바꿀 때

대시보드를 Grafana 화면에서 고쳤다면 JSON으로 내보내 `grafana/dashboards/`에 덮어쓰고 커밋한다. 내보낼 때 "외부 공유용 내보내기(Export for sharing externally)"를 반드시 꺼야 한다. 켠 채로 내보내면 데이터소스 자리가 `${DS_PROMETHEUS}` 같은 변수로 바뀌어서 프로비저닝이 깨진다. 시크릿은 `.env`에만 둔다.
