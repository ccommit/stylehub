package ccommit.stylehub.user.service;

import ccommit.stylehub.common.config.PasswordHasher;
import ccommit.stylehub.user.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/15
 * @modified 2026/09/17 by WonJin - test: 측정값 출력을 System.out 에서 SLF4J 로 교체, 가입시킨 회원을 @AfterEach 로 정리
 *
 * <p>
 * BCrypt 해싱·검증 중 요청 스레드가 DB 커넥션을 쥐는지 실제 HTTP 요청으로 측정하는 공통 시나리오로, 하위 클래스는 OSIV 설정만 다르다.
 * 풀 크기를 2로 줄여, 로그인 두 건이 BCrypt 구간에 머무는 동안 다른 요청이 커넥션을 얻을 수 있는지도 확인한다.
 * </p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.hikari.maximum-pool-size=2",
                "spring.datasource.hikari.connection-timeout=250"
        }
)
@Import(LoginConnectionHoldingTestSupport.ProbeConfig.class)
abstract class LoginConnectionHoldingTestSupport {

    private static final Logger log = LoggerFactory.getLogger(LoginConnectionHoldingTestSupport.class);
    private static final String PASSWORD = "Passw0rd!";
    private static final AtomicLong SEQUENCE = new AtomicLong(Math.floorMod(System.nanoTime(), 1_000_000L));

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ProbingPasswordHasher hasher;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository userRepository;

    private final List<String> createdEmails = new ArrayList<>();

    protected abstract boolean osivEnabled();

    @BeforeEach
    void resetProbe() {
        hasher.reset();
    }

    @AfterEach
    void cleanUp() {
        hasher.reset();
        createdEmails.forEach(email -> userRepository.findByEmail(email).ifPresent(userRepository::delete));
        createdEmails.clear();
    }

    @Test
    @DisplayName("로그인: BCrypt 검증이 실행되는 동안 요청 스레드가 DB 커넥션을 쥐고 있는지 확인한다")
    void loginVerifyConnectionHolding() throws Exception {
        String email = signUp();
        hasher.reset();

        HttpResponse<String> response = login(email);

        assertThat(response.statusCode()).isEqualTo(200);
        Probe probe = hasher.verifyProbes().get(0);
        report("로그인 BCrypt 검증", probe);
        assertThat(probe.threadHoldsConnection()).isEqualTo(osivEnabled());
    }

    @Test
    @DisplayName("회원가입: 해싱이 DB 접근보다 먼저 실행되므로 OSIV 설정과 무관하게 커넥션을 쥐고 있지 않다")
    void signUpHashConnectionHolding() throws Exception {
        signUp();

        Probe probe = hasher.hashProbes().get(0);
        report("회원가입 BCrypt 해싱", probe);
        assertThat(probe.threadHoldsConnection()).isFalse();
    }

    @Test
    @DisplayName("풀 크기만큼의 로그인이 BCrypt 구간에 머무는 동안 다른 요청이 커넥션을 얻을 수 있는지 확인한다")
    void otherRequestAcquiresConnectionWhileLoginsVerify() throws Exception {
        String first = signUp();
        String second = signUp();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        hasher.pauseVerifications(entered, release);

        CompletableFuture<HttpResponse<String>> firstLogin = loginAsync(first);
        CompletableFuture<HttpResponse<String>> secondLogin = loginAsync(second);

        boolean bothEntered;
        int activeConnections;
        boolean acquired;
        try {
            bothEntered = entered.await(10, TimeUnit.SECONDS);
            activeConnections = hasher.activeConnections(dataSource);
            acquired = tryAcquireConnection();
        } finally {
            hasher.resumeVerifications();
            release.countDown();
        }

        assertThat(bothEntered).isTrue();
        assertThat(firstLogin.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(secondLogin.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        log.info("[OSIV={}] 로그인 2건이 BCrypt 구간에 있을 때 — 활성 커넥션 {}/2, 다른 요청의 커넥션 획득 {}",
                osivEnabled(), activeConnections, acquired ? "성공" : "실패(250ms 타임아웃)");
        assertThat(acquired).isEqualTo(!osivEnabled());
    }

    private boolean tryAcquireConnection() throws SQLException {
        try (Connection ignored = dataSource.getConnection()) {
            return true;
        } catch (SQLTransientConnectionException e) {
            return false;
        }
    }

    private String signUp() throws Exception {
        long seq = SEQUENCE.incrementAndGet();
        String email = "osiv" + seq + "@test.com";
        createdEmails.add(email);
        String body = """
                {"name":"u%d","email":"%s","password":"%s","birthDate":"1995-01-01"}
                """.formatted(seq, email, PASSWORD);
        HttpResponse<String> response = http.send(post("/api/v1/users/sign-up", body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return email;
    }

    private HttpResponse<String> login(String email) throws Exception {
        return http.send(loginRequest(email), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> loginAsync(String email) {
        return http.sendAsync(loginRequest(email), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest loginRequest(String email) {
        return post("/api/v1/users/login", """
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD));
    }

    private HttpRequest post(String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private void report(String label, Probe probe) {
        log.info("[OSIV={}] {} — 요청 스레드 커넥션 보유: {}, BCrypt 소요: {}ms",
                osivEnabled(), label, probe.threadHoldsConnection(), probe.elapsedMillis());
    }

    record Probe(boolean threadHoldsConnection, long elapsedMillis) {
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        @Primary
        ProbingPasswordHasher probingPasswordHasher(EntityManagerFactory entityManagerFactory) {
            return new ProbingPasswordHasher(entityManagerFactory);
        }
    }

    static class ProbingPasswordHasher extends PasswordHasher {

        private final EntityManagerFactory entityManagerFactory;
        private final List<Probe> hashProbes = new CopyOnWriteArrayList<>();
        private final List<Probe> verifyProbes = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        ProbingPasswordHasher(EntityManagerFactory entityManagerFactory) {
            this.entityManagerFactory = entityManagerFactory;
        }

        @Override
        public String hash(String password) {
            boolean held = threadHoldsConnection();
            long start = System.nanoTime();
            String hashed = super.hash(password);
            hashProbes.add(new Probe(held, elapsedMillis(start)));
            return hashed;
        }

        @Override
        public boolean matches(String password, String hashedPassword) {
            boolean held = threadHoldsConnection();
            CountDownLatch enteredLatch = entered;
            CountDownLatch releaseLatch = release;
            if (enteredLatch != null) {
                enteredLatch.countDown();
                awaitQuietly(releaseLatch);
            }
            long start = System.nanoTime();
            boolean verified = super.matches(password, hashedPassword);
            verifyProbes.add(new Probe(held, elapsedMillis(start)));
            return verified;
        }

        // OSIV가 요청 스레드에 묶어둔 EntityManager가 없으면 커넥션도 없다. 있으면 물리 커넥션이 실제로 붙어 있는지 본다.
        private boolean threadHoldsConnection() {
            EntityManagerHolder holder =
                    (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
            if (holder == null) {
                return false;
            }
            return holder.getEntityManager()
                    .unwrap(SharedSessionContractImplementor.class)
                    .getJdbcCoordinator()
                    .getLogicalConnection()
                    .isPhysicallyConnected();
        }

        int activeConnections(DataSource dataSource) throws SQLException {
            return dataSource.unwrap(HikariDataSource.class)
                    .getHikariPoolMXBean()
                    .getActiveConnections();
        }

        void pauseVerifications(CountDownLatch enteredLatch, CountDownLatch releaseLatch) {
            this.release = releaseLatch;
            this.entered = enteredLatch;
        }

        void resumeVerifications() {
            this.entered = null;
            this.release = null;
        }

        void reset() {
            hashProbes.clear();
            verifyProbes.clear();
            resumeVerifications();
        }

        List<Probe> hashProbes() {
            return hashProbes;
        }

        List<Probe> verifyProbes() {
            return verifyProbes;
        }

        private static long elapsedMillis(long startNanos) {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        }

        private static void awaitQuietly(CountDownLatch latch) {
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
