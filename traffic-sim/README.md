# 트래픽 시뮬레이션

설계: `docs/traffic-sim/design.md`. 이 디렉터리는 2단계(봇이 쓸 서버 준비)까지 들어 있다.

| 경로 | 역할 |
|---|---|
| `mock-pg/` | 운영 서버 로컬에서 토스 대신 도는 Mock PG, systemd 유닛, 결제 대상 전환 스크립트 |
| `sim/` | 봇 데이터 생성 규칙(`data.py`), API 클라이언트(`api.py`) |
| `setup/provision.py` | API만으로 봇 스토어·상품·쿠폰·구매자·배송지를 만든다. 결과는 저장소 밖 상태 파일(아래 참고) |
| `setup/smoke_flow.py` | 주문 → Mock 결제 → 배송 완료를 한 번 흘린다 |
| `local/docker-compose.yml` | 로컬 검증 스택(`127.0.0.1:18080`) |

모든 명령은 `traffic-sim/`에서 실행한다. 로컬이 아닌 대상은 `SIM_ALLOWED_HOSTS`에 호스트를 적어야 요청을 보낸다.

## 테스트

```bash
python3 -m unittest discover -s sim -t .
python3 -m unittest discover -s setup -t .
python3 -m unittest discover -s mock-pg
```

## 로컬 검증

1. 저장소 루트에서 `./gradlew bootJar -x test`
2. `docker compose -f local/docker-compose.yml up -d`
3. 관리자 계정을 가입시키고 SQL로 역할을 ADMIN으로 바꾼다(계획 Task 6 Step 3)
4. `python3 -m setup.provision --base-url http://127.0.0.1:18080 --admin-email <관리자 이메일> --stores 2 --products-per-store 5 --buyers 20 --coupon-days 2`
5. `python3 -m setup.smoke_flow --base-url http://127.0.0.1:18080`
6. 끝나면 `docker compose -f local/docker-compose.yml down -v`로 스택을 지우고, 로컬 상태 파일 `~/.stylehub-sim/provision-127.0.0.1_18080.json`도 지운다. 남겨 두면 다음 로컬 실행이 이미 없어진 계정으로 로그인하려다 멈춘다.

## 상태 파일

`provision.py`는 결과를 `~/.stylehub-sim/provision-<host>_<port>.json`(권한 600)에 남긴다. 봇 계정 비밀번호는 이 파일에만 있어서, 운영 대상 파일을 잃으면 봇 계정으로 로그인할 수 없다. 위치는 `SIM_STATE_DIR`로 바꿀 수 있지만, 공개 저장소에 올라가지 않도록 저장소 안은 거부한다.

## 운영 서버에 적용

계획 `docs/traffic-sim/plans/2026-09-19-phase2-server-prep.md` Task 7의 순서를 따른다. Mock 모드가 켜져 있는 동안에는 결제가 실제로 일어나지 않는다. Jenkins 배포는 드롭인을 건드리지 않으니, 배포한 뒤에도 재부팅한 뒤에도 Mock 모드가 유지된다.

진짜 토스 결제를 시연할 때는 `bash /opt/stylehub-mock-pg/switch-pg.sh toss`로 되돌린다(운영 서버에서 실행).

이 스크립트가 없다면 손으로 되돌린다.

```bash
sudo rm -f /etc/systemd/system/stylehub.service.d/mock-pg.conf
sudo systemctl daemon-reload
sudo systemctl restart stylehub
```

`toss`는 결제 주소만 되돌린다. Mock PG 프로세스(최대 128M)는 계속 떠 있으니, 완전히 없애려면 `sudo systemctl disable --now stylehub-mock-pg`도 실행한다.

모드는 봇을 멈추고 결제 대기 10분 창에 주문이 없을 때만 바꾼다. 한 모드에서 결제된 주문을 다른 모드에서 취소하지 않는다(Mock 결제 주문을 토스 모드에서 취소하면 진짜 토스가 거절해 취소가 실패하고, 토스 결제 주문을 Mock 모드에서 취소하면 실제 환불 없이 환불로 기록된다).
