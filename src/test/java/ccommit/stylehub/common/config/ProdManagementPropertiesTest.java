package ccommit.stylehub.common.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 운영 프로파일의 actuator 가 서버 내부 포트에만 열리고, 대시보드에 필요한 지표 설정이 빠지지 않았는지 확인한다.
 * </p>
 */
class ProdManagementPropertiesTest {

    private static Properties prod;

    @BeforeAll
    static void loadProdProperties() throws IOException {
        prod = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"));
    }

    @Test
    @DisplayName("actuator 는 127.0.0.1:9081 에서만 열린다")
    void actuatorBindsToLoopbackOnly() {
        // when & then
        assertThat(prod.getProperty("management.server.port")).isEqualTo("9081");
        assertThat(prod.getProperty("management.server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("p95 계산용 히스토그램과 Tomcat 스레드 지표가 켜져 있다")
    void metricsForDashboardsAreEnabled() {
        // when & then
        assertThat(prod.getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests"))
                .isEqualTo("true");
        assertThat(prod.getProperty("server.tomcat.mbeanregistry.enabled")).isEqualTo("true");
    }
}
