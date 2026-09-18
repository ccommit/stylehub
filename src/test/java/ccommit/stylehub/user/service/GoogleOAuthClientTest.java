package ccommit.stylehub.user.service;

import ccommit.stylehub.common.config.GoogleOAuthProperties;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * GoogleOAuthClient의 통신 예외 매핑을 JDK 내장 HTTP 서버에 실제 HTTP로 호출해 검증하는 단위 테스트이다.
 * RestClient를 내부에서 직접 만들어 목으로 대체하지 않고, 매핑이 상태 코드별 실제 예외 타입에 의존해 이 편이 회귀를 잘 잡는다.
 * </p>
 */
class GoogleOAuthClientTest {

    private static final String TOKEN_BODY = """
            {"access_token":"access-123","token_type":"Bearer","expires_in":3599}
            """;
    private static final String USERINFO_BODY = """
            {"sub":"google-sub-1","name":"홍길동","email":"hong@gmail.com"}
            """;

    private HttpServer server;
    private volatile StubResponse tokenResponse;
    private volatile StubResponse userInfoResponse;

    @BeforeEach
    void startServer() throws IOException {
        tokenResponse = new StubResponse(200, TOKEN_BODY);
        userInfoResponse = new StubResponse(200, USERINFO_BODY);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> respond(exchange, tokenResponse));
        server.createContext("/userinfo", exchange -> respond(exchange, userInfoResponse));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("인가 URL 에 state 가 그대로 실린다")
    void authorizationUrlContainsState() {
        String url = client(baseUrl()).getAuthorizationUrl("state-abc_123");

        assertThat(UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state"))
                .isEqualTo("state-abc_123");
    }

    @Test
    @DisplayName("토큰 교환과 사용자 정보 조회가 성공하면 사용자 정보를 돌려준다")
    void returnsUserInfo_whenGoogleResponds() {
        OAuthUserInfo userInfo = client(baseUrl()).authenticate("valid-code");

        assertThat(userInfo).isEqualTo(new OAuthUserInfo("홍길동", "hong@gmail.com", "google-sub-1"));
    }

    @Test
    @DisplayName("잘못되거나 재사용된 코드로 토큰 교환이 400 이면 OAUTH_AUTHENTICATION_FAILED(401) 로 전환한다")
    void mapsTokenClientError_toAuthenticationFailed() {
        tokenResponse = new StubResponse(400, "{\"error\":\"invalid_grant\"}");

        assertErrorCode(() -> client(baseUrl()).authenticate("reused-code"), ErrorCode.OAUTH_AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("토큰 교환이 5xx 이면 OAUTH_PROVIDER_UNAVAILABLE(502) 로 전환한다")
    void mapsTokenServerError_toProviderUnavailable() {
        tokenResponse = new StubResponse(503, "");

        assertErrorCode(() -> client(baseUrl()).authenticate("valid-code"), ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("토큰 응답 본문이 없으면 OAUTH_PROVIDER_UNAVAILABLE(502) 로 전환한다")
    void mapsEmptyTokenBody_toProviderUnavailable() {
        tokenResponse = new StubResponse(200, "");

        assertErrorCode(() -> client(baseUrl()).authenticate("valid-code"), ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("구글에 연결할 수 없으면 OAUTH_PROVIDER_UNAVAILABLE(502) 로 전환한다")
    void mapsConnectionFailure_toProviderUnavailable() throws IOException {
        // 빈 포트를 하나 받아 곧바로 닫아, 연결이 거부되는 주소를 만든다
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        String unreachable = "http://127.0.0.1:" + closedPort;

        assertErrorCode(() -> client(unreachable).authenticate("valid-code"), ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("사용자 정보 조회가 401 이면 OAUTH_AUTHENTICATION_FAILED(401) 로 전환한다")
    void mapsUserInfoClientError_toAuthenticationFailed() {
        userInfoResponse = new StubResponse(401, "{\"error\":\"invalid_token\"}");

        assertErrorCode(() -> client(baseUrl()).authenticate("valid-code"), ErrorCode.OAUTH_AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("사용자 정보에 이메일이 없으면 OAUTH_PROVIDER_UNAVAILABLE(502) 로 전환한다")
    void mapsMissingEmail_toProviderUnavailable() {
        userInfoResponse = new StubResponse(200, "{\"sub\":\"google-sub-1\",\"name\":\"홍길동\"}");

        assertErrorCode(() -> client(baseUrl()).authenticate("valid-code"), ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
    }

    // ===== Helper =====

    private GoogleOAuthClient client(String baseUrl) {
        return new GoogleOAuthClient(new GoogleOAuthProperties(
                "client-id",
                "client-secret",
                "http://localhost/callback",
                baseUrl + "/auth",
                baseUrl + "/token",
                baseUrl + "/userinfo"
        ));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    private static void respond(HttpExchange exchange, StubResponse response) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        // 본문이 없으면 길이 -1 로 보내 Content-Length: 0 응답을 만든다
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private record StubResponse(int status, String body) {
    }
}
