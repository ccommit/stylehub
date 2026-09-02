# [배포 #1] Docker 컨테이너로 굽고 Jenkins로 배포하기 — GHCR push 후 운영서버 SSH 재기동

## 요약

> 로컬에서만 돌던 StyleHub를 컨테이너 이미지로 굽고, Jenkins 파이프라인이 테스트 → 이미지 빌드 → GHCR push → 운영서버 SSH 배포까지 자동으로 잇게 만들었다. 그 과정에서 가장 크게 붙잡고 있었던 건 "시크릿을 이미지에 박지 않기"와 "배포할 때 DB/Redis 데이터는 살리고 앱만 교체하기" 두 가지였다.

지금까지의 글은 전부 코드 안쪽(동시성, 트랜잭션, 성능)에 대한 것이었다. 이 글은 그 코드를 처음으로 내 노트북 밖으로 꺼내는 이야기다. `./gradlew bootRun`으로 뜨던 앱을 "누가, 언제 push해도 똑같이 재현되는 배포"로 바꾸는 과정에서 내린 결정들과, 그 결정을 왜 그렇게 내렸는지를 기록한다.

***

## 0. 무엇을 자동화하려는가

시작점의 배포는 이랬다.

```
내 노트북에서 jar 빌드 → 서버에 scp → ssh 접속 → 기존 프로세스 kill → nohup java -jar
```

이 방식의 문제는 하나하나가 다 "내 손"에 의존한다는 것이다. 내 노트북의 JDK 버전, 내가 기억하는 실행 명령, 내가 빠뜨리지 않아야 하는 env 설정. 재현성이 없다. 그래서 목표를 이렇게 잡았다.

- **재현성**: 빌드 환경을 코드로 고정한다 → 컨테이너
- **자동화**: `main`에 반영되면 사람 손 없이 테스트·빌드·배포가 이어진다 → Jenkins
- **안전성**: 시크릿이 이미지·git에 남지 않는다, 배포가 데이터를 날리지 않는다

토폴로지는 이렇게 정리했다.

```
개발자 push
   │
   ▼
Jenkins ──① 테스트(도커 컨테이너 내부 Gradle)
        ──② 이미지 빌드
        ──③ GHCR push (ghcr.io/ccommit/stylehub:<빌드번호>)
        ──④ ssh 운영서버 ─▶ docker compose pull app && up -d app
                              (mysql / redis 는 볼륨 유지, app 만 교체)
```

***

## 1. 왜 멀티스테이지 Dockerfile인가

이미지를 만드는 방법은 두 갈래였다.

| 방식 | 판단 |
|---|---|
| 로컬에서 jar 빌드 → jar만 COPY | 이미지는 작지만 "빌드 환경"이 이미지 밖(내 노트북)에 남는다. 재현성 목표에 반함 |
| 단일 스테이지 (JDK+Gradle+소스 다 포함) | 재현은 되지만 최종 이미지에 JDK·Gradle·소스가 다 들어가 700MB+로 뚱뚱해짐 |
| **멀티스테이지 (빌드용 JDK 스테이지 → 런타임 JRE 스테이지)** | 빌드는 이미지 안에서(재현성 O), 최종엔 JRE+jar만 남김(경량). 채택 |

빌드 도구는 `builder` 스테이지에만 두고, 최종 이미지엔 실행에 필요한 것만 남겼다.

```dockerfile
# ---- Build stage ----
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /workspace

# 래퍼 + 빌드 스크립트를 먼저 복사 → 의존성 레이어 캐시
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

# 소스는 그 다음에 복사 → 소스만 바뀌면 의존성 레이어 재사용
COPY src ./src
RUN ./gradlew clean bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy AS runtime
...
COPY --from=builder --chown=spring:spring /workspace/build/libs/*-SNAPSHOT.jar app.jar
```

두 가지를 의도적으로 넣었다.

**(1) 레이어 캐시 순서.** `build.gradle`을 소스보다 먼저 복사한다. Docker는 레이어 단위로 캐시하는데, 소스 한 줄만 고쳐도 의존성 다운로드부터 다시 하면 매 빌드가 느리다. 빌드 스크립트가 안 바뀌면 의존성 해석 레이어를 통째로 재사용한다.

**(2) `*-SNAPSHOT.jar`만 복사.** `build/libs`에는 실행 가능한 boot jar(`stylehub-0.0.1-SNAPSHOT.jar`)와 라이브러리용 plain jar(`...-plain.jar`) 두 개가 나온다. glob이 `-SNAPSHOT.jar`로 끝나는 것만 잡으므로 plain jar는 자동으로 걸러진다. 여기서 plain jar를 잘못 복사하면 `no main manifest attribute`로 컨테이너가 죽는다 — 실제로 한 번 밟았던 함정이다.

그리고 **루트로 실행하지 않는다.** 전용 유저를 만들어 그 권한으로 돌렸다. 컨테이너가 탈취돼도 루트가 아니면 피해 범위가 줄어든다.

```dockerfile
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

`exec`를 붙인 이유: 이게 없으면 `sh`가 PID 1이 되고 java는 자식 프로세스가 된다. 그러면 `docker stop`이 보내는 SIGTERM이 java까지 안 닿아 graceful shutdown이 안 되고 10초 뒤 강제 kill 당한다. `exec`로 java가 PID 1을 물려받게 해서 종료 신호를 직접 받게 했다.

***

## 2. 컨테이너에서 JVM 힙을 어떻게 잡을까

로컬에선 `-Xmx`를 안 줘도 잘 돌았지만, 컨테이너에선 짚고 넘어가야 했다. 예전 JVM은 컨테이너 메모리 한도를 못 보고 **호스트 전체 메모리** 기준으로 힙을 잡아, cgroup 한도를 넘으면 OOMKill 당하는 고전적 함정이 있었다.

Java 17은 `UseContainerSupport`가 기본으로 켜져 있어 cgroup 한도를 인식한다. 그 위에서 절대값(`-Xmx512m`) 대신 비율로 관리했다.

```dockerfile
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
```

```yaml
# docker-compose.yml
app:
  mem_limit: 1g   # 이 값을 바꾸면 힙도 75% 비율로 따라감
```

이렇게 하면 컨테이너 메모리 한도(`mem_limit`)만 조정하면 힙이 자동으로 따라온다. 서버 사양이 바뀌어도 Dockerfile을 안 건드려도 된다.

***

## 3. 시크릿을 이미지에 박지 않기 — 이번 작업에서 가장 신경 쓴 것

기존 `application.properties`에는 이런 값들이 그대로 들어 있었다.

```properties
spring.datasource.password=Stylehub1!
google.client-secret=GOCSPX-...
toss.payments.secret-key=test_sk_...
```

로컬 개발이면 그러려니 하지만, **컨테이너 이미지에 이 파일이 그대로 구워진다.** 이미지를 GHCR에 push하는 순간 시크릿이 레지스트리에 박제된다. 이미지 레이어는 지운다고 히스토리에서 사라지지 않는다. 이건 넘어갈 수 없는 문제였다.

로컬 개발 흐름을 깨지 않으면서 운영에서만 외부 주입하도록, **`prod` 프로파일을 분리**했다. 기존 `application.properties`는 로컬 기본값으로 그대로 두고, `application-prod.properties`에서 민감값을 환경변수로만 읽게 했다.

```properties
# application-prod.properties
spring.datasource.password=${DB_PASSWORD}
google.client-secret=${GOOGLE_CLIENT_SECRET}
toss.payments.secret-key=${TOSS_SECRET_KEY}

spring.datasource.url=jdbc:mysql://${DB_HOST:mysql}:${DB_PORT:3306}/${DB_NAME:stylehub}?...
```

핵심은 **기본값을 안 준 것**이다. `${DB_PASSWORD:some-default}`처럼 기본값을 주면 미주입 상태로도 앱이 뜨는데, 그러면 실수로 시크릿 없이 운영이 기동될 수 있다. 기본값을 비워 두면 환경변수가 없을 때 앱이 아예 안 뜨면서 곧바로 문제를 알려준다. 반대로 호스트명(`DB_HOST:mysql`)처럼 시크릿이 아닌 값은 compose 서비스명을 기본값으로 줘서, 관리형 DB로 옮길 때만 `.env`에서 덮어쓰면 되게 했다.

컨테이너는 `prod` 프로파일로 뜨고, 실제 값은 운영서버의 `.env`(git 미커밋)에서 주입된다.

```dockerfile
ENV SPRING_PROFILES_ACTIVE=prod
```

```
# 운영서버 /opt/stylehub/.env  (커밋 안 함, .env.example만 커밋)
DB_PASSWORD=...
GOOGLE_CLIENT_SECRET=...
TOSS_SECRET_KEY=...
```

> `application.properties`는 처음부터 `.gitignore`가 아닌 untracked 상태로 두어 한 번도 커밋하지 않았다. 시크릿이 git 히스토리에 박힌 적이 없다. 이번 prod 프로파일 분리는 "로컬 개발 편의용 파일이 실수로 이미지에 구워지는 것"을 구조적으로 차단하는 작업이다.

***

## 4. 왜 GHCR + SSH 배포인가

이미지를 어디에 두고, 어떻게 서버에 내릴지 두 축으로 골랐다.

**레지스트리 — GHCR.** 코드가 이미 GitHub(ccommit/stylehub)에 있으니 이미지도 같은 생태계에 두는 게 자연스러웠다. 별도 계정 없이 GitHub PAT(`write:packages`)로 push하고, 리포지토리와 패키지 권한을 한 곳에서 관리한다. Docker Hub의 무료 pull rate limit 걱정도 없다.

**배포 방식 — SSH.** 후보는 셋이었다.

| 방식 | 판단 |
|---|---|
| 같은 호스트에서 compose | 가장 단순하지만 CI 서버와 운영이 한 몸이 됨. Jenkins가 죽으면 서비스도 영향 |
| **원격 운영서버로 SSH pull & up** | CI(Jenkins)와 운영을 분리. 이미지는 GHCR가 단일 진실 소스. 채택 |
| Kubernetes | 롤링·오토스케일이 공짜지만, 단일 서버 포트폴리오엔 운영 복잡도가 과함 |

Jenkins는 "이미지를 만들어 GHCR에 올리는 것"까지만 책임지고, 운영서버는 "GHCR에서 정해진 태그를 내려받아 뜨는 것"만 한다. 역할이 깔끔하게 갈린다. 쿠버네티스는 다중 인스턴스가 실제로 필요해지는 시점의 다음 과제로 미뤘다(이 프로젝트는 세션을 이미 Redis로 공유하도록 설계해 둬서, 수평 확장으로 넘어갈 때 세션 문제는 없다).

***

## 5. Jenkins 파이프라인 — 스테이지별 결정

`Jenkinsfile`(Declarative)로 다섯 스테이지를 뒀다. 각 스테이지에서 고민했던 지점만 짚는다.

### 5-1. Test — CI를 "도커 컨테이너 기반"으로

이번 작업의 요구가 "docker container 기반 CI"였다. 그래서 테스트를 Jenkins 호스트에 깐 JDK가 아니라, **도커 컨테이너 안의 Gradle**로 돌린다.

```groovy
stage('Test') {
    agent {
        docker {
            image 'eclipse-temurin:17-jdk-jammy'
            args '-v $HOME/.gradle:/root/.gradle'   // 의존성 캐시 재사용
            reuseNode true
        }
    }
    steps {
        sh 'chmod +x gradlew && ./gradlew clean test --no-daemon'
    }
    post {
        always { junit 'build/test-results/test/*.xml' }
    }
}
```

이렇게 하면 Jenkins 호스트엔 JDK를 안 깔아도 되고, 빌드 환경이 이미지 태그로 고정된다. 다른 프로젝트가 Java 21을 쓰든 말든 이 파이프라인은 항상 temurin 17에서 테스트된다. `$HOME/.gradle`을 볼륨으로 물려 매번 의존성을 새로 받지 않게 했다.

### 5-2. Build & Push — 태그 전략

이미지에 두 태그를 동시에 단다.

```groovy
sh 'docker build -t $IMAGE:$TAG -t $IMAGE:latest .'   // TAG = BUILD_NUMBER
```

- `:<빌드번호>` — 불변 태그. 어떤 배포가 몇 번 빌드였는지 추적되고, 문제 생기면 이전 번호로 롤백할 수 있다.
- `:latest` — 편의용 최신 포인터.

`disableConcurrentBuilds()`로 배포가 동시에 겹치지 않게 막았다. 두 빌드가 같은 `latest`를 밀거나 같은 서버에 동시에 `up` 하는 사고를 원천 차단한다.

### 5-3. Deploy — 토큰을 프로세스 인자에 남기지 않기

배포 스테이지는 SSH로 운영서버에 접속해 방금 push한 태그를 내린다.

```groovy
stage('Deploy') {
    steps {
        sshagent(credentials: ['stylehub-deploy-ssh']) {
            withCredentials([usernamePassword(credentialsId: 'ghcr-credentials',
                    usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN')]) {
                sh '''
                    ssh -o StrictHostKeyChecking=no $DEPLOY_HOST bash -s <<REMOTE
                        set -e
                        echo "$GHCR_TOKEN" | docker login $REGISTRY -u "$GHCR_USER" --password-stdin
                        cd $DEPLOY_DIR
                        export IMAGE_TAG=$TAG
                        docker compose pull app
                        docker compose up -d app
                        docker logout $REGISTRY
                        docker image prune -f
REMOTE
                '''
            }
        }
    }
}
```

두 가지가 포인트다.

**(1) `--password-stdin`.** `docker login -p $TOKEN`처럼 토큰을 인자로 넘기면 `ps`나 셸 히스토리에 그대로 노출된다. stdin으로 흘려 넣어 프로세스 목록에 안 남게 했다.

**(2) `app`만 교체.** `docker compose up -d app`은 app 서비스만 새 이미지로 재생성한다. mysql·redis는 건드리지 않으니 **데이터가 유지**된다. 이게 다음 절의 핵심이다.

***

## 6. 배포가 데이터를 날리지 않게 — 볼륨과 up의 범위

가장 무서운 시나리오는 "배포 한 번에 주문·결제 데이터가 사라지는 것"이었다. compose를 이렇게 설계해 막았다.

```yaml
mysql:
  image: mysql:8.0
  volumes:
    - mysql-data:/var/lib/mysql          # named volume → 컨테이너 재생성돼도 데이터 유지
    - ./stylehub.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro  # 빈 볼륨 최초 1회만 실행

redis:
  image: redis:7-alpine
  command: ["redis-server", "--appendonly", "yes"]  # AOF 영속화
  volumes:
    - redis-data:/data

volumes:
  mysql-data:
  redis-data:
```

두 가지 안전장치:

- **named volume.** 데이터가 컨테이너가 아니라 볼륨에 산다. `up -d app`으로 app 컨테이너를 갈아끼워도, 심지어 mysql 컨테이너를 재생성해도 `mysql-data` 볼륨이 그대로면 데이터는 산다.
- **init 스크립트는 최초 1회.** `docker-entrypoint-initdb.d`의 SQL은 **볼륨이 비었을 때만** 실행된다. 재배포마다 스키마를 다시 밀지 않는다. 그래서 배포 스텝을 `up -d app`으로 좁혀 mysql을 아예 재생성 대상에서 뺐다.

`JPA_DDL_AUTO`도 운영 기본을 `validate`로 뒀다. 앱이 스키마를 마음대로 바꾸지 못하게 하고(운영에서 `update`/`create`는 사고의 지름길), 스키마 변경은 명시적 마이그레이션으로만 하겠다는 의도다. 최초 배포처럼 앱이 스키마를 만들어야 할 때만 `.env`에서 한시적으로 `update`로 올린다.

***

## 7. 배포가 "떴는지"를 어떻게 판단할까 — 헬스체크

`up -d`는 컨테이너가 **떴다**는 것만 알려주지, 앱이 **요청을 받을 준비가 됐는지**는 모른다. 스프링은 부팅에 수십 초가 걸리는데, 그 사이 컨테이너는 이미 "running"이다.

그래서 actuator를 추가하고(`spring-boot-starter-actuator`) 헬스 엔드포인트를 실제 상태 판단에 썼다.

```dockerfile
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
```

```yaml
# compose — app 은 mysql/redis 가 healthy 여야 뜬다
app:
  depends_on:
    mysql: { condition: service_healthy }
    redis: { condition: service_healthy }
```

`start_period: 60s`로 부팅 유예를 준다. 이 시간 안엔 헬스 실패를 재시작 트리거로 세지 않는다. actuator는 노출을 `health`, `info`로만 좁혀(`show-details: never`) 내부 정보가 새지 않게 했다.

> 다만 지금 구조는 **완전한 무중단(zero-downtime)은 아니다.** `up -d app`은 기존 컨테이너를 내리고 새 컨테이너를 올리므로, 새 컨테이너가 healthy가 될 때까지 짧은 공백이 생긴다. 진짜 무중단은 리버스 프록시 뒤에 인스턴스를 2개 두고 하나씩 교체하는 blue-green/rolling이 필요하다. 이건 8절의 과제로 넘긴다.

***

## 8. 남은 과제 (정직하게)

이번에 만든 건 "재현 가능한 자동 배포 파이프라인"까지다. 아직 안 한 것들:

- **무중단 배포.** 지금은 배포 순간 수 초의 공백이 있다. nginx + app 2인스턴스 롤링으로 없앨 수 있고, 이미 세션을 Redis로 공유하도록 설계해 둬서 인스턴스를 늘려도 세션은 안 깨진다 — 확장의 발판은 만들어 둔 셈이다.
- **시크릿 매니저 도입.** 지금은 운영서버의 `.env` 파일에 값을 직접 둔다. 한 단계 더 나아가면 HashiCorp Vault나 AWS Secrets Manager 같은 시크릿 매니저로 옮겨 키 로테이션·감사 로그를 중앙화할 수 있다.
- **배포 검증·자동 롤백.** 지금은 헬스체크로 "떴는지"만 본다. 배포 후 스모크 테스트를 돌리고, 실패하면 이전 태그로 자동 롤백하는 스텝을 붙이면 더 안전하다. 불변 태그(`:<빌드번호>`)를 남겨 뒀으니 롤백 자체는 태그만 바꿔 `up` 하면 된다.

***

## 마치며

코드를 잘 짜는 것과, 그 코드를 남이(또는 미래의 내가) 똑같이 재현해서 띄우는 것은 다른 문제였다. 이번 작업에서 배운 건 배포도 결국 **설계 결정의 연속**이라는 것이다 — 시크릿을 어디에 둘지, 배포가 무엇을 건드리고 무엇을 건드리지 않을지, "떴다"를 무엇으로 판단할지. 각각에 "왜 그렇게 했는가"를 붙일 수 있게 된 것이 이번 작업의 진짜 결과물이다.

### 이번에 추가/변경한 파일

| 파일 | 역할 |
|---|---|
| `Dockerfile` | 멀티스테이지 빌드 → 경량 JRE 런타임 이미지 |
| `.dockerignore` | 빌드 컨텍스트에서 산출물·문서·시크릿 제외 |
| `application-prod.properties` | 운영 프로파일 — 시크릿 env 외부화 |
| `.env.example` | 운영서버 환경변수 템플릿 (실제 `.env`는 미커밋) |
| `docker-compose.yml` | 운영서버용 app+mysql+redis, 볼륨/헬스 게이트 |
| `Jenkinsfile` | test → build → GHCR push → SSH 배포 파이프라인 |
| `build.gradle` | actuator 의존성 추가 (헬스체크) |
