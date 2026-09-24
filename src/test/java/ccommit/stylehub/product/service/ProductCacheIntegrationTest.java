package ccommit.stylehub.product.service;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.product.dto.request.ProductCreateRequest;
import ccommit.stylehub.product.dto.request.ProductOptionRequest;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.support.OrderFixtureFactory.Buyer;
import ccommit.stylehub.support.OrderFixtureFactory.StoreProduct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 * @modified 2026/09/19 by WonJin - test: 테스트 DB 가 H2 에서 MySQL 컨테이너로 바뀐 것에 맞춰 설명 정정
 *
 * <p>
 * 상품 조회 캐시의 무효화 시점, 키 정규화, 적중 시 커넥션 미사용을 실제 Redis·MySQL로 검증하는 통합 테스트이다.
 * 프록시·트랜잭션이 실제로 돌아야 드러나는 동작이라 목 객체 대신 Redis 키와 getConnection 호출 수를 직접 확인한다.
 * </p>
 */
@SpringBootTest
class ProductCacheIntegrationTest {

    private static final String PRODUCT_CACHE_PATTERN = "products:*";
    private static final String FIRST_PAGE_KEY_PATTERN = "products:firstPage::*";
    private static final String DEFAULT_FIRST_PAGE_KEY = "products:firstPage::size=20|store=*|main=*|sub=*";

    // DataSource를 감싸 getConnection 호출을 세되, 스케줄러 등 다른 스레드가 섞이지 않게 카운트를 시작한 스레드의 호출만 센다.
    // 이 설정 때문에 이 클래스는 다른 통합 테스트와 별도의 스프링 컨텍스트를 쓴다.
    @TestConfiguration
    static class ConnectionCountingConfig {

        @Bean
        static BeanPostProcessor connectionCountingDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource && !(bean instanceof ConnectionCountingDataSource)) {
                        return new ConnectionCountingDataSource(dataSource);
                    }
                    return bean;
                }
            };
        }
    }

    static final class ConnectionCountingDataSource extends DelegatingDataSource {

        private final AtomicInteger count = new AtomicInteger();
        private volatile Thread countingThread;

        ConnectionCountingDataSource(DataSource target) {
            super(target);
        }

        void startCounting() {
            count.set(0);
            countingThread = Thread.currentThread();
        }

        int stopCounting() {
            countingThread = null;
            return count.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            record();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            record();
            return super.getConnection(username, password);
        }

        private void record() {
            if (Thread.currentThread() == countingThread) {
                count.incrementAndGet();
            }
        }
    }

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WebApplicationContext context;

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    // 테스트 DB는 컨텍스트가 뜰 때마다 스키마를 새로 만들어 ID가 1부터 다시 시작하지만 Redis 캐시는 공유된다.
    // 이전 실행이 남긴 같은 ID의 상품 캐시가 키 검증에 섞이지 않도록 시작 전에도 비운다.
    @BeforeEach
    void setUp() {
        clearProductCache();
    }

    @AfterEach
    void cleanUp() {
        clearProductCache();
        if (!orderIds.isEmpty()) {
            redisTemplate.opsForZSet().remove(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY,
                    orderIds.stream().map(String::valueOf).toArray());
        }
        fixtureFactory.deleteByIds(orderIds, productIds, userIds);
        orderIds.clear();
        productIds.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("스토어가 재고를 수동으로 바꾸면 상세 캐시가 무효화되어 다음 조회에 새 재고가 보인다")
    void evictsDetailCache_afterStockUpdate() {
        // given — 재고 10 인 상세가 캐시된 상태
        StoreProduct sp = track(fixtureFactory.createStoreProduct(10));
        productApplicationService.getProduct(sp.productId());
        assertThat(redisTemplate.hasKey(detailKey(sp.productId()))).isTrue();

        // when
        productApplicationService.updateStock(sp.storeId(), sp.storeId(), sp.productId(), sp.optionId(), 3);

        // then
        assertThat(redisTemplate.hasKey(detailKey(sp.productId()))).isFalse();
        assertThat(stockInDetail(sp.productId())).isEqualTo(3);
    }

    @Test
    @DisplayName("주문으로 재고가 남아 있으면 상세 캐시를 유지하고, 재고가 0 이 되는 순간에만 무효화한다")
    void evictsDetailCache_onlyWhenOrderSellsOut() {
        // given — 재고 2 인 상세가 캐시된 상태
        Buyer buyer = track(fixtureFactory.createBuyer());
        StoreProduct sp = track(fixtureFactory.createStoreProduct(2));
        productApplicationService.getProduct(sp.productId());

        // when — 1개 주문: 재고 1, 판매 가능 여부는 그대로
        placeOrder(buyer, sp.optionId(), 1);

        // then — 캐시 유지 (인기 상품 캐시 적중률을 위해 판매 가능 여부가 바뀔 때만 무효화)
        assertThat(redisTemplate.hasKey(detailKey(sp.productId()))).isTrue();

        // when — 1개 더 주문: 재고 0
        placeOrder(buyer, sp.optionId(), 1);

        // then — 품절로 바뀌었으므로 무효화, 재조회 시 재고 0
        assertThat(redisTemplate.hasKey(detailKey(sp.productId()))).isFalse();
        assertThat(stockInDetail(sp.productId())).isZero();
    }

    @Test
    @DisplayName("미결제 주문 취소로 재고가 0 에서 양수로 복구되면 상세 캐시를 무효화한다")
    void evictsDetailCache_whenCancelRestoresSoldOutStock() {
        // given — 재고 1 을 주문해 품절시키고, 품절 상태의 상세가 캐시된 상태
        Buyer buyer = track(fixtureFactory.createBuyer());
        StoreProduct sp = track(fixtureFactory.createStoreProduct(1));
        Long orderId = placeOrder(buyer, sp.optionId(), 1);
        assertThat(stockInDetail(sp.productId())).isZero();
        assertThat(redisTemplate.hasKey(detailKey(sp.productId()))).isTrue();

        // when
        assertThat(orderService.cancelUnpaidOrder(orderId)).isTrue();

        // then
        assertThat(redisTemplate.hasKey(detailKey(sp.productId()))).isFalse();
        assertThat(stockInDetail(sp.productId())).isEqualTo(1);
    }

    @Test
    @DisplayName("재고를 0 으로 만든 차감이 같은 주문의 다른 항목 실패로 롤백되면 상세 캐시를 무효화하지 않는다")
    void keepsDetailCache_whenOrderRollsBack() {
        // given — 옵션 A(재고 1, 상세 캐시됨), 옵션 B(재고 0). 옵션 ID 오름차순으로 차감하므로 A 가 먼저 0 이 된다.
        Buyer buyer = track(fixtureFactory.createBuyer());
        StoreProduct soldOutOnOrder = track(fixtureFactory.createStoreProduct(1));
        StoreProduct alreadySoldOut = track(fixtureFactory.createStoreProduct(0));
        productApplicationService.getProduct(soldOutOnOrder.productId());

        OrderCreateRequest request = new OrderCreateRequest(buyer.addressId(), List.of(
                new OrderDetailRequest(soldOutOnOrder.optionId(), 1),
                new OrderDetailRequest(alreadySoldOut.optionId(), 1)
        ), null);

        // when
        assertThatThrownBy(() -> orderService.placeOrder(buyer.userId(), request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_STOCK);

        // then — 커밋되지 않았으므로 무효화도 일어나지 않고, 캐시된 재고 1 이 DB 와 계속 일치한다
        assertThat(redisTemplate.hasKey(detailKey(soldOutOnOrder.productId()))).isTrue();
        assertThat(productOptionRepository.findById(soldOutOnOrder.optionId()).orElseThrow().getStockQuantity())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("상품을 등록하면 커밋 후 첫 페이지 캐시가 무효화되어 새 상품이 바로 목록에 보인다")
    void clearsFirstPageCache_afterProductRegistered() {
        // given — 기본 첫 페이지가 캐시된 상태
        StoreProduct sp = track(fixtureFactory.createStoreProduct(10));
        productApplicationService.getProducts(null, null, null, null, null);
        assertThat(redisTemplate.hasKey(DEFAULT_FIRST_PAGE_KEY)).isTrue();

        // when
        ProductResponse registered = productApplicationService.registerProduct(sp.storeId(), sp.storeId(),
                new ProductCreateRequest("신상품", MainCategory.TOP, SubCategory.T_SHIRT, "설명", 20000,
                        "https://img/new", List.of(new ProductOptionRequest("white", "L", 5, 0))));
        productIds.add(registered.productId());

        // then
        assertThat(redisTemplate.hasKey(DEFAULT_FIRST_PAGE_KEY)).isFalse();
        assertThat(productApplicationService.getProducts(null, null, null, null, null).items())
                .extracting(ProductListResponse::productId)
                .first()
                .isEqualTo(registered.productId());
    }

    @Test
    @DisplayName("pageSize 가 기본값이 아니면(999) 캐시 키를 만들지 않고, 생략·20 은 같은 키 하나를 공유한다")
    void cachesOnlyDefaultPageSize_withNormalizedKey() {
        // given
        track(fixtureFactory.createStoreProduct(10));

        // when — 정규화 후 100 이 되는 요청
        productApplicationService.getProducts(null, null, null, null, 999);

        // then
        assertThat(redisTemplate.keys(FIRST_PAGE_KEY_PATTERN)).isEmpty();

        // when — 생략과 20 은 정규화 후 같은 값
        productApplicationService.getProducts(null, null, null, null, null);
        productApplicationService.getProducts(null, null, null, null, 20);

        // then
        assertThat(redisTemplate.keys(FIRST_PAGE_KEY_PATTERN)).containsExactly(DEFAULT_FIRST_PAGE_KEY);
    }

    @Test
    @DisplayName("결과가 비어 있으면(존재하지 않는 storeId) 첫 페이지 캐시 키를 만들지 않는다")
    void doesNotCacheEmptyFirstPage() {
        // when
        CursorResponse<ProductListResponse> page =
                productApplicationService.getProducts(null, Long.MAX_VALUE, null, null, null);

        // then
        assertThat(page.items()).isEmpty();
        assertThat(redisTemplate.keys(FIRST_PAGE_KEY_PATTERN)).isEmpty();
    }

    @Test
    @DisplayName("상세·첫 페이지 캐시 적중 요청은 DB 커넥션을 가져오지 않는다")
    void doesNotAcquireConnection_onCacheHit() {
        // given
        ConnectionCountingDataSource countingDataSource = (ConnectionCountingDataSource) dataSource;
        StoreProduct sp = track(fixtureFactory.createStoreProduct(10));

        // when — 첫 호출은 캐시 미스 (카운터가 실제로 커넥션 획득을 잡는지도 함께 확인)
        countingDataSource.startCounting();
        productApplicationService.getProduct(sp.productId());
        productApplicationService.getProducts(null, null, null, null, null);
        int connectionsOnMiss = countingDataSource.stopCounting();

        // when — 같은 요청을 다시 보내 캐시 적중
        countingDataSource.startCounting();
        ProductResponse detail = productApplicationService.getProduct(sp.productId());
        CursorResponse<ProductListResponse> firstPage = productApplicationService.getProducts(null, null, null, null, null);
        int connectionsOnHit = countingDataSource.stopCounting();

        // then
        assertThat(connectionsOnMiss).isPositive();
        assertThat(connectionsOnHit).isZero();
        assertThat(detail.productId()).isEqualTo(sp.productId());
        assertThat(firstPage.items()).extracting(ProductListResponse::productId).contains(sp.productId());
    }

    @Test
    @DisplayName("API 경로(OSIV 인터셉터 포함)로 들어온 캐시 적중 요청도 DB 커넥션을 가져오지 않는다")
    void doesNotAcquireConnection_onCacheHitThroughApi() throws Exception {
        // given — MockMvc 는 open-in-view 인터셉터를 포함한 실제 MVC 설정으로 같은 스레드에서 요청을 처리한다
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        ConnectionCountingDataSource countingDataSource = (ConnectionCountingDataSource) dataSource;
        StoreProduct sp = track(fixtureFactory.createStoreProduct(10));
        mockMvc.perform(get("/api/v1/products/{productId}", sp.productId())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());

        // when
        countingDataSource.startCounting();
        mockMvc.perform(get("/api/v1/products/{productId}", sp.productId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(sp.productId()));
        mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());
        int connectionsOnHit = countingDataSource.stopCounting();

        // then
        assertThat(connectionsOnHit).isZero();
    }

    private StoreProduct track(StoreProduct storeProduct) {
        productIds.add(storeProduct.productId());
        userIds.add(storeProduct.storeId());
        return storeProduct;
    }

    private Buyer track(Buyer buyer) {
        userIds.add(buyer.userId());
        return buyer;
    }

    private Long placeOrder(Buyer buyer, Long optionId, int quantity) {
        OrderResponse placed = orderService.placeOrder(buyer.userId(), new OrderCreateRequest(
                buyer.addressId(), List.of(new OrderDetailRequest(optionId, quantity)), null));
        orderIds.add(placed.orderId());
        return placed.orderId();
    }

    private int stockInDetail(Long productId) {
        return productApplicationService.getProduct(productId).options().get(0).stockQuantity();
    }

    private static String detailKey(Long productId) {
        return "products:detail::" + productId;
    }

    private void clearProductCache() {
        Set<String> keys = redisTemplate.keys(PRODUCT_CACHE_PATTERN);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
