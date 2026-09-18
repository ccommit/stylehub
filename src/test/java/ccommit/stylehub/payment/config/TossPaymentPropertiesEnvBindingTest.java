package ccommit.stylehub.payment.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/19
 *
 * <p>
 * Mock PG 드롭인(traffic-sim/mock-pg/mock-pg.conf)의 환경변수가 운영 설정 파일의 토스 주소를 덮어쓰는지 확인한다.
 * </p>
 */
class TossPaymentPropertiesEnvBindingTest {

    private static final Path DROP_IN = Path.of("traffic-sim/mock-pg/mock-pg.conf");

    @Test
    @DisplayName("드롭인 환경변수가 운영 설정 파일의 토스 주소보다 우선한다")
    void dropInEnvironmentOverridesProdUrls() throws IOException {
        // given
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new SystemEnvironmentPropertySource("systemEnvironment", readDropInEnvironment()));
        sources.addLast(new MapPropertySource("application-prod.properties", Map.of(
                "toss.payments.confirm-url", "https://api.tosspayments.com/v1/payments/confirm",
                "toss.payments.cancel-url", "https://api.tosspayments.com/v1/payments",
                "toss.payments.find-by-order-id-url", "https://api.tosspayments.com/v1/payments/orders")));

        // when
        TossPaymentProperties bound = new Binder(ConfigurationPropertySources.from(sources))
                .bind("toss.payments", TossPaymentProperties.class)
                .get();

        // then
        assertThat(bound.getConfirmUrl()).isEqualTo("http://127.0.0.1:18090/v1/payments/confirm");
        assertThat(bound.getCancelUrl()).isEqualTo("http://127.0.0.1:18090/v1/payments");
        assertThat(bound.getFindByOrderIdUrl()).isEqualTo("http://127.0.0.1:18090/v1/payments/orders");
    }

    // 드롭인의 "Environment=KEY=VALUE" 줄만 읽는다
    private Map<String, Object> readDropInEnvironment() throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        for (String line : Files.readAllLines(DROP_IN)) {
            if (line.startsWith("Environment=")) {
                String pair = line.substring("Environment=".length());
                int separator = pair.indexOf('=');
                env.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
        return env;
    }
}
