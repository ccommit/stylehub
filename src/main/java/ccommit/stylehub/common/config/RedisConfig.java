package ccommit.stylehub.common.config;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.service.ProductCacheEvictor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
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
 * @modified 2026/09/17 by WonJin - fix: 캐시 전체 무효화를 KEYS 대신 SCAN 으로 수행, 캐시 이름을 ProductCacheEvictor 상수로 통일
 * @modified 2026/09/19 by WonJin - fix: 캐시 저장을 비동기에서 동기(immediateWrites)로 변경
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

    private static final String LIST_CACHE = ProductCacheEvictor.FIRST_PAGE_CACHE;
    private static final String DETAIL_CACHE = ProductCacheEvictor.DETAIL_CACHE;

    // SCAN 한 번에 훑을 키 개수 힌트(COUNT). 측정으로 정한 값이 아니며, 한 번의 명령이 Redis 를 오래 붙잡지 않게 하려는 크기다.
    private static final int CACHE_CLEAR_SCAN_BATCH_SIZE = 1000;

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    // default typing 없이 캐시별 고정 JavaType으로 직렬화해 record와 안전하게 호환된다.
    // 전체 무효화는 SCAN으로 키를 찾는다. KEYS는 세션·주문 타임아웃 키도 있는 이 Redis를 훑는 동안 막아 다른 요청을 지연시킨다.
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = JsonMapper.builder().build();

        JavaType listType = mapper.getTypeFactory()
                .constructParametricType(CursorResponse.class, ProductListResponse.class);
        JavaType detailType = mapper.constructType(ProductResponse.class);

        RedisCacheConfiguration listConfig = cacheConfig(typedJsonSerializer(mapper, listType));
        RedisCacheConfiguration detailConfig = cacheConfig(typedJsonSerializer(mapper, detailType));

        // 캐시 저장을 응답 전에 끝낸다. 기본값(Lettuce 비동기 저장)은 무효화 뒤에 늦은 SET 이 도착해 옛 값이 TTL 동안 남는 틈을 넓힌다
        RedisCacheWriter cacheWriter = RedisCacheWriter.create(connectionFactory, writer -> writer
                .batchStrategy(BatchStrategies.scan(CACHE_CLEAR_SCAN_BATCH_SIZE))
                .immediateWrites());

        return RedisCacheManager.builder(cacheWriter)
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
