package ccommit.stylehub.common.config;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
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
import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/01 by WonJin - docs: Lettuce vs Jedis 비교 및 선택 이유 추가
 * @modified 2026/04/24 by WonJin - feat: Spring Cache 용 RedisCacheManager 추가 (first page 캐싱)
 * @modified 2026/04/24 by WonJin - feat: 캐시 범위 확대 + 상세 조회 캐시 추가 (Step 5 — 1,000/2,000 users 대응)
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

    private static final String LIST_CACHE = "products:firstPage";
    private static final String DETAIL_CACHE = "products:detail";

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Spring Cache 전용 CacheManager.
     * - 키: String (사람이 읽기 쉬운 형태, 예: products:firstPage::size=20|store=null|main=TOP|sub=T_SHIRT)
     * - 값: 캐시별 고정 JavaType JSON 직렬화 (Jackson 3.x, default typing 미사용 — record 와 안전 호환)
     * - 캐시별 설정:
     *     products:firstPage (목록 조회)   → CursorResponse&lt;ProductListResponse&gt;, TTL 60초
     *     products:detail    (단건 상세)   → ProductResponse, TTL 60초
     * - null 값은 캐시하지 않음
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = JsonMapper.builder().build();

        JavaType listType = mapper.getTypeFactory()
                .constructParametricType(CursorResponse.class, ProductListResponse.class);
        JavaType detailType = mapper.constructType(ProductResponse.class);

        RedisCacheConfiguration listConfig = cacheConfig(typedJsonSerializer(mapper, listType));
        RedisCacheConfiguration detailConfig = cacheConfig(typedJsonSerializer(mapper, detailType));

        return RedisCacheManager.builder(connectionFactory)
                .withInitialCacheConfigurations(Map.of(
                        LIST_CACHE, listConfig,
                        DETAIL_CACHE, detailConfig
                ))
                .build();
    }

    private static RedisCacheConfiguration cacheConfig(RedisSerializer<Object> valueSerializer) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(60))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer));
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
