# syntax=docker/dockerfile:1

# =========================================================================
# StyleHub 애플리케이션 이미지
#
# 멀티스테이지 빌드 — 빌드 도구(JDK, Gradle)는 build 스테이지에만 두고,
# 최종 이미지에는 JRE + jar 만 남겨 크기와 공격 표면을 최소화한다.
# 테스트는 Jenkins 의 Test 스테이지에서 별도 수행하므로 여기선 -x test.
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
# 운영에서 -Xmx 대신 비율로 관리 → compose 의 mem_limit 만 바꾸면 힙도 따라감
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
ENV SPRING_PROFILES_ACTIVE=prod

# 애플리케이션 자체 헬스 (actuator) 로 컨테이너 상태를 판단
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
