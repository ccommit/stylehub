package ccommit.stylehub.product.service;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.repository.OrderQueryRepository;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.support.OrderFixtureFactory.Buyer;
import ccommit.stylehub.support.OrderFixtureFactory.StoreProduct;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 * @modified 2026/09/19 by WonJin - test: 테스트 DB 가 H2 에서 MySQL 컨테이너로 바뀐 것에 맞춰 설명 정정
 *
 * <p>
 * 재고 변경 IDOR 차단과 정지 스토어 상품의 노출·주문 차단을 실제 MySQL·트랜잭션으로 검증하는 통합 테스트이다.
 * 소속 검증은 컨트롤러의 경로 변수 바인딩부터 이어져야 의미가 있고, 스토어 승인 조건은 JPQL UPDATE 서브쿼리라 목 객체로는 검증할 수 없다.
 * </p>
 */
@SpringBootTest
class ProductSaleGuardIntegrationTest {

    private static final String STOCK_URL = "/api/v1/stores/me/products/{productId}/options/{optionId}/stock";
    private static final String PRODUCT_CACHE_PATTERN = "products:*";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private UserService userService;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OrderQueryRepository orderQueryRepository;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private MockMvc mockMvc;

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    // 테스트 DB는 컨텍스트가 뜰 때마다 스키마를 새로 만들어 ID가 1부터 다시 시작하지만 Redis 캐시는 공유된다.
    // 이전 실행이 남긴 같은 ID의 상품 캐시가 조회 결과에 섞이지 않도록 시작 전에도 비운다.
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
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
    @DisplayName("스토어 A 가 자기 storeId 로 스토어 B 의 상품·옵션 재고를 바꾸려 하면 404 를 받고 B 의 재고는 그대로다")
    void rejectsStockUpdate_whenOptionBelongsToOtherStore() throws Exception {
        // given
        StoreProduct storeA = track(fixtureFactory.createStoreProduct(10));
        StoreProduct storeB = track(fixtureFactory.createStoreProduct(10));

        // when / then
        mockMvc.perform(patch(STOCK_URL, storeB.productId(), storeB.optionId())
                        .session(storeSession(storeA.storeId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockQuantity\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_OPTION_NOT_FOUND.getCode()));

        assertThat(stockOf(storeB.optionId())).isEqualTo(10);
    }

    @Test
    @DisplayName("스토어 A 가 자기 상품 ID 에 스토어 B 의 옵션 ID 를 섞어 보내도 404 를 받고 B 의 재고는 그대로다")
    void rejectsStockUpdate_whenOwnProductIdIsMixedWithOtherStoresOption() throws Exception {
        // given
        StoreProduct storeA = track(fixtureFactory.createStoreProduct(10));
        StoreProduct storeB = track(fixtureFactory.createStoreProduct(10));

        // when / then
        mockMvc.perform(patch(STOCK_URL, storeA.productId(), storeB.optionId())
                        .session(storeSession(storeA.storeId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockQuantity\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_OPTION_NOT_FOUND.getCode()));

        assertThat(stockOf(storeB.optionId())).isEqualTo(10);
    }

    @Test
    @DisplayName("스토어가 자기 상품의 옵션 재고를 바꾸면 200 과 함께 재고가 반영된다")
    void updatesStock_whenOptionBelongsToOwnProduct() throws Exception {
        // given
        StoreProduct store = track(fixtureFactory.createStoreProduct(10));

        // when / then
        mockMvc.perform(patch(STOCK_URL, store.productId(), store.optionId())
                        .session(storeSession(store.storeId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockQuantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(3));

        assertThat(stockOf(store.optionId())).isEqualTo(3);
    }

    @Test
    @DisplayName("정지된 스토어의 상품은 상품 목록에서 제외되고 승인 스토어의 상품은 그대로 보인다")
    void excludesSuspendedStoreProducts_fromList() {
        // given
        StoreProduct approved = track(fixtureFactory.createStoreProduct(10));
        StoreProduct suspended = track(fixtureFactory.createStoreProduct(10));
        userService.suspendStore(suspended.storeId());

        // when
        CursorResponse<ProductListResponse> suspendedStorePage =
                productApplicationService.getProducts(null, suspended.storeId(), null, null, null);
        CursorResponse<ProductListResponse> approvedStorePage =
                productApplicationService.getProducts(null, approved.storeId(), null, null, null);
        CursorResponse<ProductListResponse> firstPage =
                productApplicationService.getProducts(null, null, null, null, null);

        // then
        assertThat(suspendedStorePage.items()).isEmpty();
        assertThat(approvedStorePage.items())
                .extracting(ProductListResponse::productId)
                .containsExactly(approved.productId());
        assertThat(firstPage.items())
                .extracting(ProductListResponse::productId)
                .contains(approved.productId())
                .doesNotContain(suspended.productId());
    }

    @Test
    @DisplayName("정지된 스토어의 상품 상세는 존재하지 않는 상품과 같이 PRODUCT_NOT_FOUND 로 응답한다")
    void hidesSuspendedStoreProduct_fromDetail() {
        // given
        StoreProduct approved = track(fixtureFactory.createStoreProduct(10));
        StoreProduct suspended = track(fixtureFactory.createStoreProduct(10));
        userService.suspendStore(suspended.storeId());

        // when / then
        assertThatThrownBy(() -> productApplicationService.getProduct(suspended.productId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_NOT_FOUND);
        assertThat(productApplicationService.getProduct(approved.productId()).productId())
                .isEqualTo(approved.productId());
    }

    @Test
    @DisplayName("정지된 스토어의 옵션을 주문하면 PRODUCT_NOT_ON_SALE 로 거절되고 재고와 주문이 남지 않는다")
    void rejectsOrder_whenStoreSuspended() {
        // given
        Buyer buyer = track(fixtureFactory.createBuyer());
        StoreProduct suspended = track(fixtureFactory.createStoreProduct(10));
        userService.suspendStore(suspended.storeId());

        // when / then
        assertThatThrownBy(() -> orderService.placeOrder(buyer.userId(), orderRequest(buyer, suspended.optionId(), 1)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_NOT_ON_SALE);

        assertThat(stockOf(suspended.optionId())).isEqualTo(10);
        assertThat(orderQueryRepository.findMyOrdersWithCursor(buyer.userId(), null, 10)).isEmpty();
    }

    @Test
    @DisplayName("승인된 스토어의 옵션을 주문하면 주문 수량만큼 재고가 차감된다")
    void decreasesStock_whenStoreApproved() {
        // given
        Buyer buyer = track(fixtureFactory.createBuyer());
        StoreProduct approved = track(fixtureFactory.createStoreProduct(10));

        // when
        OrderResponse placed = orderService.placeOrder(buyer.userId(), orderRequest(buyer, approved.optionId(), 3));
        orderIds.add(placed.orderId());

        // then
        assertThat(stockOf(approved.optionId())).isEqualTo(7);
    }

    @Test
    @DisplayName("재고가 부족하면 스토어가 승인 상태여도 INSUFFICIENT_STOCK 으로 구분해 거절한다")
    void rejectsOrder_whenStockInsufficient() {
        // given
        Buyer buyer = track(fixtureFactory.createBuyer());
        StoreProduct approved = track(fixtureFactory.createStoreProduct(2));

        // when / then
        assertThatThrownBy(() -> orderService.placeOrder(buyer.userId(), orderRequest(buyer, approved.optionId(), 3)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_STOCK);
        assertThat(stockOf(approved.optionId())).isEqualTo(2);
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

    private MockHttpSession storeSession(Long storeId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstants.SESSION_USER_ID, storeId);
        session.setAttribute(SessionConstants.SESSION_USER_ROLE, UserRole.STORE);
        return session;
    }

    private OrderCreateRequest orderRequest(Buyer buyer, Long optionId, int quantity) {
        return new OrderCreateRequest(buyer.addressId(), List.of(new OrderDetailRequest(optionId, quantity)), null);
    }

    private int stockOf(Long optionId) {
        return productOptionRepository.findById(optionId).orElseThrow().getStockQuantity();
    }

    private void clearProductCache() {
        Set<String> keys = redisTemplate.keys(PRODUCT_CACHE_PATTERN);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
