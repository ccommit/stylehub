# 1단계 관측(Grafana) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로컬 맥의 Grafana에서 운영 EC2의 애플리케이션·호스트 지표와 CPU 크레딧을 보고, 서버 쪽 이상이 생기면 Slack으로 알림을 받는다.

**Architecture:** 운영 서버는 actuator를 `127.0.0.1:9081`로 옮기고, node_exporter를 `127.0.0.1:9100`에 띄운다. 맥은 SSH 터널로 두 포트를 `19081`, `19100`에 연결한다. 맥의 Docker로 띄운 Prometheus가 터널을 통해 지표를 수집하고, Grafana가 Prometheus와 CloudWatch를 데이터소스로 쓴다. 데이터소스, 대시보드, 알림 규칙은 모두 저장소의 파일로 관리한다.

**Tech Stack:** Spring Boot 4.0.3 Actuator + Micrometer Prometheus, prometheus-node-exporter(Ubuntu 패키지), Docker Compose, `prom/prometheus:v3.14.0`, `grafana/grafana-oss:12.4.3`, Python 3.12 표준 라이브러리

**Spec:** `docs/traffic-sim/design.md` (2장, 7장 1단계, 8장)

## Global Constraints

- 운영 서버에 실행하는 명령은 사용자가 실행한다. Claude의 운영 서버 SSH 조회는 auto mode 분류기가 막는다.
- 커밋, push, PR 생성, develop → live 병합(운영 배포)은 모두 사용자 승인 후에 한다.
- 커밋 전에 새 파일의 시크릿을 스캔한다: `git diff --cached --name-only -- . ':!docs/traffic-sim/plans' | xargs grep -lE "GOCSPX-|AKIA[0-9A-Z]{16}|sk_live_|hooks\.slack\.com/services/T|-----BEGIN.*PRIVATE KEY-----"`. 결과가 비어 있어야 한다. 이 계획 파일 자체가 스캔 정규식 패턴 문자열을 그대로 담고 있어서, 계획 디렉터리는 pathspec으로 스캔에서 뺀다.
- 시크릿(Slack 웹훅, AWS 키, Grafana 관리자 비밀번호, EC2 주소, SSH 키 경로)은 `monitoring/.env`에만 둔다. 루트 `.gitignore`의 `.env` 규칙이 이 파일도 제외한다.
- Java: wildcard import 금지. 새 파일은 import 아래에 헤더(`@author WonJin Bae`, `@created`, 역할 설명 1~2줄)를 둔다. 코드 안 주석은 `//` 한 줄. 메서드 `/** */`와 주석 안 `**` 표기 금지. 테스트는 `@DisplayName`과 `// given`, `// when & then` 관례를 따른다.
- 커밋 메시지: `타입: 내용`, 50자 이내, 마침표 없음. PR 베이스는 `develop`, 제목은 `[#이슈] 타입: 요약`.
- 포트: 서버 actuator `127.0.0.1:9081`, node_exporter `127.0.0.1:9100`. 맥 터널 `19081`/`19100`(맥 8081은 Jenkins가 사용). 맥 Prometheus `127.0.0.1:9090`, Grafana `127.0.0.1:3000`.
- Prometheus job 이름: `stylehub`(actuator), `node`(node_exporter)
- 이미지 버전을 고정한다: `prom/prometheus:v3.14.0`, `grafana/grafana-oss:12.4.3`
- 작업은 `origin/develop`(f2d892a 이후)에서 새로 만든 워크트리에서 한다. 메인 작업 트리는 detached HEAD이고 커밋 안 된 쿠폰 수정이 있으니 건드리지 않는다.

---

## 사전 조건 (0단계, 사용자)

Task 1을 배포하기 전에, 그리고 Task 3을 실행하기 전에 끝나 있어야 한다.

- [ ] 서버에서 9081, 9100 포트가 비어 있는지 확인: `ss -tln | grep -E ':(9081|9100)\b'` → 출력 없음
- [ ] EC2 CPU 크레딧 모드를 standard로 전환(AWS 콘솔 → EC2 → 인스턴스 → 작업 → 인스턴스 설정 → 크레딧 사양 변경)
- [ ] CloudWatch 읽기 전용 IAM 사용자와 액세스 키 생성. 관리형 정책 `CloudWatchReadOnlyAccess` 대신 최소 권한 인라인 정책을 붙인다: `cloudwatch:GetMetricData`, `cloudwatch:ListMetrics` (Grafana 쿼리 편집기가 필요로 할 때만 `ec2:DescribeRegions`, `ec2:DescribeInstances`, `tag:GetResources`도 추가)
- [ ] Grafana 알림용 Slack 웹훅 URL 준비(Jenkins용과 같은 채널의 URL을 다시 써도 된다)
- [ ] 맥에 Docker Desktop 실행 중(`docker info`로 확인, 확인 당시 27.4.0)

## 파일 구조

| 파일 | 역할 | Task |
|---|---|---|
| `src/main/resources/application-prod.properties` | actuator 포트·주소 분리, 히스토그램, Tomcat 지표 | 1 |
| `src/test/java/ccommit/stylehub/common/config/ProdManagementPropertiesTest.java` | 위 설정이 빠지지 않았는지 확인 | 1 |
| `scripts/deploy-remote.sh` | 헬스체크를 9081 우선, 8080 보조로 | 1 |
| `Jenkinsfile` | Verify 단계 헬스체크 주소 | 1 |
| `Dockerfile`, `docker-compose.yml` | 컨테이너 헬스체크 주소 | 1 |
| `monitoring/server/install-node-exporter.sh` | 운영 서버에 node_exporter 설치 | 2 |
| `monitoring/docker-compose.yml` | Prometheus, Grafana | 3 |
| `monitoring/prometheus/prometheus.yml` | 수집 대상 | 3 |
| `monitoring/tunnel.sh` | SSH 터널 유지 | 3 |
| `monitoring/.env.example`, `monitoring/README.md` | 설정 자리, 실행 방법 | 3 |
| `monitoring/grafana/provisioning/datasources/datasources.yaml` | Prometheus, CloudWatch | 4 |
| `monitoring/grafana/provisioning/dashboards/dashboards.yaml` | 대시보드 파일 위치 | 4 |
| `monitoring/grafana/dashboards/stylehub-app.json` | 애플리케이션 대시보드 | 4 |
| `monitoring/grafana/dashboards/stylehub-host.json` | 호스트 대시보드 | 4 |
| `monitoring/tools/check_queries.py`, `monitoring/tools/test_check_queries.py` | 대시보드 쿼리 검증 도구와 테스트 | 4 |
| `monitoring/grafana/provisioning/alerting/contact-points.yaml` | Slack 연락처, 알림 정책 | 5 |
| `monitoring/grafana/provisioning/alerting/rules.yaml` | 알림 규칙 4개 | 5 |

PR은 두 개로 나눈다. PR A(Task 1)는 운영 배포가 필요하고, PR B(Task 2~5)는 맥에서만 쓰는 파일이다. PR A가 배포돼야 Task 3부터 운영 지표를 확인할 수 있다.

---

### Task 1: actuator 내부 포트 분리와 관측 지표 설정 (PR A)

**Files:**
- Modify: `src/main/resources/application-prod.properties` (`# --- Actuator` 구역)
- Create: `src/test/java/ccommit/stylehub/common/config/ProdManagementPropertiesTest.java`
- Modify: `scripts/deploy-remote.sh:23-38`
- Modify: `Jenkinsfile` Verify 단계 `curl` 한 줄
- Modify: `Dockerfile` HEALTHCHECK, `docker-compose.yml` healthcheck

**Interfaces:**
- Produces: 운영 서버의 `http://127.0.0.1:9081/actuator/health`와 `/actuator/prometheus`. 지표 이름: `http_server_requests_seconds_bucket`, `hikaricp_connections_*`, `tomcat_threads_*`, `jvm_*`, `process_cpu_usage`

- [ ] **Step 1: 이슈와 워크트리 준비 (사용자 승인 후)**

사용자 승인을 받고 1단계 이슈를 만든다. 이슈 번호를 `ISSUE` 변수에 넣는다.

```bash
# 저장소 루트에서 실행한다
gh issue create --title "feat: 운영 트래픽 시뮬레이션 1단계 — Grafana 관측 구성" --body "설계: docs/traffic-sim/design.md 7장 1단계"
ISSUE=<위 명령이 출력한 번호>   # Task 2~5에서도 쓴다. 새 셸에서는 다시 지정한다
git fetch origin
git worktree add .claude/worktrees/obs-a -b "${ISSUE}-feat-actuator-내부포트" origin/develop
cd .claude/worktrees/obs-a
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/ccommit/stylehub/common/config/ProdManagementPropertiesTest.java`:

```java
package ccommit.stylehub.common.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 운영 프로파일의 actuator 가 서버 내부 포트에만 열리고, 대시보드에 필요한 지표 설정이 빠지지 않았는지 확인한다.
 * </p>
 */
class ProdManagementPropertiesTest {

    private static Properties prod;

    @BeforeAll
    static void loadProdProperties() throws IOException {
        prod = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"));
    }

    @Test
    @DisplayName("actuator 는 127.0.0.1:9081 에서만 열린다")
    void actuatorBindsToLoopbackOnly() {
        // when & then
        assertThat(prod.getProperty("management.server.port")).isEqualTo("9081");
        assertThat(prod.getProperty("management.server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("p95 계산용 히스토그램과 Tomcat 스레드 지표가 켜져 있다")
    void metricsForDashboardsAreEnabled() {
        // when & then
        assertThat(prod.getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests"))
                .isEqualTo("true");
        assertThat(prod.getProperty("server.tomcat.mbeanregistry.enabled")).isEqualTo("true");
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests 'ccommit.stylehub.common.config.ProdManagementPropertiesTest'`
Expected: 두 테스트 모두 FAIL. `expected: "9081" but was: null` 같은 메시지가 나온다.

- [ ] **Step 4: 운영 설정 수정**

`application-prod.properties`의 `# --- Actuator` 구역에서 `# metrics/prometheus:`로 시작하는 주석 네 줄을 바꾸고, 설정을 추가한다.

바꿀 주석(기존):

```properties
# metrics/prometheus: JVM/HTTP/HikariCP 등 지표 노출 — 아직 별도 수집기(Prometheus)는
# 붙어 있지 않다. 이 엔드포인트는 인증 없이 열려 있으므로(WebConfig에서 /actuator/**를
# 인터셉터 제외), 외부에 그대로 노출하지 말고 EC2 보안그룹/리버스 프록시에서
# 접근 가능한 출처를 제한할 것 — 다음 단계 과제로 남겨둔다.
```

바뀐 주석과 추가 설정:

```properties
# metrics/prometheus: JVM/HTTP/HikariCP 등 지표 노출. 맥의 Prometheus 가 SSH 터널로 수집한다(docs/traffic-sim/design.md 2.2).
# 인증 없는 엔드포인트라 관리 포트를 따로 두고 127.0.0.1 에만 열어 서버 밖에서는 닿지 않게 한다.
management.server.port=9081
management.server.address=127.0.0.1
# p95/p99 를 Prometheus 에서 계산하려면 버킷이 필요하다
management.metrics.distribution.percentiles-histogram.http.server.requests=true
# 켜야 Tomcat 스레드 지표(tomcat_threads_*)가 나온다
server.tomcat.mbeanregistry.enabled=true
```

`management.endpoints.web.exposure.include=health,info,metrics,prometheus` 이하 기존 세 줄은 그대로 둔다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests 'ccommit.stylehub.common.config.ProdManagementPropertiesTest'`
Expected: PASS (2 tests)

- [ ] **Step 6: 배포 스크립트 헬스체크 수정**

`scripts/deploy-remote.sh`의 `HEALTH_URL=http://localhost:8080/actuator/health` 한 줄을 아래로 바꾼다.

```bash
# 관리 포트 전환 배포에서 롤백하면 이전 jar 는 actuator 를 8080 에서 연다. 전환 배포가 한 번 성공하면 8080 항목은 지운다.
HEALTH_URLS="http://127.0.0.1:9081/actuator/health http://localhost:8080/actuator/health"
```

`wait_for_health` 함수를 아래로 바꾼다(위의 설명 주석 세 줄은 유지).

```bash
wait_for_health() {
    for _ in $(seq 1 "$HEALTH_RETRIES"); do
        for url in $HEALTH_URLS; do
            if curl -fsS "$url" > /dev/null 2>&1; then
                return 0
            fi
        done
        sleep "$HEALTH_INTERVAL"
    done
    return 1
}
```

Run: `bash -n scripts/deploy-remote.sh && grep -c 'HEALTH_URL[^S]' scripts/deploy-remote.sh`
Expected: 문법 오류 없이 `0` 출력. 예전 변수 `HEALTH_URL`이 남아 있지 않다는 뜻이다.

- [ ] **Step 7: Jenkinsfile, Dockerfile, docker-compose 헬스체크 수정**

`Jenkinsfile` Verify 단계:

```groovy
                                curl -fsS http://127.0.0.1:9081/actuator/health
```

`Dockerfile`:

```dockerfile
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:9081/actuator/health || exit 1
```

`docker-compose.yml`:

```yaml
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:9081/actuator/health"]
```

Run: `grep -rn 'localhost:8080/actuator' Jenkinsfile Dockerfile docker-compose.yml scripts/`
Expected: `scripts/deploy-remote.sh`의 `HEALTH_URLS` 한 줄만 나온다.

- [ ] **Step 8: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋, PR (사용자 승인 후)**

```bash
git add src/main/resources/application-prod.properties \
        src/test/java/ccommit/stylehub/common/config/ProdManagementPropertiesTest.java \
        scripts/deploy-remote.sh Jenkinsfile Dockerfile docker-compose.yml
git diff --cached --name-only -- . ':!docs/traffic-sim/plans' | xargs grep -lE "GOCSPX-|AKIA[0-9A-Z]{16}|sk_live_|hooks\.slack\.com/services/T|-----BEGIN.*PRIVATE KEY-----"
git commit -m "feat: actuator 내부 포트 분리와 관측 지표 설정"
git push -u origin HEAD
gh pr create --base develop --title "[#${ISSUE}] feat: actuator 내부 포트 분리와 관측 지표 설정"
```

grep 결과는 비어 있어야 한다. PR 본문은 프로젝트 템플릿을 따르고, 주요 설계 결정에 두 가지를 적는다: 관리 포트를 분리한 이유, 헬스체크가 8080을 보조로 보는 이유(롤백 호환).

- [ ] **Step 10: 운영 반영과 확인 (사용자)**

1. PR A가 develop에 병합되면 develop → live PR을 만들고 병합한다. Jenkins가 배포한다.
2. Jenkins Verify 단계 로그에 `{"status":"UP"...}`가 찍히는지 본다.
3. 맥에서 외부 노출이 막혔는지 확인한다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' "http://<EC2 공인 IP>:8080/actuator/health"
```

Expected: `404`. 8080에는 더 이상 actuator가 없다.

4. 서버에서 `curl -fsS http://127.0.0.1:9081/actuator/prometheus | grep -c '^http_server_requests_seconds_bucket'` → 0보다 큰 수

---

### Task 2: 운영 서버 node_exporter (PR B 시작)

**Files:**
- Create: `monitoring/server/install-node-exporter.sh`

**Interfaces:**
- Produces: 운영 서버 `http://127.0.0.1:9100/metrics`. 지표 `node_cpu_seconds_total`, `node_memory_MemAvailable_bytes`, `node_memory_MemTotal_bytes`, `node_memory_SwapTotal_bytes`, `node_memory_SwapFree_bytes`, `node_filesystem_avail_bytes`, `node_filesystem_size_bytes`, `node_network_receive_bytes_total`, `node_network_transmit_bytes_total`

- [ ] **Step 1: 워크트리 준비**

```bash
# 저장소 루트에서 실행한다
git worktree add .claude/worktrees/obs-b -b "${ISSUE}-feat-grafana-관측" origin/develop
cd .claude/worktrees/obs-b
```

- [ ] **Step 2: 설치 스크립트 작성**

`monitoring/server/install-node-exporter.sh`:

```bash
#!/bin/bash
# 운영 서버에서 한 번 실행한다. node_exporter 를 설치하고 127.0.0.1:9100 에만 열어 서버 밖으로 노출하지 않는다.
set -euo pipefail

sudo apt-get update -qq
sudo apt-get install -y prometheus-node-exporter

echo 'ARGS="--web.listen-address=127.0.0.1:9100"' | sudo tee /etc/default/prometheus-node-exporter > /dev/null
sudo systemctl restart prometheus-node-exporter
sleep 2

echo "--- 수신 주소 (127.0.0.1:9100 만 나와야 한다)"
ss -tln | grep ':9100'
echo "--- 지표 확인"
curl -fsS http://127.0.0.1:9100/metrics | grep -m1 '^node_memory_MemAvailable_bytes'
echo "--- 메모리 사용량"
ps -o rss=,comm= -C prometheus-node-exporter
```

Run: `bash -n monitoring/server/install-node-exporter.sh && chmod +x monitoring/server/install-node-exporter.sh`
Expected: 출력 없음

- [ ] **Step 3: 서버에서 실행 (사용자)**

```bash
scp -i <키 경로> monitoring/server/install-node-exporter.sh ubuntu@<EC2 공인 IP>:/tmp/
ssh -i <키 경로> ubuntu@<EC2 공인 IP> bash /tmp/install-node-exporter.sh
```

Expected: 수신 주소는 `127.0.0.1:9100` 한 줄이고, `node_memory_MemAvailable_bytes <숫자>`가 나온다. 메모리 사용량(RSS, KB)은 design.md 0단계 체크리스트 옆에 기록한다.

---

### Task 3: 맥 관측 스택과 SSH 터널

**Files:**
- Create: `monitoring/docker-compose.yml`
- Create: `monitoring/prometheus/prometheus.yml`
- Create: `monitoring/tunnel.sh`
- Create: `monitoring/.env.example`
- Create: `monitoring/README.md`

**Interfaces:**
- Consumes: Task 1의 `127.0.0.1:9081/actuator/prometheus`, Task 2의 `127.0.0.1:9100/metrics` (서버 쪽)
- Produces: 맥 `http://localhost:9090` Prometheus(job `stylehub`, `node`, 라벨 `env="ec2"`), `http://localhost:3000` Grafana. `.env` 변수 `STYLEHUB_SSH_HOST`, `STYLEHUB_SSH_KEY`, `GRAFANA_ADMIN_PASSWORD`, `SLACK_WEBHOOK_URL`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`

- [ ] **Step 1: 설정 예시와 compose 작성**

`monitoring/.env.example`:

```bash
# 이 파일을 .env 로 복사해 값을 채운다. .env 는 커밋되지 않는다.
STYLEHUB_SSH_HOST=ubuntu@<EC2 공인 IP>
STYLEHUB_SSH_KEY=/absolute/path/to/key.pem
GRAFANA_ADMIN_PASSWORD=
SLACK_WEBHOOK_URL=
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=
AWS_REGION=ap-northeast-2
```

`monitoring/docker-compose.yml`:

```yaml
name: stylehub-monitoring

services:
  prometheus:
    image: prom/prometheus:v3.14.0
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.retention.time=30d
    ports:
      - "127.0.0.1:9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
    restart: unless-stopped

  grafana:
    image: grafana/grafana-oss:12.4.3
    ports:
      - "127.0.0.1:3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:?monitoring/.env 에 GRAFANA_ADMIN_PASSWORD 필요}
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_ANALYTICS_REPORTING_ENABLED: "false"
      SLACK_WEBHOOK_URL: ${SLACK_WEBHOOK_URL:?monitoring/.env 에 SLACK_WEBHOOK_URL 필요}
      AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID:?monitoring/.env 에 AWS_ACCESS_KEY_ID 필요}
      AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY:?monitoring/.env 에 AWS_SECRET_ACCESS_KEY 필요}
      AWS_REGION: ${AWS_REGION:-ap-northeast-2}
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/etc/grafana/dashboards:ro
      - grafana-data:/var/lib/grafana
    depends_on:
      - prometheus
    restart: unless-stopped

volumes:
  prometheus-data:
  grafana-data:
```

`monitoring/prometheus/prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: stylehub
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["host.docker.internal:19081"]
        labels:
          env: ec2

  - job_name: node
    static_configs:
      - targets: ["host.docker.internal:19100"]
        labels:
          env: ec2
```

- [ ] **Step 2: 터널 스크립트 작성**

`monitoring/tunnel.sh`:

```bash
#!/bin/bash
# 운영 서버의 actuator(9081)와 node_exporter(9100)를 맥 19081, 19100 으로 잇는다. 끊기면 5초 뒤 다시 붙는다.
set -u
cd "$(dirname "$0")"
set -a
source .env
set +a
: "${STYLEHUB_SSH_HOST:?monitoring/.env 에 STYLEHUB_SSH_HOST 필요}"
: "${STYLEHUB_SSH_KEY:?monitoring/.env 에 STYLEHUB_SSH_KEY 필요}"

while true; do
    ssh -N -i "$STYLEHUB_SSH_KEY" \
        -o ExitOnForwardFailure=yes \
        -o ServerAliveInterval=15 \
        -o ServerAliveCountMax=3 \
        -o StrictHostKeyChecking=accept-new \
        -L 127.0.0.1:19081:127.0.0.1:9081 \
        -L 127.0.0.1:19100:127.0.0.1:9100 \
        "$STYLEHUB_SSH_HOST"
    echo "$(date '+%F %T') 터널 끊김, 5초 뒤 다시 연결"
    sleep 5
done
```

Run: `bash -n monitoring/tunnel.sh && chmod +x monitoring/tunnel.sh`
Expected: 출력 없음

- [ ] **Step 3: README 작성**

`monitoring/README.md`:

````markdown
# 관측 스택 (로컬 맥)

설계: `docs/traffic-sim/design.md` 2장. 운영 서버 지표를 SSH 터널로 가져와 맥의 Prometheus와 Grafana에서 본다.

## 처음 한 번

1. `cp .env.example .env` 후 값을 채운다. `.env`는 커밋되지 않는다.
2. 운영 서버에 node_exporter를 설치한다: `server/install-node-exporter.sh`를 서버로 복사해 실행

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

`docker compose down`. 수집한 데이터는 볼륨에 남는다(보존 30일).

## 바꿀 때

대시보드를 Grafana 화면에서 고쳤다면 JSON으로 내보내 `grafana/dashboards/`에 덮어쓰고 커밋한다. 시크릿은 `.env`에만 둔다.
````

- [ ] **Step 4: 기동과 수집 확인**

사용자가 `cp monitoring/.env.example monitoring/.env` 후 값을 채운다. Claude는 `.env` 내용을 출력하지 않는다.

Grafana 프로비저닝 디렉터리가 아직 비어 있어도 기동은 된다. 먼저 빈 디렉터리를 만든다.

```bash
cd monitoring
mkdir -p grafana/provisioning/datasources grafana/provisioning/dashboards grafana/provisioning/alerting grafana/dashboards
./tunnel.sh &                     # 확인용. 평소에는 별도 터미널에서 켠다
sleep 5
docker compose up -d
sleep 20
curl -s localhost:9090/api/v1/targets | python3 -c 'import sys,json; [print(t["labels"]["job"], t["health"], t.get("lastError","")) for t in json.load(sys.stdin)["data"]["activeTargets"]]'
```

Expected:

```
stylehub up
node up
```

`down`이고 `connection refused`가 나오면, 컨테이너에서 맥 루프백으로 닿지 않는 것이다. 이때는 `tunnel.sh`가 떠 있는지, Docker Desktop의 host networking이 `host.docker.internal`로 맥 루프백에 닿는지부터 확인한다. 터널을 `0.0.0.0`에 바인딩하지 않는다 — 인증 없는 운영 actuator와 node_exporter가 같은 네트워크의 다른 기기에 그대로 노출된다.

확인이 끝나면 `kill %1`로 백그라운드 터널을 끈다.

- [ ] **Step 5: 커밋 (사용자 승인 후)**

```bash
cd ..
git add monitoring/docker-compose.yml monitoring/prometheus/prometheus.yml monitoring/tunnel.sh \
        monitoring/.env.example monitoring/README.md monitoring/server/install-node-exporter.sh
git status --short monitoring/ | grep -F '.env' | grep -v example
git diff --cached --name-only -- . ':!docs/traffic-sim/plans' | xargs grep -lE "GOCSPX-|AKIA[0-9A-Z]{16}|sk_live_|hooks\.slack\.com/services/T|-----BEGIN.*PRIVATE KEY-----"
git commit -m "feat: 맥 Prometheus·Grafana 스택과 SSH 터널 추가"
```

두 번째 명령과 세 번째 명령의 출력은 모두 비어 있어야 한다. `.env`가 스테이징되지 않았고, 시크릿도 없다는 뜻이다.

---

### Task 4: Grafana 데이터소스, 대시보드 2개, 쿼리 검증 도구

**Files:**
- Create: `monitoring/tools/test_check_queries.py`
- Create: `monitoring/tools/check_queries.py`
- Create: `monitoring/grafana/provisioning/datasources/datasources.yaml`
- Create: `monitoring/grafana/provisioning/dashboards/dashboards.yaml`
- Create: `monitoring/grafana/dashboards/stylehub-app.json`
- Create: `monitoring/grafana/dashboards/stylehub-host.json`

**Interfaces:**
- Consumes: Task 3의 Prometheus job `stylehub`, `node`, `.env`의 AWS 변수
- Produces: 데이터소스 uid `prometheus`, `cloudwatch`. 대시보드 uid `stylehub-app`, `stylehub-host`. 함수 `extract_prometheus_queries(dashboard: dict) -> list[tuple[str, str]]` (패널 제목, expr)

- [ ] **Step 1: 실패하는 테스트 작성**

`monitoring/tools/test_check_queries.py`:

```python
import unittest

from check_queries import extract_prometheus_queries


class ExtractPrometheusQueriesTest(unittest.TestCase):

    def test_collects_prometheus_targets_and_skips_other_datasources(self):
        dashboard = {"panels": [
            {"title": "p95", "datasource": {"type": "prometheus", "uid": "prometheus"},
             "targets": [{"refId": "A", "expr": "up"}, {"refId": "B", "expr": "node_load1"}]},
            {"title": "크레딧", "datasource": {"type": "cloudwatch", "uid": "cloudwatch"},
             "targets": [{"refId": "A", "metricName": "CPUCreditBalance"}]},
        ]}

        self.assertEqual(extract_prometheus_queries(dashboard), [("p95", "up"), ("p95", "node_load1")])

    def test_target_datasource_overrides_panel_datasource(self):
        dashboard = {"panels": [
            {"title": "섞임", "datasource": {"type": "cloudwatch", "uid": "cloudwatch"},
             "targets": [{"refId": "A", "expr": "up", "datasource": {"type": "prometheus", "uid": "prometheus"}}]},
        ]}

        self.assertEqual(extract_prometheus_queries(dashboard), [("섞임", "up")])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `python3 -m unittest discover -s monitoring/tools -v`
Expected: `ModuleNotFoundError: No module named 'check_queries'`

- [ ] **Step 3: 검증 도구 구현**

`monitoring/tools/check_queries.py`:

```python
#!/usr/bin/env python3
# 대시보드 JSON 의 Prometheus 쿼리를 실제 Prometheus 에 던져, 지표 이름이 틀렸거나 데이터가 없는 패널을 찾는다.
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PROMETHEUS_URL = "http://localhost:9090"
DASHBOARD_DIR = Path(__file__).resolve().parent.parent / "grafana" / "dashboards"


def extract_prometheus_queries(dashboard):
    queries = []
    for panel in dashboard.get("panels", []):
        panel_datasource = panel.get("datasource") or {}
        for target in panel.get("targets", []):
            datasource = target.get("datasource") or panel_datasource
            if datasource.get("type") == "prometheus" and target.get("expr"):
                queries.append((panel["title"], target["expr"]))
    return queries


def run_query(expr):
    url = f"{PROMETHEUS_URL}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            body = json.load(response)
    except urllib.error.HTTPError as error:
        body = json.load(error)
    if body.get("status") != "success":
        return "ERROR", body.get("error", "unknown")
    series = len(body["data"]["result"])
    return ("OK" if series else "EMPTY"), series


def main():
    failed = 0
    for path in sorted(DASHBOARD_DIR.glob("*.json")):
        dashboard = json.loads(path.read_text(encoding="utf-8"))
        for title, expr in extract_prometheus_queries(dashboard):
            status, detail = run_query(expr)
            print(f"[{status}] {path.name} / {title}: {detail}")
            if status != "OK":
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python3 -m unittest discover -s monitoring/tools -v`
Expected: `Ran 2 tests ... OK`

- [ ] **Step 5: 데이터소스와 대시보드 프로비저닝 파일 작성**

`monitoring/grafana/provisioning/datasources/datasources.yaml`:

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true

  - name: CloudWatch
    uid: cloudwatch
    type: cloudwatch
    jsonData:
      authType: keys
      defaultRegion: $AWS_REGION
    secureJsonData:
      accessKey: $AWS_ACCESS_KEY_ID
      secretKey: $AWS_SECRET_ACCESS_KEY
```

`monitoring/grafana/provisioning/dashboards/dashboards.yaml`:

```yaml
apiVersion: 1

providers:
  - name: stylehub
    folder: StyleHub
    type: file
    allowUiUpdates: true
    options:
      path: /etc/grafana/dashboards
```

- [ ] **Step 6: 애플리케이션 대시보드 작성**

`monitoring/grafana/dashboards/stylehub-app.json`:

```json
{
  "uid": "stylehub-app",
  "title": "StyleHub 애플리케이션",
  "tags": ["stylehub"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "30s",
  "time": {"from": "now-3h", "to": "now"},
  "panels": [
    {
      "id": 1, "type": "timeseries", "title": "URI별 p95 응답 시간",
      "gridPos": {"h": 8, "w": 12, "x": 0, "y": 0},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "s"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "{{method}} {{uri}}",
         "expr": "histogram_quantile(0.95, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{job=\"stylehub\", uri!~\"/actuator.*\"}[5m])))"}
      ]
    },
    {
      "id": 2, "type": "timeseries", "title": "상태 코드별 요청 수",
      "gridPos": {"h": 8, "w": 12, "x": 12, "y": 0},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "reqps"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "{{status}}",
         "expr": "sum by (status) (rate(http_server_requests_seconds_count{job=\"stylehub\", uri!~\"/actuator.*\"}[1m]))"}
      ]
    },
    {
      "id": 3, "type": "timeseries", "title": "5xx 비율",
      "gridPos": {"h": 8, "w": 8, "x": 0, "y": 8},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "percentunit"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "5xx",
         "expr": "(sum(rate(http_server_requests_seconds_count{job=\"stylehub\", status=~\"5..\"}[5m])) or vector(0)) / sum(rate(http_server_requests_seconds_count{job=\"stylehub\", uri!~\"/actuator.*\"}[5m]))"}
      ]
    },
    {
      "id": 4, "type": "timeseries", "title": "Hikari 커넥션",
      "gridPos": {"h": 8, "w": 8, "x": 8, "y": 8},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "none"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "active", "expr": "sum(hikaricp_connections_active{job=\"stylehub\"})"},
        {"refId": "B", "legendFormat": "pending", "expr": "sum(hikaricp_connections_pending{job=\"stylehub\"})"},
        {"refId": "C", "legendFormat": "max", "expr": "sum(hikaricp_connections_max{job=\"stylehub\"})"}
      ]
    },
    {
      "id": 5, "type": "timeseries", "title": "커넥션 획득 타임아웃 (5분 누적)",
      "gridPos": {"h": 8, "w": 8, "x": 16, "y": 8},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "none"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "timeout", "expr": "sum(increase(hikaricp_connections_timeout_total{job=\"stylehub\"}[5m]))"}
      ]
    },
    {
      "id": 6, "type": "timeseries", "title": "Tomcat 스레드 (API 포트)",
      "gridPos": {"h": 8, "w": 8, "x": 0, "y": 16},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "none"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "busy", "expr": "sum(tomcat_threads_busy_threads{job=\"stylehub\", name=~\".*8080.*\"})"},
        {"refId": "B", "legendFormat": "max", "expr": "sum(tomcat_threads_config_max_threads{job=\"stylehub\", name=~\".*8080.*\"})"}
      ]
    },
    {
      "id": 7, "type": "timeseries", "title": "JVM 힙",
      "gridPos": {"h": 8, "w": 8, "x": 8, "y": 16},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "bytes"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "used", "expr": "sum(jvm_memory_used_bytes{job=\"stylehub\", area=\"heap\"})"},
        {"refId": "B", "legendFormat": "committed", "expr": "sum(jvm_memory_committed_bytes{job=\"stylehub\", area=\"heap\"})"}
      ]
    },
    {
      "id": 8, "type": "timeseries", "title": "GC 최대 멈춤",
      "gridPos": {"h": 8, "w": 8, "x": 16, "y": 16},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "s"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "{{gc}}", "expr": "max by (gc) (max_over_time(jvm_gc_pause_seconds_max{job=\"stylehub\"}[5m]))"}
      ]
    },
    {
      "id": 9, "type": "timeseries", "title": "CPU (프로세스, 시스템)",
      "gridPos": {"h": 8, "w": 24, "x": 0, "y": 24},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "percentunit"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "process", "expr": "process_cpu_usage{job=\"stylehub\"}"},
        {"refId": "B", "legendFormat": "system", "expr": "system_cpu_usage{job=\"stylehub\"}"}
      ]
    }
  ]
}
```

- [ ] **Step 7: 호스트 대시보드 작성**

`monitoring/grafana/dashboards/stylehub-host.json`:

```json
{
  "uid": "stylehub-host",
  "title": "StyleHub 호스트",
  "tags": ["stylehub"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "1m",
  "time": {"from": "now-6h", "to": "now"},
  "panels": [
    {
      "id": 1, "type": "timeseries", "title": "CPU 사용률",
      "gridPos": {"h": 8, "w": 12, "x": 0, "y": 0},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "percentunit"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "used", "expr": "1 - avg(rate(node_cpu_seconds_total{job=\"node\", mode=\"idle\"}[5m]))"},
        {"refId": "B", "legendFormat": "steal", "expr": "avg(rate(node_cpu_seconds_total{job=\"node\", mode=\"steal\"}[5m]))"}
      ]
    },
    {
      "id": 2, "type": "timeseries", "title": "CPU 크레딧 잔량 (CloudWatch)",
      "gridPos": {"h": 8, "w": 12, "x": 12, "y": 0},
      "datasource": {"type": "cloudwatch", "uid": "cloudwatch"},
      "fieldConfig": {"defaults": {"unit": "none"}, "overrides": []},
      "targets": [
        {"refId": "A", "queryMode": "Metrics", "region": "default", "namespace": "AWS/EC2",
         "metricName": "CPUCreditBalance", "dimensions": {"InstanceId": "*"}, "matchExact": false,
         "statistic": "Average", "period": "300", "metricQueryType": 0, "metricEditorMode": 0,
         "id": "", "expression": ""}
      ]
    },
    {
      "id": 3, "type": "timeseries", "title": "메모리",
      "gridPos": {"h": 8, "w": 12, "x": 0, "y": 8},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "bytes"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "available", "expr": "node_memory_MemAvailable_bytes{job=\"node\"}"},
        {"refId": "B", "legendFormat": "total", "expr": "node_memory_MemTotal_bytes{job=\"node\"}"},
        {"refId": "C", "legendFormat": "swap used", "expr": "node_memory_SwapTotal_bytes{job=\"node\"} - node_memory_SwapFree_bytes{job=\"node\"}"}
      ]
    },
    {
      "id": 4, "type": "timeseries", "title": "디스크 사용률 (/)",
      "gridPos": {"h": 8, "w": 12, "x": 12, "y": 8},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "percentunit"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "/", "expr": "1 - node_filesystem_avail_bytes{job=\"node\", mountpoint=\"/\"} / node_filesystem_size_bytes{job=\"node\", mountpoint=\"/\"}"}
      ]
    },
    {
      "id": 5, "type": "timeseries", "title": "네트워크",
      "gridPos": {"h": 8, "w": 24, "x": 0, "y": 16},
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "Bps"}, "overrides": []},
      "targets": [
        {"refId": "A", "legendFormat": "rx {{device}}", "expr": "rate(node_network_receive_bytes_total{job=\"node\", device!=\"lo\"}[5m])"},
        {"refId": "B", "legendFormat": "tx {{device}}", "expr": "rate(node_network_transmit_bytes_total{job=\"node\", device!=\"lo\"}[5m])"}
      ]
    }
  ]
}
```

Run: `for f in monitoring/grafana/dashboards/*.json; do python3 -m json.tool "$f" > /dev/null && echo "ok $f"; done`
Expected: 두 파일 모두 `ok`

- [ ] **Step 8: 쿼리 검증**

터널과 스택이 떠 있는 상태에서 실행한다. 지표가 쌓이도록 수동 요청을 몇 번 보낸다.

```bash
for i in $(seq 1 20); do curl -s -o /dev/null "http://<EC2 공인 IP>:8080/api/v1/products"; done
sleep 60
python3 monitoring/tools/check_queries.py
```

Expected: 모든 줄이 `[OK]`로 끝나고 종료 코드 0.

`EMPTY`나 `ERROR`가 나오면 원인을 찾는다: `curl -s 'localhost:9090/api/v1/label/__name__/values' | python3 -c 'import sys,json; [print(n) for n in json.load(sys.stdin)["data"] if n.startswith(("hikari","tomcat","jvm_gc","http_server"))]'`로 실제 지표 이름을 확인하고 JSON을 고친다. GC 패널은 GC가 한 번도 안 일어났으면 비어 있을 수 있다. 이 경우만 예외로 기록한다.

- [ ] **Step 9: Grafana 반영과 CloudWatch 확인**

```bash
cd monitoring && docker compose restart grafana && sleep 15
docker compose logs grafana 2>&1 | grep -iE 'level=error' | grep -iE 'provision|datasource|dashboard'
set -a; source .env; set +a
curl -s -u "admin:${GRAFANA_ADMIN_PASSWORD}" 'localhost:3000/api/search?tag=stylehub' | python3 -c 'import sys,json; [print(d["uid"], d["title"]) for d in json.load(sys.stdin)]'
curl -s -u "admin:${GRAFANA_ADMIN_PASSWORD}" -H 'Content-Type: application/json' localhost:3000/api/ds/query \
  -d '{"from":"now-3h","to":"now","queries":[{"refId":"A","datasource":{"uid":"cloudwatch"},"queryMode":"Metrics","region":"default","namespace":"AWS/EC2","metricName":"CPUCreditBalance","dimensions":{"InstanceId":"*"},"matchExact":false,"statistic":"Average","period":"300","metricQueryType":0,"metricEditorMode":0,"id":"","expression":""}]}' \
  | python3 -c 'import sys,json; print(json.dumps(json.load(sys.stdin)["results"], indent=2))'
```

Expected: 두 번째 명령은 출력 없음. 세 번째는 `stylehub-app`, `stylehub-host` 두 줄. 네 번째는 `A` 키 아래 `frames`가 1개 이상. `results`를 전체 출력하는 이유는, 인증/권한 오류가 `A`가 아니라 빈 문자열 키(`""`) 아래로 오기 때문이다 — `["results"]["A"]`로 바로 집으면 이 경우 `KeyError`로 죽는다. 오류가 있으면 IAM 권한과 리전을 확인한다. `InstanceId: "*"`가 안 먹으면, 사용자가 인스턴스 ID를 알려주고 그 값을 JSON에 넣는다.

마지막으로 브라우저에서 http://localhost:3000 을 열어 두 대시보드의 모든 패널에 선이 그려지는지 눈으로 확인한다.

- [ ] **Step 10: 커밋 (사용자 승인 후)**

```bash
cd ..
git add monitoring/tools monitoring/grafana/provisioning/datasources monitoring/grafana/provisioning/dashboards monitoring/grafana/dashboards
git diff --cached --name-only -- . ':!docs/traffic-sim/plans' | xargs grep -lE "GOCSPX-|AKIA[0-9A-Z]{16}|sk_live_|hooks\.slack\.com/services/T|-----BEGIN.*PRIVATE KEY-----"
git commit -m "feat: Grafana 대시보드 2종과 쿼리 검증 도구 추가"
```

grep 결과는 비어 있어야 한다.

---

### Task 5: 서버 쪽 알림 규칙과 Slack 연결

**Files:**
- Create: `monitoring/grafana/provisioning/alerting/contact-points.yaml`
- Create: `monitoring/grafana/provisioning/alerting/rules.yaml`
- Modify: `docs/traffic-sim/design.md` (상태 줄, 0단계 체크리스트, 1단계 완료 표시)

**Interfaces:**
- Consumes: 데이터소스 uid `prometheus`, `cloudwatch`(Task 4), `.env`의 `SLACK_WEBHOOK_URL`
- Produces: 연락처 `slack-stylehub`, 규칙 uid `stylehub-scrape-down`, `stylehub-memory-low`, `stylehub-disk-high`, `stylehub-cpu-credit-low`

- [ ] **Step 1: 연락처와 알림 정책 작성**

`monitoring/grafana/provisioning/alerting/contact-points.yaml`:

```yaml
apiVersion: 1

contactPoints:
  - orgId: 1
    name: slack-stylehub
    receivers:
      - uid: slack-stylehub
        type: slack
        settings:
          url: $SLACK_WEBHOOK_URL
        disableResolveMessage: false

policies:
  - orgId: 1
    receiver: slack-stylehub
    group_by: ["grafana_folder", "alertname"]
    group_wait: 30s
    group_interval: 5m
    repeat_interval: 4h
```

- [ ] **Step 2: 알림 규칙 작성**

`monitoring/grafana/provisioning/alerting/rules.yaml`:

```yaml
apiVersion: 1

groups:
  - orgId: 1
    name: server
    folder: StyleHub
    interval: 1m
    rules:
      - uid: stylehub-scrape-down
        title: 수집 끊김
        condition: C
        data:
          - refId: A
            relativeTimeRange: {from: 600, to: 0}
            datasourceUid: prometheus
            model:
              refId: A
              expr: up{job=~"stylehub|node"}
              instant: true
          - refId: C
            datasourceUid: __expr__
            model:
              refId: C
              type: threshold
              expression: A
              datasource: {type: __expr__, uid: __expr__}
              conditions:
                - evaluator: {type: lt, params: [1]}
        for: 2m
        noDataState: Alerting
        execErrState: Error
        labels: {severity: critical}
        annotations:
          summary: "{{ $labels.job }} 을 2분 넘게 수집하지 못했다. 서버 다운, 배포 지연, 터널 끊김 중 하나다."

      - uid: stylehub-memory-low
        title: 가용 메모리 부족
        condition: C
        data:
          - refId: A
            relativeTimeRange: {from: 600, to: 0}
            datasourceUid: prometheus
            model:
              refId: A
              expr: 100 * node_memory_MemAvailable_bytes{job="node"} / node_memory_MemTotal_bytes{job="node"}
              instant: true
          - refId: C
            datasourceUid: __expr__
            model:
              refId: C
              type: threshold
              expression: A
              datasource: {type: __expr__, uid: __expr__}
              conditions:
                - evaluator: {type: lt, params: [10]}
        for: 5m
        noDataState: OK
        execErrState: Error
        labels: {severity: warning}
        annotations:
          summary: "운영 서버 가용 메모리가 5분 넘게 10% 미만이다: {{ humanize $values.A.Value }}%"

      - uid: stylehub-disk-high
        title: 디스크 사용률 높음
        condition: C
        data:
          - refId: A
            relativeTimeRange: {from: 600, to: 0}
            datasourceUid: prometheus
            model:
              refId: A
              expr: 100 * (1 - node_filesystem_avail_bytes{job="node", mountpoint="/"} / node_filesystem_size_bytes{job="node", mountpoint="/"})
              instant: true
          - refId: C
            datasourceUid: __expr__
            model:
              refId: C
              type: threshold
              expression: A
              datasource: {type: __expr__, uid: __expr__}
              conditions:
                - evaluator: {type: gt, params: [80]}
        for: 5m
        noDataState: OK
        execErrState: Error
        labels: {severity: warning}
        annotations:
          summary: "운영 서버 디스크 사용률이 80%를 넘었다: {{ humanize $values.A.Value }}%"

  - orgId: 1
    name: aws
    folder: StyleHub
    interval: 5m
    rules:
      - uid: stylehub-cpu-credit-low
        title: CPU 크레딧 부족
        condition: C
        data:
          - refId: A
            relativeTimeRange: {from: 3600, to: 0}
            datasourceUid: cloudwatch
            model:
              refId: A
              queryMode: Metrics
              region: default
              namespace: AWS/EC2
              metricName: CPUCreditBalance
              dimensions: {InstanceId: "*"}
              matchExact: false
              statistic: Average
              period: "300"
              metricQueryType: 0
              metricEditorMode: 0
              id: ""
              expression: ""
          - refId: B
            datasourceUid: __expr__
            model:
              refId: B
              type: reduce
              expression: A
              reducer: last
              datasource: {type: __expr__, uid: __expr__}
          - refId: C
            datasourceUid: __expr__
            model:
              refId: C
              type: threshold
              expression: B
              datasource: {type: __expr__, uid: __expr__}
              conditions:
                - evaluator: {type: lt, params: [30]}
        for: 10m
        noDataState: NoData
        execErrState: Error
        labels: {severity: warning}
        annotations:
          summary: "CPU 크레딧 잔량이 30 미만이다: {{ humanize $values.B.Value }}. standard 모드라 곧 기준 성능으로 제한된다."
```

- [ ] **Step 3: 반영 확인**

```bash
cd monitoring && docker compose restart grafana && sleep 15
docker compose logs grafana 2>&1 | grep -iE 'level=error' | grep -iE 'alert|provision'
set -a; source .env; set +a
curl -s -u "admin:${GRAFANA_ADMIN_PASSWORD}" localhost:3000/api/v1/provisioning/alert-rules | python3 -c 'import sys,json; [print(r["uid"], r["title"]) for r in json.load(sys.stdin)]'
```

Expected: 두 번째 명령은 출력 없음. 세 번째 명령은 규칙 4줄(`stylehub-scrape-down`, `stylehub-memory-low`, `stylehub-disk-high`, `stylehub-cpu-credit-low`).

- [ ] **Step 4: 끝에서 끝까지 알림 확인**

1. 터널을 끈다(`tunnel.sh` 터미널에서 Ctrl+C).
2. 3~4분 기다린다. `for: 2m`에 평가 주기가 더해진다.
3. Slack `#stylehub-alerts-전체`에 "수집 끊김" 알림이 `stylehub`, `node` 두 job으로 오는지 확인한다.
4. 터널을 다시 켜고, 몇 분 안에 해소(Resolved) 알림이 오는지 확인한다.

Expected: 발생과 해소 메시지가 둘 다 온다. 오지 않으면 Grafana → Alerting → Alert rules에서 규칙 상태를 보고, Contact points에서 `slack-stylehub` 테스트 발송으로 웹훅부터 확인한다.

- [ ] **Step 5: 1단계 완료 조건 확인과 설계 문서 갱신**

design.md 7장 1단계 완료 조건:
- 수동 요청만 흘린 상태에서 대시보드 2개에 EC2 지표가 1시간 이어서 찍힌다: 1시간 뒤 두 대시보드를 "지난 1시간"으로 열어, 끊김 없는지 확인
- 테스트 알림이 Slack에 도착한다: Step 4에서 확인
- 배포 후 Jenkins Verify 단계가 통과한다: Task 1 Step 10에서 확인

`docs/traffic-sim/design.md`에서 이렇게 고친다.
- 상태 줄을 `1단계 완료(YYYY-MM-DD), 2단계 준비 중`으로 바꾼다
- 7장 0단계 체크리스트 중 끝난 항목에 `[x]` 표시를 하고, 확인한 값(인스턴스 타입, 메모리, 디스크, node_exporter RSS)을 적는다

- [ ] **Step 6: 커밋, PR (사용자 승인 후)**

```bash
cd ..
git add monitoring/grafana/provisioning/alerting docs/traffic-sim/design.md
git diff --cached --name-only -- . ':!docs/traffic-sim/plans' | xargs grep -lE "GOCSPX-|AKIA[0-9A-Z]{16}|sk_live_|hooks\.slack\.com/services/T|-----BEGIN.*PRIVATE KEY-----"
git commit -m "feat: Grafana 서버 알림 규칙과 Slack 연결 추가"
git push -u origin HEAD
gh pr create --base develop --title "[#${ISSUE}] feat: 맥 Grafana 관측 스택과 서버 알림"
```

grep 결과는 비어 있어야 한다. PR 본문의 주요 설계 결정에는 세 가지를 적는다: 수집을 맥에서 하는 이유(design.md 2.1), Mock 없이 확인 가능한 범위, CloudWatch 조회 주기를 5분으로 둔 이유.

---

## 이 계획에서 하지 않는 것

- 봇 지표(`:9646`), 사용자 관점 대시보드, 봇 기준 알림(가용성, 조회 지연, 봇 멈춤): 3단계
- 로컬 docker-compose 앱 수집(`env="local"`): 3단계에서 필요할 때
- Jenkins 배포 주석(annotation): 4단계
- 수정 체인 #86~#98 병합: 별도 진행. 체인도 `application-prod.properties`를 고치므로, 먼저 병합되는 쪽 뒤에 오는 쪽이 리베이스한다(다른 구역이라 충돌은 작을 것으로 예상).

## 실행 기록 (2026-09-18 ~ 09-19)

계획대로 진행하되, 실행 중 아래가 바뀌었다. **코드의 기준은 `monitoring/` 아래 실제 파일이다.** 위 코드 블록과 다른 곳은 여기에 적은 이유로 바뀌었다.

**진행 방식**
- 커밋은 사용자 승인 뒤에만 했다. Task마다 스테이징만 하고 검토했다.
- Task 1은 PR #112로 병합해 운영에 반영했다(2026-09-18 22:21 KST, 릴리스 PR #113). 롤백용 8080 보조 헬스체크는 PR #114로 지웠고, 다음 릴리스 때 함께 나간다.
- 운영 서버 작업(Task 2 Step 3)과 실제 키가 필요한 확인(Task 3 Step 4, Task 4 Step 8~9, Task 5 Step 4~6)은 사용자와 함께 하기로 미뤘다. 대신 맥에서 임시 값으로 테스트용 스택(`-p stylehub-monitoring-selftest`)을 띄워 파일 구성을 검증하고, 끝나면 내렸다.

**계획서 코드에서 바뀐 것**
- Task 3: 빈 디렉터리를 git이 추적하도록 `.gitkeep`을 두었다.
- Task 4 `check_queries.py`: Prometheus에 닿지 않으면(`URLError`) 첫 쿼리에서 멈추던 것을, 쿼리마다 `[ERROR] Prometheus 연결 실패`로 보고하도록 바꿨다. 닫힌 로컬 포트로 확인하는 테스트도 추가했다(테스트 3개).
- Task 4 5xx 비율 패널: 분자에도 `uri!~"/actuator.*"`를 넣어 분모와 같은 요청 집합을 세게 했다.
- Task 4 fix round: `grafana/provisioning/plugins/.gitkeep`을 추가했다(Task 3이 아니라 Task 4 리뷰 뒤 수정 라운드에서 추가됨). 이 디렉터리가 없으면 Grafana가 켜질 때마다 오류 로그를 남기고, 그 때문에 Task 5 Step 3의 오류 로그 점검이 문제가 없는데도 실패한다.
- Task 5 알림 문구: `{{ humanize $values.A.Value }}`는 값이 없을 때(NoData) `can't convert <nil> to float` 오류를 냈다. 처음 넣은 `{{ if $values.A.Value }}` 보호는 실제 값 0을 `N/A`로 숨겼다. Grafana 12.4.3에서 후보 템플릿 5개를 값 0, 42.5, 값 없음에 각각 넣어 본 결과, `{{ if ne $values.A.Value nil }}{{ humanize $values.A.Value }}{{ else }}N/A{{ end }}`만 셋을 모두 올바르게 표시했다(0, 42.5, N/A). 이것을 메모리·디스크(`$values.A`)와 CPU 크레딧(`$values.B`) 문구에 썼다.

**최종 전체 브랜치 리뷰 이후 수정 (2026-09-19)**
- 워크트리 베이스를 `origin/develop` 93e0f3d로 옮겼다(#116~#126 병합, 주소 API #126 포함). 스테이징돼 있던 17개 파일은 겹치는 파일이 없어 그대로 유지됐다.
- `tunnel.sh`: `.env`가 없으면 안내 메시지를 찍고 바로 종료하게 했다. ssh를 백그라운드로 띄우고 `wait`하다가, INT·TERM 트랩에서 살아있는 ssh 자식을 정리하고 130/143으로 종료하게 했다 — 이전에는 Ctrl+C 한 번으로 while 루프만 끊기고 ssh가 살아남아 19081/19100을 붙들 수 있었다. ssh 옵션에 `-o Compression=yes`, `-o BatchMode=yes`, `-o IdentitiesOnly=yes`를 추가하고, 재연결 대기를 5초에서 10초로 늘렸다.
- `install-node-exporter.sh`: `apt-get install`에 `--no-install-recommends`를 추가했다. 지표 확인은 `grep -m1` 대신 `grep '^node_memory_MemAvailable_bytes'`로 바꿔 SIGPIPE/pipefail 경쟁을 없앴고, RSS 확인은 프로세스 이름 매칭(`ps -C`) 대신 `systemctl show -p MainPID`로 정확한 PID를 잡게 했다.
- `docker-compose.yml`: 두 서비스의 `restart`를 `unless-stopped`에서 `"no"`로 바꿨다. 맥이 재부팅돼도 터널 없이 스택만 혼자 살아나 "수집 끊김" 오탐으로 Slack을 울리지 않게 하기 위해서다.
- `check_queries.py`: 접힌 행(collapsed row)에 중첩된 패널도 재귀로 훑고, 문자열 형태의 옛 datasource는 크래시 없이 건너뛰게 했다. HTTP 오류 응답 본문이 JSON이 아니면 `HTTP <코드>`를, 타임아웃이면 "Prometheus 응답 시간 초과"를 돌려주게 했다. 새 테스트 7개를 추가했다.
- `rules.yaml`: CPU 크레딧 알림의 `N/A` 문구에 원인을 덧붙였다(`N/A(CloudWatch 값을 받지 못함 — 키·권한·리전 확인)`).
- 이 계획 파일의 시크릿 스캔 명령에 `-- . ':!docs/traffic-sim/plans'` pathspec을 추가했다. 계획 파일 자체가 스캔 정규식을 문자열 그대로 담고 있어 항상 걸리기 때문이다.

**환경 메모**
- macOS에는 `timeout` 명령이 없다. 대기는 `gh pr checks --watch`처럼 스스로 끝나는 명령으로 한다.
