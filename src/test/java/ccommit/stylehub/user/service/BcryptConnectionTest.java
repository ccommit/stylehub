package ccommit.stylehub.user.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
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
 *
 * <p>
 * BCrypt 해싱 위치에 따른 커넥션 풀 영향을 비교하는 성능 테스트이다.
 * 트랜잭션 분리 설계가 커넥션 풀 고갈을 방지함을 실증한다.
 * </p>
 */

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @summary BCrypt 커넥션 점유 비교 테스트
 */

/**
 * BCrypt 커넥션 점유 문제 — 변경 전/후 비교 테스트
 *
 * HikariCP 커넥션 풀(최대 10개)에 동시 50개 요청을 보내서
 * 커넥션 점유 시간, 타임아웃 발생 여부, 처리량을 비교한다.
 */
class BcryptConnectionTest {

    private static HikariDataSource dataSource;

    private static final int POOL_SIZE = 5;
    private static final int CONCURRENT_REQUESTS = 100;
    private static final int CONNECTION_TIMEOUT_MS = 500; // 타임아웃 500ms (빠른 실패 확인용)

    @BeforeAll
    static void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
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
    @DisplayName("변경 전: BCrypt가 트랜잭션 안에서 실행 → 커넥션 풀 고갈")
    void before_bcryptInsideTransaction() throws Exception {
        // BCrypt 워밍업 (첫 호출은 느릴 수 있으므로)
        BCrypt.withDefaults().hashToString(12, "warmup".toCharArray());

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
                    // === 변경 전 방식: 커넥션 획득 → BCrypt → INSERT → 커넥션 반환 ===
                    try (Connection conn = dataSource.getConnection()) {
                        conn.setAutoCommit(false);

                        // SELECT (중복 검증 시뮬레이션)
                        try (Statement stmt = conn.createStatement()) {
                            stmt.executeQuery("SELECT COUNT(*) FROM users WHERE email = 'before_" + index + "@test.com'");
                        }

                        // BCrypt — 커넥션을 잡고 있는 채로 실행
                        String encoded = BCrypt.withDefaults().hashToString(12, "password123!".toCharArray());

                        // INSERT
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute("INSERT INTO users (name, email) VALUES ('user" + index + "', 'before_" + index + "@test.com')");
                        }

                        conn.commit();
                        successCount.incrementAndGet();
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

        System.out.println("========================================");
        System.out.println("  [변경 전] BCrypt IN Transaction");
        System.out.println("========================================");
        System.out.println("  커넥션 풀 크기     : " + POOL_SIZE);
        System.out.println("  동시 요청 수       : " + CONCURRENT_REQUESTS);
        System.out.println("  성공               : " + successCount.get());
        System.out.println("  타임아웃 (실패)    : " + timeoutCount.get());
        System.out.println("  평균 응답 시간     : " + avgDuration + "ms");
        System.out.println("  최대 응답 시간     : " + maxDuration + "ms");
        System.out.println("  전체 소요 시간     : " + totalTime + "ms");
        System.out.println("========================================");

        // 커넥션 타임아웃이 발생해야 한다 (풀 고갈 증명)
        assertThat(timeoutCount.get())
                .as("커넥션 풀 고갈로 타임아웃이 발생해야 한다")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("변경 후: BCrypt가 트랜잭션 밖에서 실행 → 커넥션 풀 안정")
    void after_bcryptOutsideTransaction() throws Exception {
        // 이전 테스트 데이터 정리
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM users");
        }

        // BCrypt 워밍업
        BCrypt.withDefaults().hashToString(12, "warmup".toCharArray());

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);
        List<Future<Long>> futures = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                long start = System.currentTimeMillis();
                try {
                    // === 변경 후 방식: BCrypt 먼저 → 커넥션 획득 → INSERT → 커넥션 반환 ===

                    // BCrypt — 커넥션 없이 실행
                    String encoded = BCrypt.withDefaults().hashToString(12, "password123!".toCharArray());

                    // 커넥션 획득 → SELECT + INSERT만 → 바로 반환
                    try (Connection conn = dataSource.getConnection()) {
                        conn.setAutoCommit(false);

                        try (Statement stmt = conn.createStatement()) {
                            stmt.executeQuery("SELECT COUNT(*) FROM users WHERE email = 'after_" + index + "@test.com'");
                        }

                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute("INSERT INTO users (name, email) VALUES ('user" + index + "', 'after_" + index + "@test.com')");
                        }

                        conn.commit();
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    timeoutCount.incrementAndGet();
                }
                return System.currentTimeMillis() - start;
            }));
        }

        long testStart = System.currentTimeMillis();
        startLatch.countDown();

        List<Long> durations = new ArrayList<>();
        for (Future<Long> f : futures) {
            durations.add(f.get());
        }
        long totalTime = System.currentTimeMillis() - testStart;

        executor.shutdown();

        long avgDuration = durations.stream().mapToLong(Long::longValue).sum() / durations.size();
        long maxDuration = durations.stream().mapToLong(Long::longValue).max().orElse(0);

        System.out.println("========================================");
        System.out.println("  [변경 후] BCrypt OUT of Transaction");
        System.out.println("========================================");
        System.out.println("  커넥션 풀 크기     : " + POOL_SIZE);
        System.out.println("  동시 요청 수       : " + CONCURRENT_REQUESTS);
        System.out.println("  성공               : " + successCount.get());
        System.out.println("  타임아웃 (실패)    : " + timeoutCount.get());
        System.out.println("  평균 응답 시간     : " + avgDuration + "ms");
        System.out.println("  최대 응답 시간     : " + maxDuration + "ms");
        System.out.println("  전체 소요 시간     : " + totalTime + "ms");
        System.out.println("========================================");

        // 타임아웃 없이 전부 성공해야 한다
        assertThat(timeoutCount.get())
                .as("커넥션 풀 고갈 없이 전부 성공해야 한다")
                .isEqualTo(0);
        assertThat(successCount.get())
                .isEqualTo(CONCURRENT_REQUESTS);
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

        System.out.println("========================================");
        System.out.println("  BCrypt Cost 비교 (각 " + iterations + "회 반복)");
        System.out.println("========================================");
        System.out.println("  cost=12 평균     : " + cost12Avg + "ms");
        System.out.println("  cost=12 총 시간  : " + cost12Total + "ms");
        System.out.println("  cost=10 평균     : " + cost10Avg + "ms");
        System.out.println("  cost=10 총 시간  : " + cost10Total + "ms");
        System.out.println("  개선율           : " + String.format("%.1f", improvementRate) + "% 감소");
        System.out.println("========================================");

        // cost=10이 cost=12보다 빨라야 한다
        assertThat(cost10Avg)
                .as("cost=10이 cost=12보다 빨라야 한다")
                .isLessThan(cost12Avg);
    }
}
