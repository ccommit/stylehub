package ccommit.stylehub.order.service;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.payment.client.PaymentClient;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
import ccommit.stylehub.payment.enums.PaymentStatus;
import ccommit.stylehub.payment.service.PaymentService;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.dto.response.PointHistoryResponse;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.PointType;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import ccommit.stylehub.user.service.PointService;
import ccommit.stylehub.user.service.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 주문 포인트 사용·복구가 실제 트랜잭션과 DB 에서 잔액·이력·결제 금액과 어긋나지 않는지 검증하는 통합 테스트이다.
 * 동시 차감, 취소 복구의 1회성, 먼저 로딩된 엔티티로 인한 덮어쓰기, 결제 승인 금액 검증은 목으로 재현할 수 없어 H2 위에서 확인한다.
 * </p>
 *
 * <p>PG 통신만 PaymentClientFactory 목으로 차단한다. 커밋되는 데이터는 @AfterEach 에서 ID 로 범위를 한정해 지운다.
 * 이 테스트는 H2 에서 실행되므로 MySQL(InnoDB)의 외래키 공유 락·교착 동작은 검증하지 않는다.
 */
@SpringBootTest
class OrderPointIntegrationTest {

    private static final int STOCK = 20;
    // OrderFixtureFactory 가 만드는 상품 가격
    private static final int PRICE = 10_000;
    private static final int WELCOME_POINT = 1_000;
    private static final String CANCEL_REASON = "단순 변심";

    @MockitoBean
    private PaymentClientFactory paymentClientFactory;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private UserService userService;

    @Autowired
    private PointService pointService;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private PaymentClient paymentClient;

    private final List<Long> orderIds = new CopyOnWriteArrayList<>();
    private final List<Long> productIds = new CopyOnWriteArrayList<>();
    private final List<Long> userIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        paymentClient = mock(PaymentClient.class);
        given(paymentClientFactory.getClient(any())).willReturn(paymentClient);
    }

    // 포인트 이력이 주문·회원을 참조하므로 OrderFixtureFactory 가 이력부터 외래키 순서대로 지운다.
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY);
        fixtureFactory.deleteByIds(orderIds, productIds, userIds);
        orderIds.clear();
        productIds.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("같은 사용자가 잔액 합계를 넘는 포인트 주문을 동시에 보내면 잔액만큼만 성공하고 잔액은 음수가 되지 않는다")
    void concurrentOrdersNeverOverdrawPoint() throws InterruptedException {
        // given — 잔액 1000P, 300P 씩 쓰는 주문 10건(합계 3000P)
        OrderFixtureFactory.Fixture fx = newFixture();
        rewardWelcome(fx.userId());
        int threads = 10;
        int usePerOrder = 300;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> failures = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        // when
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    placeWithPoint(fx, usePerOrder);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    failures.computeIfAbsent(e.getErrorCode(), code -> new AtomicInteger()).incrementAndGet();
                } catch (Throwable e) {
                    unexpected.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // then — 1000 / 300 = 3건만 성공, 나머지는 잔액 부족. 실패한 주문의 재고 차감은 함께 롤백된다.
        assertThat(unexpected).isEmpty();
        assertThat(success.get()).isEqualTo(WELCOME_POINT / usePerOrder);
        assertThat(failures.get(ErrorCode.INSUFFICIENT_POINT)).hasValue(threads - success.get());
        assertThat(balanceOf(fx.userId())).isEqualTo(WELCOME_POINT - success.get() * usePerOrder).isNotNegative();
        assertThat(stockOf(fx.optionId())).isEqualTo(STOCK - success.get());

        // 잔여 포인트가 차감 순서대로 기록된다(동시 차감에서 같은 잔액을 두 번 기록하지 않는다).
        assertThat(historiesOf(fx.userId()))
                .filteredOn(history -> history.pointType() == PointType.USE)
                .extracting(PointHistoryResponse::amount, PointHistoryResponse::balanceSnapshot)
                .containsExactly(tuple(-300, 100), tuple(-300, 400), tuple(-300, 700));
    }

    @Test
    @DisplayName("결제 실패 콜백으로 결제 대기 주문이 취소되면 사용 포인트가 한 번 복구되고, 이후 만료 처리가 다시 호출돼도 중복 복구되지 않는다")
    void restoresOnce_whenUnpaidOrderCancelled() {
        // given — 1000P 중 300P 사용 주문
        OrderFixtureFactory.Fixture fx = newFixture();
        rewardWelcome(fx.userId());
        OrderResponse placed = placeWithPoint(fx, 300);
        assertThat(balanceOf(fx.userId())).isEqualTo(700);

        // when — 결제 실패 콜백(이벤트 → cancelUnpaidOrder) 뒤 만료 처리와 같은 cancelUnpaidOrder 재호출
        paymentService.handlePaymentFailure(placed.pgOrderId());
        boolean cancelledAgain = orderService.cancelUnpaidOrder(placed.orderId());

        // then
        assertThat(cancelledAgain).isFalse();
        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(balanceOf(fx.userId())).isEqualTo(WELCOME_POINT);
        assertThat(stockOf(fx.optionId())).isEqualTo(STOCK);
        assertThat(historiesOfOrder(fx.userId(), placed.orderId()))
                .extracting(PointHistoryResponse::pointType, PointHistoryResponse::amount, PointHistoryResponse::balanceSnapshot)
                .containsExactly(
                        tuple(PointType.USE, 300, WELCOME_POINT),
                        tuple(PointType.USE, -300, 700));
    }

    @Test
    @DisplayName("결제 완료 주문을 전액 환불하면 사용 포인트가 한 번 복구되고, 같은 주문의 결제 후 취소가 다시 들어와도 중복 복구되지 않는다")
    void restoresOnce_whenPaidOrderCancelled() {
        // given — 300P 사용 주문을 결제 승인
        OrderFixtureFactory.Fixture fx = newFixture();
        rewardWelcome(fx.userId());
        OrderResponse placed = placeWithPoint(fx, 300);
        String paymentKey = "pk-" + placed.pgOrderId();
        PaymentResponse paid = paymentService.confirmPayment(paymentKey, placed.pgOrderId(), placed.finalAmount());

        // when — 전액 환불(이벤트 → cancelPaidOrder) 뒤 같은 주문에 대한 재요청
        paymentService.cancelPayment(paid.paymentId(), fx.userId(), UserRole.USER, CANCEL_REASON, null);

        assertThatThrownBy(() -> orderService.cancelPaidOrder(placed.orderId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ORDER_STATUS);
        assertThatThrownBy(() -> paymentService.cancelPayment(paid.paymentId(), fx.userId(), UserRole.USER, CANCEL_REASON, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_ALREADY_PROCESSED);

        // then — PG 환불은 한 번, 포인트 복구도 한 번
        then(paymentClient).should(times(1)).cancelPayment(paymentKey, CANCEL_REASON, null);
        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(balanceOf(fx.userId())).isEqualTo(WELCOME_POINT);
        assertThat(historiesOfOrder(fx.userId(), placed.orderId()))
                .extracting(PointHistoryResponse::amount, PointHistoryResponse::balanceSnapshot)
                .containsExactly(tuple(300, WELCOME_POINT), tuple(-300, 700));
    }

    // 재고 복구에서 겪은 lost update(StockRestoreLostUpdateTest)와 같은 순서를 포인트에 재현한다.
    // 취소 트랜잭션이 잔액 700 을 먼저 읽고, 다른 트랜잭션이 200P 사용을 커밋(500)한 뒤 300P 를 복구한다. 엔티티 값에 더해 저장하면 1000 이 된다.
    @Test
    @DisplayName("취소 트랜잭션이 사용자 엔티티를 먼저 로딩한 뒤 다른 주문이 포인트를 차감해도, 복구가 그 차감을 덮어쓰지 않는다")
    void restoreDoesNotOverwriteConcurrentDeduction() {
        // given
        OrderFixtureFactory.Fixture fx = newFixture();
        rewardWelcome(fx.userId());
        OrderResponse first = placeWithPoint(fx, 300);

        // when
        transactionTemplate.executeWithoutResult(status -> {
            User loaded = userRepository.findById(fx.userId()).orElseThrow();
            assertThat(loaded.getPointBalance()).isEqualTo(700);

            CompletableFuture.runAsync(() -> placeWithPoint(fx, 200)).join();

            orderService.cancelUnpaidOrder(first.orderId());
        });

        // then — 1000 - 300 - 200 + 300 = 800. 복구 이력의 잔여 포인트도 엔티티 값(700)이 아니라 복구 직후 DB 값이다.
        assertThat(balanceOf(fx.userId())).isEqualTo(800);
        assertThat(historiesOfOrder(fx.userId(), first.orderId()))
                .extracting(PointHistoryResponse::amount, PointHistoryResponse::balanceSnapshot)
                .containsExactly(tuple(300, 800), tuple(-300, 700));
    }

    @Test
    @DisplayName("포인트를 쓴 주문의 결제 승인은 포인트 차감 후 최종 금액으로만 통과하고, 할인 전 금액으로 요청하면 PG 호출 전에 거절된다")
    void approvesPaymentWithFinalAmountAfterPoint() {
        // given — 10,000원 상품에 3,000P 사용
        OrderFixtureFactory.Fixture fx = newFixture();
        grantPoint(fx.userId(), 3_000);
        OrderResponse placed = placeWithPoint(fx, 3_000);
        String paymentKey = "pk-" + placed.pgOrderId();

        assertThat(placed.totalAmount()).isEqualTo(PRICE);
        assertThat(placed.usedPoint()).isEqualTo(3_000);
        assertThat(placed.finalAmount()).isEqualTo(PRICE - 3_000);

        // when & then — 포인트 차감 전 금액은 금액 위변조로 거절
        assertThatThrownBy(() -> paymentService.confirmPayment(paymentKey, placed.pgOrderId(), PRICE))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        then(paymentClient).should(never()).confirmPayment(any(), any(), any());

        PaymentResponse approved = paymentService.confirmPayment(paymentKey, placed.pgOrderId(), PRICE - 3_000);

        assertThat(approved.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(approved.approvedAmount()).isEqualTo(PRICE - 3_000);
        then(paymentClient).should().confirmPayment(paymentKey, placed.pgOrderId(), PRICE - 3_000);
        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.PAID);
    }

    // 차감을 주문 INSERT 보다 먼저 하므로, 뒤에서 규칙 위반이 나면 차감이 롤백되는지 실제 트랜잭션으로 확인한다.
    @Test
    @DisplayName("차감 뒤 최종 결제 금액 0원 규칙 위반으로 거절되면 포인트 차감·재고 차감·이력이 모두 롤백된다")
    void rollsBackDeduction_whenPointRuleViolatedAfterDeduction() {
        // given — 잔액은 충분하지만 상품 금액 전부를 포인트로 결제하려는 주문
        OrderFixtureFactory.Fixture fx = newFixture();
        grantPoint(fx.userId(), 20_000);

        // when & then
        assertThatThrownBy(() -> placeWithPoint(fx, PRICE))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_EXCEEDS_PAYMENT_AMOUNT);

        assertThat(balanceOf(fx.userId())).isEqualTo(20_000);
        assertThat(stockOf(fx.optionId())).isEqualTo(STOCK);
        assertThat(historiesOf(fx.userId())).isEmpty();
    }

    // 요청 DTO 에 포인트 미사용용 보조 생성자가 있어도 JSON 은 정식 생성자로 바인딩돼야 한다.
    @Test
    @DisplayName("주문 API 는 음수 사용 포인트를 400(C001)으로 거절하고, 양수 사용 포인트는 요청 본문에서 바인딩해 최종 금액에 반영한다")
    void bindsUsedPointFromJson() throws Exception {
        // given
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        OrderFixtureFactory.Fixture fx = newFixture();
        rewardWelcome(fx.userId());
        MockHttpSession session = userSession(fx.userId());

        // when & then — 음수
        mockMvc.perform(post("/api/v1/orders/orders")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(fx, -1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()))
                .andExpect(jsonPath("$.message").value(startsWith("usedPoint")));
        assertThat(balanceOf(fx.userId())).isEqualTo(WELCOME_POINT);

        // when & then — 양수
        MvcResult result = mockMvc.perform(post("/api/v1/orders/orders")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(fx, 300)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usedPoint").value(300))
                .andExpect(jsonPath("$.finalAmount").value(PRICE - 300))
                .andReturn();
        orderIds.add(((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.orderId")).longValue());

        assertThat(balanceOf(fx.userId())).isEqualTo(WELCOME_POINT - 300);
    }

    // ===== Helper =====

    private OrderFixtureFactory.Fixture newFixture() {
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(STOCK);
        userIds.add(fx.userId());
        userIds.add(fx.storeId());
        productIds.add(fx.productId());
        return fx;
    }

    // 실제 로그인 적립 경로로 1000P 를 준다(WELCOME 이력 1건).
    private void rewardWelcome(Long userId) {
        userService.rewardLoginPoint(userId, LocalDate.now());
    }

    // 1000P 보다 큰 잔액이 필요한 테스트용 준비 데이터. 이력은 남기지 않는다.
    private void grantPoint(Long userId, int amount) {
        transactionTemplate.executeWithoutResult(status -> userRepository.addPoint(userId, amount));
    }

    private OrderResponse placeWithPoint(OrderFixtureFactory.Fixture fx, int usedPoint) {
        OrderResponse placed = orderService.placeOrder(fx.userId(), new OrderCreateRequest(
                fx.addressId(), List.of(new OrderDetailRequest(fx.optionId(), 1)), null, usedPoint));
        orderIds.add(placed.orderId());
        return placed;
    }

    private String orderJson(OrderFixtureFactory.Fixture fx, int usedPoint) {
        return """
                {"addressId": %d, "details": [{"productOptionId": %d, "quantity": 1}], "usedPoint": %d}
                """.formatted(fx.addressId(), fx.optionId(), usedPoint);
    }

    private MockHttpSession userSession(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstants.SESSION_USER_ID, userId);
        session.setAttribute(SessionConstants.SESSION_USER_ROLE, UserRole.USER);
        return session;
    }

    private int balanceOf(Long userId) {
        return userRepository.findPointBalance(userId);
    }

    private int stockOf(Long optionId) {
        return productOptionRepository.findById(optionId).orElseThrow().getStockQuantity();
    }

    private OrderStatus orderStatusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getOrderStatus();
    }

    private List<PointHistoryResponse> historiesOf(Long userId) {
        return pointService.getMyPoints(userId, null, 100).histories().items();
    }

    private List<PointHistoryResponse> historiesOfOrder(Long userId, Long orderId) {
        return historiesOf(userId).stream()
                .filter(history -> orderId.equals(history.orderId()))
                .toList();
    }
}
