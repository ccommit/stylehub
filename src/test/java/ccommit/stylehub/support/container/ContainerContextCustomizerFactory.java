package ccommit.stylehub.support.container;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;
import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 모든 스프링 테스트 컨텍스트가 공유 MySQL·Redis 컨테이너에 붙도록 접속 정보를 넣는다.
 * META-INF/spring.factories 로 등록해 테스트 클래스를 고치지 않고 적용하며, Mockito 단위 테스트만 돌 때는 컨테이너를 띄우지 않는다.
 * </p>
 */
public class ContainerContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                     List<ContextConfigurationAttributes> configAttributes) {
        return new ContainerContextCustomizer();
    }

    static class ContainerContextCustomizer implements ContextCustomizer {

        private static final String PROPERTY_SOURCE_NAME = "sharedContainers";

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            MySQLContainer mysql = SharedContainers.mysql();

            // 가장 앞에 둬서 환경 변수(예: SPRING_DATA_REDIS_HOST)보다 우선한다
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(
                    "spring.datasource.url", mysql.getJdbcUrl(),
                    "spring.datasource.username", mysql.getUsername(),
                    "spring.datasource.password", mysql.getPassword(),
                    "spring.data.redis.host", SharedContainers.redis().getHost(),
                    "spring.data.redis.port", String.valueOf(SharedContainers.redisPort())
            )));
        }

        // 컨텍스트 캐시 키에 들어가므로, 모든 인스턴스를 같게 봐야 기존 컨텍스트 재사용이 깨지지 않는다
        @Override
        public boolean equals(Object other) {
            return other instanceof ContainerContextCustomizer;
        }

        @Override
        public int hashCode() {
            return ContainerContextCustomizer.class.hashCode();
        }
    }
}
