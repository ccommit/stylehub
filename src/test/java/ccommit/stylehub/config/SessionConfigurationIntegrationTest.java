package ccommit.stylehub.config;

import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 * @modified 2026/09/17 by WonJin - test: 가입 회원 정리를 포인트 이력까지 지우는 OrderFixtureFactory.deleteByIds 로 변경 (로그인 적립이 이력을 남기게 됨)
 *
 * <p>
 * Boot 4에서 세션 자동 설정 모듈이 빠지면 설정이 조용히 무시되므로, 실제 쿠키와 Redis 키로 세션 설정 반영을 검증하는 통합 테스트이다.
 * 쿠키·Redis 저장은 SessionRepositoryFilter가 맡아 실제 포트를 열고, 기본값과 다른 Secure·네임스페이스·타임아웃으로 바인딩 여부를 가린다.
 * </p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.session.data.redis.namespace=" + SessionConfigurationIntegrationTest.NAMESPACE,
                "spring.session.timeout=17m",
                "server.servlet.session.cookie.http-only=true",
                "server.servlet.session.cookie.same-site=lax",
                "server.servlet.session.cookie.secure=true"
        }
)
class SessionConfigurationIntegrationTest {

    static final String NAMESPACE = "stylehub-test:session-it";
    private static final long TIMEOUT_SECONDS = TimeUnit.MINUTES.toSeconds(17);
    private static final String DEFAULT_NAMESPACE = "spring:session";
    private static final String SESSION_COOKIE = "SESSION";
    private static final String PASSWORD = "Test1234!";

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    private final List<String> createdEmails = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        Set<String> keys = redisTemplate.keys(NAMESPACE + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        // 로그인 적립 이력이 회원을 참조하므로 이력부터 지운다.
        List<Long> userIds = createdEmails.stream()
                .flatMap(email -> userRepository.findByEmail(email).stream())
                .map(User::getUserId)
                .toList();
        fixtureFactory.deleteByIds(List.of(), List.of(), userIds);
        createdEmails.clear();
    }

    @Test
    @DisplayName("로그인 응답의 세션 쿠키에 HttpOnly, SameSite=Lax, 설정한 Secure 가 붙는다")
    void sessionCookieCarriesConfiguredAttributes() throws Exception {
        String setCookie = sessionSetCookie(login(signUp()));

        assertThat(setCookie)
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                // 평문 HTTP 요청이라 설정이 무시되면 Secure 는 붙지 않는다
                .contains("Secure");
    }

    @Test
    @DisplayName("세션은 설정한 네임스페이스와 타임아웃으로 Redis 에 저장되고, 그 세션으로 인증된 요청을 보낼 수 있다")
    void sessionIsStoredUnderConfiguredNamespace() throws Exception {
        // given
        String cookieValue = cookieValue(sessionSetCookie(login(signUp())));
        String sessionId = new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
        String sessionKey = NAMESPACE + ":sessions:" + sessionId;

        // then — 테스트 네임스페이스에 저장되고 기본 네임스페이스에는 없다
        assertThat(redisTemplate.hasKey(sessionKey)).isTrue();
        assertThat(redisTemplate.hasKey(DEFAULT_NAMESPACE + ":sessions:" + sessionId)).isFalse();
        Long ttl = redisTemplate.getExpire(sessionKey, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(TIMEOUT_SECONDS);

        // when — 로그아웃은 인증이 필요한 API 라 Redis 에서 세션을 읽어야 통과하고, 성공하면 세션을 지운다
        HttpResponse<String> logout = http.send(HttpRequest.newBuilder(uri("/api/v1/users/logout"))
                .header("Cookie", SESSION_COOKIE + "=" + cookieValue)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        // then
        assertThat(logout.statusCode()).as(logout.body()).isEqualTo(200);
        assertThat(redisTemplate.hasKey(sessionKey)).isFalse();
    }

    // ===== Helper =====

    private String signUp() throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String email = "session-it-" + unique + "@test.com";
        createdEmails.add(email);
        String body = """
                {"name":"se%s","email":"%s","password":"%s","birthDate":"1995-01-01"}
                """.formatted(unique, email, PASSWORD);

        HttpResponse<String> response = http.send(postJson("/api/v1/users/sign-up", body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return email;
    }

    private HttpResponse<String> login(String email) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD);

        HttpResponse<String> response = http.send(postJson("/api/v1/users/login", body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response;
    }

    private String sessionSetCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("세션 쿠키가 발급되지 않았다: " + response.headers().map()));
    }

    private String cookieValue(String setCookie) {
        return setCookie.substring((SESSION_COOKIE + "=").length(), setCookie.indexOf(';'));
    }

    private HttpRequest postJson(String path, String json) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
