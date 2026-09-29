package ccommit.stylehub.product.service;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.support.OrderFixtureFactory.StoreProduct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 캐시 저장소가 응답하지 않아도 상품 목록·상세 조회가 DB 로 응답하는지 검증한다.
 * Redis 컨테이너를 멈추면 같은 컨테이너를 쓰는 다른 테스트가 깨지므로, 모든 캐시 연산이 연결 실패를 던지는 캐시로 바꿔 재현한다.
 * </p>
 */
@SpringBootTest
class ProductCacheFailureTest {

    @MockitoBean
    private CacheManager cacheManager;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    private StoreProduct storeProduct;

    @BeforeEach
    void setUp() {
        Cache brokenCache = mock(Cache.class);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("Redis 연결 실패");
        given(brokenCache.getName()).willReturn("broken");
        willThrow(failure).given(brokenCache).get(any());
        willThrow(failure).given(brokenCache).get(any(), any(Callable.class));
        willThrow(failure).given(brokenCache).put(any(), any());
        willThrow(failure).given(brokenCache).evictIfPresent(any());
        given(cacheManager.getCache(anyString())).willReturn(brokenCache);

        storeProduct = fixtureFactory.createStoreProduct(10);
    }

    @AfterEach
    void cleanUp() {
        fixtureFactory.deleteByIds(List.of(), List.of(storeProduct.productId()), List.of(storeProduct.storeId()));
    }

    @Test
    @DisplayName("캐시 조회·저장이 실패해도 상품 상세는 DB 에서 읽어 응답한다")
    void detailFallsBackToDatabase() {
        ProductResponse response = productApplicationService.getProduct(storeProduct.productId());

        assertThat(response.productId()).isEqualTo(storeProduct.productId());
    }

    @Test
    @DisplayName("캐시 조회·저장이 실패해도 상품 목록 첫 페이지는 DB 에서 읽어 응답한다")
    void firstPageFallsBackToDatabase() {
        CursorResponse<ProductListResponse> page = productApplicationService.getProducts(null, null, null, null, null);

        assertThat(page.items()).isNotEmpty();
    }
}
