package ccommit.stylehub.product.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * ProductCacheEvictor의 커밋 후 무효화, 트랜잭션이 없을 때 즉시 실행, 캐시 장애 격리를 검증하는 단위 테스트이다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductCacheEvictorTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache detailCache;

    @Mock
    private Cache firstPageCache;

    @InjectMocks
    private ProductCacheEvictor productCacheEvictor;

    @BeforeEach
    void setUp() {
        given(cacheManager.getCache(ProductCacheEvictor.DETAIL_CACHE)).willReturn(detailCache);
        given(cacheManager.getCache(ProductCacheEvictor.FIRST_PAGE_CACHE)).willReturn(firstPageCache);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션이 없으면 상세 캐시를 즉시 지운다")
    void evictsImmediately_whenNoTransaction() {
        // when
        productCacheEvictor.evictDetailAfterCommit(1L);

        // then
        then(detailCache).should().evictIfPresent(1L);
    }

    @Test
    @DisplayName("트랜잭션 안에서는 커밋 전까지 지우지 않고, 커밋 후에 지운다")
    void evictsAfterCommit_whenInTransaction() {
        // given
        TransactionSynchronizationManager.initSynchronization();

        // when
        productCacheEvictor.evictDetailAfterCommit(1L);

        // then — 커밋 전
        then(detailCache).should(never()).evictIfPresent(any());

        // when — 커밋
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        // then
        then(detailCache).should().evictIfPresent(1L);
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 캐시를 지우지 않는다")
    void doesNotEvict_whenRolledBack() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        productCacheEvictor.clearFirstPageAfterCommit();

        // when — 롤백은 afterCommit 없이 afterCompletion(STATUS_ROLLED_BACK) 만 호출된다
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        // then
        then(firstPageCache).should(never()).invalidate();
    }

    @Test
    @DisplayName("첫 페이지 캐시 무효화는 커밋 후 캐시 전체를 지운다")
    void invalidatesFirstPageAfterCommit() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        productCacheEvictor.clearFirstPageAfterCommit();

        // when
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        // then
        then(firstPageCache).should().invalidate();
    }

    @Test
    @DisplayName("Redis 장애로 무효화가 실패해도 예외를 던지지 않아 이미 커밋된 요청을 실패로 만들지 않는다")
    void swallowsCacheFailure() {
        // given
        given(detailCache.evictIfPresent(1L)).willThrow(new RedisConnectionFailureException("redis down"));
        TransactionSynchronizationManager.initSynchronization();
        productCacheEvictor.evictDetailAfterCommit(1L);

        // when / then
        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit))
                .doesNotThrowAnyException();
    }
}
