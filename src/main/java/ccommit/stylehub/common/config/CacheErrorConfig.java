package ccommit.stylehub.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 캐시 저장소(Redis) 오류가 조회 요청의 실패로 번지지 않게, 캐시 조회·저장 실패는 로그만 남기고 원래 메서드(DB 조회)로 진행한다.
 * 대신 Redis 가 죽은 동안에는 캐시가 받던 조회가 모두 DB 로 가므로, DB 커넥션 풀이 그 부하를 감당하는지가 새 한계가 된다.
 * </p>
 */
@Configuration
public class CacheErrorConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheErrorConfig.class);

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("캐시 조회 실패 — DB 조회로 진행: cache={}, key={}, error={}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("캐시 저장 실패 — 응답은 그대로 반환: cache={}, key={}, error={}", cache.getName(), key, exception.getMessage());
            }

            // 무효화 실패를 삼키면 옛 값이 TTL 동안 남지만, 이미 커밋된 변경을 실패 응답으로 바꾸는 것보다 낫다.
            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("캐시 무효화 실패 — TTL 만료 전까지 이전 값이 응답될 수 있음: cache={}, key={}", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("캐시 전체 무효화 실패: cache={}", cache.getName(), exception);
            }
        };
    }
}
