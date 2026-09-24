# syntax=docker/dockerfile:1

# =========================================================================
# StyleHub 애플리케이션 이미지 — 현재 배포·CI·로컬 실행 어디에서도 쓰지 않는다
#
# 사용처 (저장소 기준으로 이 파일을 참조하는 곳이 없다)
#   - 운영 배포: Jenkinsfile 은 이미지를 만들지 않는다. bootJar 로 만든 jar 를 운영서버에 SCP 로 올리고
#     systemd(stylehub.service)로 재시작하는 네이티브 배포다.
#   - CI: .github/workflows/ci.yml 은 테스트만 실행한다.
#   - 로컬 실행: docker-compose.yml 은 MySQL·Redis 만 띄우고, 앱은 SPRING_PROFILES_ACTIVE=local ./gradlew bootRun 으로 실행한다.
#   예전 GHCR 이미지 배포(Jenkins 이미지 빌드 → GHCR push → 운영서버 compose pull) 때 만든 파일이 배포 방식이 바뀐 뒤 남아 있다.
#   삭제 여부는 별도로 결정한다.
#
# 직접 이미지를 만들어 볼 때
#   docker build -t stylehub:local .
#   이미지의 기본 프로파일이 prod 라 application-prod.properties 가 요구하는 환경 변수(.env.example 의 [운영] 구역)를
#   docker run --env-file 등으로 넣어야 기동한다. 컨테이너 안의 localhost 는 호스트가 아니므로 DB_HOST·REDIS_HOST 도 지정한다.
#
# 멀티스테이지 빌드 — 빌드 도구(JDK, Gradle)는 build 스테이지에만 두고,
# 최종 이미지에는 JRE + jar 만 남겨 크기와 공격 표면을 줄인다.
# 테스트는 CI(GitHub Actions)와 Jenkins 의 Test 스테이지에서 수행하므로 여기선 -x test.
# =========================================================================

# ---- Build stage --------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /workspace

# Gradle 래퍼 + 빌드 스크립트를 먼저 복사해 의존성 해석 레이어를 캐시한다.
# (소스만 바뀌면 이 레이어는 재사용되어 재빌드가 빨라진다)
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

# 소스 복사 후 실행 가능한 boot jar 만 생성 (plain jar 제외, 테스트 제외)
COPY src ./src
RUN ./gradlew clean bootJar --no-daemon -x test

# ---- Runtime stage ------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# 헬스체크(curl)용 최소 패키지만 설치 후 캐시 정리
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 루트로 실행하지 않기 위한 전용 유저
RUN groupadd --system spring && useradd --system --gid spring spring

# build 스테이지에서 만든 boot jar 만 가져온다 (-plain.jar 는 제외됨)
COPY --from=builder --chown=spring:spring /workspace/build/libs/*-SNAPSHOT.jar app.jar

USER spring:spring

EXPOSE 8080

# 컨테이너 메모리 한도를 JVM 이 인식하도록 (Java 17 UseContainerSupport 기본 on)
# -Xmx 대신 비율로 두어, docker run --memory 로 준 한도에 힙이 따라가게 한다
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
ENV SPRING_PROFILES_ACTIVE=prod

# 애플리케이션 자체 헬스 (actuator) 로 컨테이너 상태를 판단. 관리 포트(9081)는 컨테이너 안에서만 열린다.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:9081/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
