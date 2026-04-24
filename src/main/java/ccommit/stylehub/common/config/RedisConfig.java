package ccommit.stylehub.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.time.Duration;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/01 by WonJin - docs: Lettuce vs Jedis 비교 및 선택 이유 추가
 * @modified 2026/04/24 by WonJin - feat: Spring Cache 용 RedisCacheManager 추가 (first page 캐싱)
 *
 * Redis 설정을 담당한다. 분산 락/타임아웃에는 StringRedisTemplate, 응답 캐싱에는 RedisCacheManager 를 사용한다.
 *
 * Redis 구현체 비교
 *   - Lettuce (현재 사용): 비동기/논블로킹, Netty 기반 커넥션 공유, 대용량 트래픽 친화
 *   - Jedis: 동기, 스레드별 커넥션 필요, 멀티스레드 환경에서 비효율
 *   대용량 트래픽 환경에서 커넥션 효율이 중요하므로 Lettuce 를 사용한다.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Spring Cache 전용 CacheManager.
     * - 키: String (사람이 읽기 쉬운 형태, 예: products:firstPage::20)
     * - 값: JSON 직렬화 (GenericJacksonJsonRedisSerializer, Jackson 3.x)
     *   타입 정보를 인라인 `@class` 프로퍼티(AsProperty)로 기록해 record/제네릭도 안전하게 역직렬화
     *   (WrapperArray 방식은 final record 루트에서 타입 래퍼가 생략돼 역직렬화가 깨짐)
     * - Spring Cache null marker 지원 활성화 (NullValue 직렬화)
     * - 기본 TTL: 30초 (신상품 반영 지연 허용 범위)
     * - null 값은 캐시하지 않음 (의미 없음)
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();

        ObjectMapper objectMapper = JsonMapper.builder()
                .activateDefaultTypingAsProperty(typeValidator, DefaultTyping.NON_FINAL, "@class")
                .build();

        GenericJacksonJsonRedisSerializer valueSerializer =
                new GenericJacksonJsonRedisSerializer(objectMapper);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(30))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
