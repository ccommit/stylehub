package ccommit.stylehub.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * 토스페이먼츠 API 연동에 필요한 설정값을 관리한다.
 * application.yml의 toss.payments 하위 속성을 바인딩한다.
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "toss.payments")
@Getter
@Setter
public class TossPaymentProperties {

    private String secretKey;
    private String confirmUrl;
}
