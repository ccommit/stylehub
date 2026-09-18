package ccommit.stylehub.product.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 상품 조회 캐시(상세·첫 페이지)의 이름과 무효화를 담당한다.
 * 무효화는 트랜잭션 커밋 후에만 실행해, 롤백된 변경 때문에 캐시를 비우거나 커밋 전 값으로 캐시가 다시 채워지는 일을 막는다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class ProductCacheEvictor {

    public static final String FIRST_PAGE_CACHE = "products:firstPage";
    public static final String DETAIL_CACHE = "products:detail";

    private static final Logger log = LoggerFactory.getLogger(ProductCacheEvictor.class);

    private final CacheManager cacheManager;

    // 커밋 후 상품 상세 캐시 한 건을 지운다.
    public void evictDetailAfterCommit(Long productId) {
        runAfterCommitOrNow(DETAIL_CACHE, productId, cache -> cache.evictIfPresent(productId));
    }

    // 새 상품이 들어갈 필터 조합을 키 단위로 계산하기보다 전체를 비운다. 등록은 드물고 첫 페이지 키 수는 제한돼 있다.
    // 키 탐색은 RedisConfig에서 KEYS 대신 SCAN으로 설정해 전체 무효화가 Redis를 한 번에 오래 막지 않는다.
    public void clearFirstPageAfterCommit() {
        runAfterCommitOrNow(FIRST_PAGE_CACHE, "*", Cache::invalidate);
    }

    // afterCommit 예외는 이미 커밋된 요청을 실패 응답으로 바꿔 재시도를 부르므로, 무효화 실패는 로그만 남기고 TTL로 수렴시킨다.
    private void runAfterCommitOrNow(String cacheName, Object key, Consumer<Cache> eviction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictQuietly(cacheName, key, eviction);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictQuietly(cacheName, key, eviction);
            }
        });
    }

    private void evictQuietly(String cacheName, Object key, Consumer<Cache> eviction) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                eviction.accept(cache);
            }
        } catch (RuntimeException e) {
            log.warn("상품 캐시 무효화 실패 — TTL 만료 전까지 이전 값이 응답될 수 있음: cache={}, key={}", cacheName, key, e);
        }
    }
}
