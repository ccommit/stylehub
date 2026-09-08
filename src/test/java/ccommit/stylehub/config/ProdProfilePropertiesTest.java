package ccommit.stylehub.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/08
 *
 * <p>
 * 운영 프로파일 설정 누락 회귀 테스트이다.
 * application.properties 는 로컬 시크릿이 평문으로 들어가 gitignore 되어 있어
 * 저장소를 클론해 빌드하는 CI/배포 산출물에는 포함되지 않는다.
 * 시크릿이 아닌 설정을 그쪽에만 두면 운영에서 통째로 사라지므로,
 * 운영 동작에 필수인 키가 prod 프로파일에 남아 있는지 검증한다.
 * </p>
 */
class ProdProfilePropertiesTest {

    private static final String PROD_PROFILE = "/application-prod.properties";

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
            "server.servlet.session.timeout",
            "spring.session.redis.namespace"
    })
    void sessionSecurityPropertiesArePresent(String key) throws IOException {
        assertThat(loadProdProperties().getProperty(key))
                .as("%s 가 없으면 세션 쿠키 보호 수준이 운영에서 낮아진다", key)
                .isNotBlank();
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
