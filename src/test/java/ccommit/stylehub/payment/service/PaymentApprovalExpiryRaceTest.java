package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.payment.client.PaymentClient;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
import ccommit.stylehub.payment.dto.response.PgPaymentSnapshot;
import ccommit.stylehub.payment.enums.PaymentStatus;
import ccommit.stylehub.payment.repository.PaymentRepository;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 결제 승인과 주문 만료 처리가 겹치는 상황을 실제 DB·Redis·트랜잭션으로 재현하는 통합 테스트이다.
 * 트랜잭션 경계 사이 시점은 목으로 만들 수 없어 PG 통신만 목으로 막고 응답 지연은 래치로 만든다.
 * </p>
 */
@SpringBootTest
class PaymentApprovalExpiryRaceTest {

    private static final int INITIAL_STOCK = 10;

    @MockitoBean
    private PaymentClientFactory paymentClientFactory;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderTimeoutScheduler scheduler;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderDetailRepository orderDetailRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        paymentClient = mock(PaymentClient.class);
        given(paymentClientFactory.getClient(any())).willReturn(paymentClient);
        given(paymentClient.findPayment(any())).willReturn(PgPaymentSnapshot.notFound());
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY);
        paymentRepository.deleteAll();
        orderDetailRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("만료 처리로 취소된 주문에 늦게 도착한 승인 콜백은 PG 승인을 요청하지 않고 ORDER_NOT_PAYABLE 로 거절된다")
    void rejectsLateApprovalAfterExpiry() {
        // given — 결제하지 않은 채 만료 시각이 지나 스케줄러가 취소
        PlacedOrder placed = placeOrder();
        moveTimeoutToPast(placed.orderId());
        scheduler.cancelExpiredOrders();

        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentStatusOf(placed.pgOrderId())).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(stockOf(placed.optionId())).isEqualTo(INITIAL_STOCK);

        // when & then
        assertThatThrownBy(() -> paymentService.confirmPayment("pk-late", placed.pgOrderId(), placed.amount()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_PAYABLE);
        then(paymentClient).should(never()).confirmPayment(any(), any(), any());
        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("PG 승인 응답을 기다리는 사이 만료 처리가 돌아도 주문을 취소하지 않고, 승인이 끝나면 결제 완료가 된다")
    void doesNotExpireWhileApprovalInFlight() throws Exception {
        // given
        PlacedOrder placed = placeOrder();
        CountDownLatch pgCalled = new CountDownLatch(1);
        CountDownLatch releasePg = new CountDownLatch(1);
        willAnswer(invocation -> {
            pgCalled.countDown();
            releasePg.await(10, TimeUnit.SECONDS);
            return null;
        }).given(paymentClient).confirmPayment(any(), any(), any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentResponse> approval = executor.submit(
                    () -> paymentService.confirmPayment("pk-inflight", placed.pgOrderId(), placed.amount()));
            assertThat(pgCalled.await(10, TimeUnit.SECONDS)).isTrue();

            // when — 승인 요청이 PG 에 머무는 동안 만료 시각이 지나 스케줄러 실행
            moveTimeoutToPast(placed.orderId());
            scheduler.cancelExpiredOrders();

            // then — 취소하지 않고 결론을 미룬다
            assertThat(paymentStatusOf(placed.pgOrderId())).isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.PENDING);
            assertThat(stockOf(placed.optionId())).isEqualTo(INITIAL_STOCK - 1);
            assertThat(timeoutScoreOf(placed.orderId())).isGreaterThan((double) System.currentTimeMillis());

            releasePg.countDown();
            PaymentResponse response = approval.get(10, TimeUnit.SECONDS);

            assertThat(response.status()).isEqualTo(PaymentStatus.DONE);
            assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.PAID);
            assertThat(stockOf(placed.optionId())).isEqualTo(INITIAL_STOCK - 1);
        } finally {
            releasePg.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("만료 직전 PG 조회가 실패하면 주문을 취소하지 않고 타이머를 다시 등록한다")
    void reschedules_whenPgLookupFails() {
        // given
        PlacedOrder placed = placeOrder();
        willThrow(new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN))
                .given(paymentClient).findPayment(placed.pgOrderId());
        moveTimeoutToPast(placed.orderId());

        // when
        scheduler.cancelExpiredOrders();

        // then
        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.PENDING);
        assertThat(paymentStatusOf(placed.pgOrderId())).isEqualTo(PaymentStatus.READY);
        assertThat(stockOf(placed.optionId())).isEqualTo(INITIAL_STOCK - 1);
        assertThat(timeoutScoreOf(placed.orderId())).isGreaterThan((double) System.currentTimeMillis());
    }

    @Test
    @DisplayName("Redis 타이머가 없는 주문도 보정 스케줄러가 PG 대조를 거쳐, 응답이 유실된 승인 건을 취소하지 않고 결제 완료로 맞춘다")
    void compensationConsultsPg_beforeCancelling() {
        // given — 타이머 등록과 승인 응답이 모두 유실된 주문
        PlacedOrder placed = placeOrder();
        redisTemplate.opsForZSet().remove(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY, String.valueOf(placed.orderId()));
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE order_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(30)), placed.orderId());
        given(paymentClient.findPayment(placed.pgOrderId()))
                .willReturn(new PgPaymentSnapshot(true, "pk-lost", placed.amount()));

        // when
        scheduler.compensateOrphanedOrders();

        // then
        assertThat(paymentStatusOf(placed.pgOrderId())).isEqualTo(PaymentStatus.DONE);
        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.PAID);
        assertThat(stockOf(placed.optionId())).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    @DisplayName("PG 가 승인을 거절하면 결제가 READY 로 돌아가 같은 주문을 다시 결제할 수 있다")
    void allowsRetry_afterPgDecline() {
        // given
        PlacedOrder placed = placeOrder();
        willThrow(new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED))
                .willAnswer(invocation -> null)
                .given(paymentClient).confirmPayment(any(), any(), any());

        // when — 첫 시도는 거절
        assertThatThrownBy(() -> paymentService.confirmPayment("pk-1", placed.pgOrderId(), placed.amount()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_APPROVAL_FAILED);
        assertThat(paymentStatusOf(placed.pgOrderId())).isEqualTo(PaymentStatus.READY);

        // then — 재시도 성공
        PaymentResponse response = paymentService.confirmPayment("pk-2", placed.pgOrderId(), placed.amount());
        assertThat(response.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("PG 결과를 알 수 없는 승인은 유예 시간 동안 만료되지 않고, 유예가 지나 PG 에 승인이 없으면 만료·취소된다")
    void expiresUnknownApproval_onlyAfterGrace() {
        // given — 승인 요청 결과 불명(읽기 타임아웃 등)
        PlacedOrder placed = placeOrder();
        willThrow(new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN))
                .given(paymentClient).confirmPayment(any(), any(), any());
        assertThatThrownBy(() -> paymentService.confirmPayment("pk-unknown", placed.pgOrderId(), placed.amount()))
                .isInstanceOf(BusinessException.class);
        assertThat(paymentStatusOf(placed.pgOrderId())).isEqualTo(PaymentStatus.IN_PROGRESS);

        // when — 유예 시간 안의 만료 처리
        moveTimeoutToPast(placed.orderId());
        scheduler.cancelExpiredOrders();

        // then — 보류
        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.PENDING);

        // when — 유예 시간이 지난 뒤 다시 만료 처리
        jdbcTemplate.update("UPDATE payments SET updated_at = ? WHERE order_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)), placed.orderId());
        moveTimeoutToPast(placed.orderId());
        scheduler.cancelExpiredOrders();

        // then — PG 에 승인이 없으므로 만료·취소, 재고 복구
        assertThat(paymentStatusOf(placed.pgOrderId())).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(placed.optionId())).isEqualTo(INITIAL_STOCK);
    }

    private record PlacedOrder(Long orderId, String pgOrderId, Integer amount, Long optionId) {
    }

    private PlacedOrder placeOrder() {
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(INITIAL_STOCK);
        OrderResponse placed = orderService.placeOrder(fx.userId(), new OrderCreateRequest(
                fx.addressId(), List.of(new OrderDetailRequest(fx.optionId(), 1)), null
        ));
        return new PlacedOrder(placed.orderId(), placed.pgOrderId(), placed.finalAmount(), fx.optionId());
    }

    private void moveTimeoutToPast(Long orderId) {
        redisTemplate.opsForZSet().add(
                OrderTimeoutScheduler.ORDER_TIMEOUT_KEY, String.valueOf(orderId), System.currentTimeMillis() - 1000);
    }

    private Double timeoutScoreOf(Long orderId) {
        return redisTemplate.opsForZSet().score(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY, String.valueOf(orderId));
    }

    private OrderStatus orderStatusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getOrderStatus();
    }

    private PaymentStatus paymentStatusOf(String pgOrderId) {
        return paymentRepository.findByOrderPgOrderId(pgOrderId).orElseThrow().getStatus();
    }

    private int stockOf(Long optionId) {
        return productOptionRepository.findById(optionId).orElseThrow().getStockQuantity();
    }
}
