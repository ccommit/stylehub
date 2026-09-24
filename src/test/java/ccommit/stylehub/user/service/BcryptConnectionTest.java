package ccommit.stylehub.user.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import ccommit.stylehub.support.container.SharedContainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/09/17 by WonJin - test: 중복 Javadoc 3개를 헤더 하나로 합치고, 풀 크기·요청 수를 실제 상수와 맞추고, 측정 조건의 한계를 사실대로 정정(테스트 로직은 그대로)
 * @modified 2026/09/17 by WonJin - test: 측정값 출력을 System.out 에서 SLF4J(log.info)로 교체
 * @modified 2026/09/18 by WonJin - test: H2 제거에 따라 공유 MySQL 컨테이너의 별도 데이터베이스로 접속
 *
 * <p>
 * BCrypt 해싱을 커넥션을 잡은 채 할 때와 얻기 전에 할 때의 풀 영향을 Spring 없이 HikariCP·MySQL 컨테이너로 비교한다.
 * cost·DB·풀·쿼리가 운영과 달라 수치를 운영 성능으로 읽으면 안 되며, 실제 요청의 점유는 LoginConnectionHoldingOsivOnTest·LoginConnectionHoldingOsivOffTest가 측정한다.
 * </p>
 */
class BcryptConnectionTest {

    private static final Logger log = LoggerFactory.getLogger(BcryptConnectionTest.class);

    private static HikariDataSource dataSource;

    private static final int POOL_SIZE = 5;
    private static final int CONCURRENT_REQUESTS = 100;
    private static final int CONNECTION_TIMEOUT_MS = 500; // 타임아웃 500ms (빠른 실패 확인용)
    private static final int BCRYPT_COST = 12;
    private static final String PASSWORD = "password123!";

    @BeforeAll
    static void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(SharedContainers.mysqlDatabaseUrl("bcrypt_connection_test"));
        config.setUsername(SharedContainers.mysql().getUsername());
        config.setPassword(SharedContainers.mysql().getPassword());
        config.setMaximumPoolSize(POOL_SIZE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setPoolName("TestPool");

        dataSource = new HikariDataSource(config);

        // 테스트용 테이블 생성
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "user_id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(20) NOT NULL, "
                    + "email VARCHAR(100) NOT NULL UNIQUE)");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @DisplayName("BCrypt 를 커넥션 밖에서 실행하면 같은 부하에서 풀 고갈로 인한 타임아웃이 줄어든다")
    void bcryptOutsideConnection_reducesPoolExhaustion() throws Exception {
        // BCrypt 워밍업 (첫 호출은 느릴 수 있으므로)
        BCrypt.withDefaults().hashToString(BCRYPT_COST, "warmup".toCharArray());

        // 두 방식을 같은 실행·같은 머신에서 연달아 재고, 절대값이 아니라 차이를 본다.
        // 절대 기준(타임아웃 0건)은 머신 속도에 따라 달라져 CI 에서 흔들린다.
        Result before = runScenario(true, "before");
        deleteAllRows();
        Result after = runScenario(false, "after");

        log.info("[변경 전] BCrypt IN Transaction — 풀 크기={}, 동시 요청={}, 성공={}, 타임아웃={}, 평균={}ms, 최대={}ms, 전체={}ms",
                POOL_SIZE, CONCURRENT_REQUESTS, before.success, before.timeout, before.avgMs, before.maxMs, before.totalMs);
        log.info("[변경 후] BCrypt OUT of Transaction — 풀 크기={}, 동시 요청={}, 성공={}, 타임아웃={}, 평균={}ms, 최대={}ms, 전체={}ms",
                POOL_SIZE, CONCURRENT_REQUESTS, after.success, after.timeout, after.avgMs, after.maxMs, after.totalMs);

        assertThat(before.timeout)
                .as("커넥션을 쥔 채 해싱하면 풀이 고갈돼 타임아웃이 발생해야 한다")
                .isGreaterThan(0);
        assertThat(after.timeout)
                .as("해싱을 커넥션 밖으로 빼면 타임아웃이 줄어야 한다 (변경 전 %d건)", before.timeout)
                .isLessThan(before.timeout);
        assertThat(after.success)
                .as("해싱을 커넥션 밖으로 빼면 성공 건수가 늘어야 한다 (변경 전 %d건)", before.success)
                .isGreaterThan(before.success);
    }

    private record Result(int success, int timeout, long avgMs, long maxMs, long totalMs) {
    }

    // bcryptInsideConnection=true 면 커넥션을 잡은 채 해싱하고, false 면 해싱을 먼저 끝낸 뒤 커넥션을 잡는다.
    private Result runScenario(boolean bcryptInsideConnection, String emailPrefix) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);
        List<Future<Long>> futures = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                startLatch.await(); // 모든 스레드가 동시에 시작
                long start = System.currentTimeMillis();
                try {
                    String email = emailPrefix + "_" + index + "@test.com";
                    if (bcryptInsideConnection) {
                        try (Connection conn = dataSource.getConnection()) {
                            conn.setAutoCommit(false);
                            selectByEmail(conn, email);
                            BCrypt.withDefaults().hashToString(BCRYPT_COST, PASSWORD.toCharArray());
                            insertUser(conn, index, email);
                            conn.commit();
                            successCount.incrementAndGet();
                        }
                    } else {
                        BCrypt.withDefaults().hashToString(BCRYPT_COST, PASSWORD.toCharArray());
                        try (Connection conn = dataSource.getConnection()) {
                            conn.setAutoCommit(false);
                            selectByEmail(conn, email);
                            insertUser(conn, index, email);
                            conn.commit();
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    timeoutCount.incrementAndGet();
                }
                return System.currentTimeMillis() - start;
            }));
        }

        long testStart = System.currentTimeMillis();
        startLatch.countDown(); // 동시 시작

        List<Long> durations = new ArrayList<>();
        for (Future<Long> f : futures) {
            durations.add(f.get());
        }
        long totalTime = System.currentTimeMillis() - testStart;
        executor.shutdown();

        long avgDuration = durations.stream().mapToLong(Long::longValue).sum() / durations.size();
        long maxDuration = durations.stream().mapToLong(Long::longValue).max().orElse(0);
        return new Result(successCount.get(), timeoutCount.get(), avgDuration, maxDuration, totalTime);
    }

    private void selectByEmail(Connection conn, String email) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT COUNT(*) FROM users WHERE email = '" + email + "'");
        }
    }

    private void insertUser(Connection conn, int index, String email) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (name, email) VALUES ('user" + index + "', '" + email + "')");
        }
    }

    private void deleteAllRows() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM users");
        }
    }

    @Test
    @DisplayName("BCrypt cost 12 vs 10 해싱 시간 비교")
    void compareBcryptCost() {
        int iterations = 100;
        String password = "password123!";

        // 워밍업
        BCrypt.withDefaults().hashToString(12, password.toCharArray());
        BCrypt.withDefaults().hashToString(10, password.toCharArray());

        // cost=12 측정
        long cost12Start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            BCrypt.withDefaults().hashToString(12, password.toCharArray());
        }
        long cost12Total = System.currentTimeMillis() - cost12Start;
        long cost12Avg = cost12Total / iterations;

        // cost=10 측정
        long cost10Start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            BCrypt.withDefaults().hashToString(10, password.toCharArray());
        }
        long cost10Total = System.currentTimeMillis() - cost10Start;
        long cost10Avg = cost10Total / iterations;

        double improvementRate = (1.0 - (double) cost10Avg / cost12Avg) * 100;

        log.info("BCrypt cost 비교(각 {}회) — cost=12 평균={}ms/총={}ms, cost=10 평균={}ms/총={}ms, 감소율={}%",
                iterations, cost12Avg, cost12Total, cost10Avg, cost10Total, String.format("%.1f", improvementRate));

        // cost=10이 cost=12보다 빨라야 한다
        assertThat(cost10Avg)
                .as("cost=10이 cost=12보다 빨라야 한다")
                .isLessThan(cost12Avg);
    }
}
