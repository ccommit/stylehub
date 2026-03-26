package ccommit.stylehub.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * Redis 설정을 담당한다.
 * 분산 락과 캐싱에 StringRedisTemplate을 사용한다.
 * </p>
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
