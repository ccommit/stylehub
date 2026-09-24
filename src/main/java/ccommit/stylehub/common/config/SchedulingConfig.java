package ccommit.stylehub.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * @Scheduled 작업을 켠다. 테스트는 stylehub.scheduling.enabled=false 로 꺼서
 * 백그라운드 폴링이 테스트가 직접 호출하는 스케줄러 메서드와 같은 주문을 두고 경합하지 않게 한다.
 * </p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "stylehub.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
