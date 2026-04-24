package ccommit.stylehub.common.config;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
     * - 값: CursorResponse&lt;ProductListResponse&gt; 타입드 JSON 직렬화
     *   (Jackson 3 은 default typing EVERYTHING 모드가 제거되어 final record 에는 타입 정보를 남길 수 없다.
     *   캐시 타입이 단일이므로 고정 JavaType 기반 직렬화기로 안전하게 역직렬화한다.)
     * - 기본 TTL: 30초 (신상품 반영 지연 허용 범위)
     * - null 값은 캐시하지 않음
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        JavaType cursorResponseType = objectMapper.getTypeFactory()
                .constructParametricType(CursorResponse.class, ProductListResponse.class);

        RedisSerializer<Object> valueSerializer = typedJsonSerializer(objectMapper, cursorResponseType);

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

    // 고정 JavaType 기반 RedisSerializer. default typing 없이 정확한 타입으로 역직렬화한다.
    private static RedisSerializer<Object> typedJsonSerializer(ObjectMapper mapper, JavaType type) {
        return new RedisSerializer<>() {
            @Override
            public byte[] serialize(Object value) {
                if (value == null) {
                    return new byte[0];
                }
                try {
                    return mapper.writeValueAsBytes(value);
                } catch (Exception e) {
                    throw new SerializationException("Redis 캐시 JSON 직렬화 실패", e);
                }
            }

            @Override
            public Object deserialize(byte[] bytes) {
                if (bytes == null || bytes.length == 0) {
                    return null;
                }
                try {
                    return mapper.readValue(bytes, type);
                } catch (Exception e) {
                    throw new SerializationException("Redis 캐시 JSON 역직렬화 실패", e);
                }
            }
        };
    }
}
