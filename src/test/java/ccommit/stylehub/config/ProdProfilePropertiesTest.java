package ccommit.stylehub.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/08
 * @modified 2026/09/17 by WonJin - test: Redis 명령 타임아웃 설정 누락 검증 추가 (선착순 쿠폰 fail-closed 시 스레드 고갈 방지)
 * @modified 2026/09/17 by WonJin - test: Boot 4 세션 키(spring.session.data.redis.namespace, cookie.secure, spring.session.timeout) 존재와, 세션 키가 Boot 설정 메타데이터에 실제로 선언된(바인딩되는) 이름인지 검증 추가
 *
 * <p>
 * application.properties는 gitignore되어 CI·배포 산출물에 없으므로, 운영 필수 키가 prod 프로파일에 남아 있는지 검증한다.
 * 키 이름이 틀리면 Boot가 조용히 무시하므로 세션 키는 클래스패스의 설정 메타데이터와 대조한다.
 * </p>
 */
class ProdProfilePropertiesTest {

    private static final String PROD_PROFILE = "/application-prod.properties";
    private static final String BOOT_METADATA = "META-INF/spring-configuration-metadata.json";
    private static final String DEPRECATION_ERROR = "error";

    // 클래스패스의 모든 설정 메타데이터를 읽어 프로퍼티별 deprecation level을 담는다. 폐기되지 않은 키는 빈 문자열이다.
    private Map<String, String> loadBootConfigurationMetadata() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        Map<String, String> properties = new HashMap<>();
        Enumeration<URL> resources = getClass().getClassLoader().getResources(BOOT_METADATA);
        while (resources.hasMoreElements()) {
            try (InputStream in = resources.nextElement().openStream()) {
                for (JsonNode property : mapper.readTree(in).path("properties")) {
                    String level = property.path("deprecation").path("level").asString("");
                    // 여러 모듈에 같은 이름이 있으면 하나라도 폐기되지 않은 선언이 있을 때 바인딩되는 것으로 본다
                    properties.merge(property.path("name").asString(), level,
                            (existing, added) -> existing.isEmpty() || added.isEmpty() ? "" : existing);
                }
            }
        }
        assertThat(properties).as("Boot 설정 메타데이터를 찾지 못했다").isNotEmpty();
        return properties;
    }

    private Properties loadProdProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream(PROD_PROFILE)) {
            assertThat(in).as("application-prod.properties 가 클래스패스에 존재해야 한다").isNotNull();
            properties.load(in);
        }
        return properties;
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("외부 연동 엔드포인트는 시크릿이 아니므로 운영 프로파일에 존재해야 한다")
    @ValueSource(strings = {
            "toss.payments.confirm-url",
            "toss.payments.cancel-url",
            "toss.payments.find-by-order-id-url",
            "google.auth-url",
            "google.token-url",
            "google.userinfo-url"
    })
    void externalEndpointsArePresent(String key) throws IOException {
        assertThat(loadProdProperties().getProperty(key))
                .as("%s 가 없으면 결제 또는 소셜 로그인 호출 대상이 사라진다", key)
                .isNotBlank();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("부하 테스트로 조정한 커넥션 풀·스레드 설정이 운영 프로파일에 존재해야 한다")
    @ValueSource(strings = {
            "spring.datasource.hikari.maximum-pool-size",
            "server.tomcat.threads.max",
            "server.tomcat.threads.min-spare",
            "server.tomcat.accept-count"
    })
    void tuningPropertiesArePresent(String key) throws IOException {
        assertThat(loadProdProperties().getProperty(key))
                .as("%s 가 없으면 프레임워크 기본값으로 동작해 측정 결과가 반영되지 않는다", key)
                .isNotBlank();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("세션 보안 설정이 운영 프로파일에 존재해야 한다")
    @ValueSource(strings = {
            "server.servlet.session.cookie.http-only",
            "server.servlet.session.cookie.same-site",
            "server.servlet.session.cookie.secure",
            "server.servlet.session.timeout",
            "spring.session.data.redis.namespace",
            "spring.session.timeout"
    })
    void sessionSecurityPropertiesArePresent(String key) throws IOException {
        assertThat(loadProdProperties().getProperty(key))
                .as("%s 가 없으면 세션 쿠키 보호 수준이 운영에서 낮아진다", key)
                .isNotBlank();
    }

    @Test
    @DisplayName("Redis 명령 타임아웃이 운영 프로파일에 존재해야 한다")
    void redisCommandTimeoutIsPresent() throws IOException {
        assertThat(loadProdProperties().getProperty("spring.data.redis.timeout"))
                .as("없으면 Redis 장애 시 요청이 Lettuce 기본 타임아웃(60초)까지 톰캣 스레드를 붙잡는다")
                .isNotBlank();
    }

    @Test
    @DisplayName("세션 키는 Boot 설정 메타데이터에 선언된 이름이어야 한다 (Boot 4 에서 바뀐 옛 키는 바인딩되지 않는다)")
    void sessionKeysAreBoundBySpringBoot() throws IOException {
        Map<String, String> bootProperties = loadBootConfigurationMetadata();
        List<String> sessionKeys = loadProdProperties().stringPropertyNames().stream()
                .filter(key -> key.startsWith("server.servlet.session.") || key.startsWith("spring.session."))
                .sorted()
                .toList();

        assertThat(sessionKeys).isNotEmpty();
        assertThat(sessionKeys)
                .as("메타데이터에 없는 키는 오타이거나 다른 버전의 키라 어디에도 바인딩되지 않는다")
                .allSatisfy(key -> assertThat(bootProperties).containsKey(key));
        assertThat(sessionKeys)
                .as("deprecation level=error 인 키는 이름만 남아 있고 값이 적용되지 않는다(예: spring.session.redis.namespace)")
                .allSatisfy(key -> assertThat(bootProperties.get(key)).isNotEqualTo(DEPRECATION_ERROR));
    }

    @Test
    @DisplayName("Secure 쿠키는 HTTPS 종단 여부에 따라 환경변수로 켜며, 값이 없으면 꺼진 상태로 뜬다")
    void secureCookieIsToggledByEnvironment() throws IOException {
        assertThat(loadProdProperties().getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo("${SESSION_COOKIE_SECURE:false}");
    }

    @Test
    @DisplayName("시크릿은 평문이 아니라 환경변수 자리표시자로만 선언되어 있다")
    void secretsAreInjectedFromEnvironment() throws IOException {
        Properties properties = loadProdProperties();

        assertThat(properties.getProperty("spring.datasource.password")).startsWith("${");
        assertThat(properties.getProperty("google.client-id")).startsWith("${");
        assertThat(properties.getProperty("google.client-secret")).startsWith("${");
        assertThat(properties.getProperty("toss.payments.secret-key")).startsWith("${");
    }
}
