package ccommit.stylehub.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * 결제 관련 Bean 설정을 담당한다.
 * </p>
 */
@Configuration
public class PaymentConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
