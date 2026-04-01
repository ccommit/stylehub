package ccommit.stylehub.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/01 by WonJin - docs: Lettuce vs Jedis 비교 및 선택 이유 추가
 *
 * Redis 설정을 담당한다.
 * 분산 락과 캐싱에 StringRedisTemplate을 사용한다.
 *
 * Redis 구현체 비교:
 *
 * Lettuce (현재 사용)
 *   - 장점: 비동기/논블로킹, Netty 기반으로 하나의 커넥션을 멀티스레드에서 공유 가능, 커넥션 풀 없이도 높은 동시성 처리, Spring Boot 기본 내장
 *   - 단점: 디버깅이 상대적으로 어려움
 *
 * Jedis
 *   - 장점: 동기 방식으로 직관적, 오래된 라이브러리라 레퍼런스 풍부
 *   - 단점: 스레드별 커넥션 필요(커넥션 풀 필수), 동시 요청이 많으면 풀 고갈 위험, 멀티스레드 환경에서 thread-safe하지 않음
 *
 * 대용량 트래픽 환경에서 커넥션 효율이 중요하므로 Lettuce를 사용한다.
 * spring-boot-starter-data-redis가 기본으로 Lettuce를 내장하고 있어 별도 설정 없이 적용된다.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
