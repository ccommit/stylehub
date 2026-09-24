package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.request.UpdateDeliveryStatusRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.payment.client.PaymentClient;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.enums.PaymentStatus;
import ccommit.stylehub.payment.repository.PaymentRepository;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 결제 완료 주문의 배송 전이와 결제 취소가 주문·재고·PG 호출과 어긋나지 않는지 실제 트랜잭션으로 검증하는 통합 테스트이다.
 * 결제 취소는 동기 리스너가 같은 트랜잭션에서 주문을 취소해 목으로는 PG 호출 뒤 롤백 같은 결함을 볼 수 없어, PG 통신만 목으로 막는다.
 * </p>
 */
@SpringBootTest
class PaymentCancelOrderConsistencyTest {

    private static final int INITIAL_STOCK = 10;
    private static final String REASON = "단순 변심";

    @MockitoBean
    private PaymentClientFactory paymentClientFactory;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderService orderService;

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

    private PaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        paymentClient = mock(PaymentClient.class);
        given(paymentClientFactory.getClient(any())).willReturn(paymentClient);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY);
        paymentRepository.deleteAll();
        orderDetailRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("스토어는 결제 완료 주문을 PAID → PREPARING → SHIPPING → DELIVERED 순서로 진행할 수 있고, 건너뛰는 전이는 거절된다")
    void progressesDeliveryFromPaid() {
        // given
        PaidOrder paid = placePaidOrder();

        // when & then
        assertThatThrownBy(() -> changeStatus(paid, OrderStatus.SHIPPING))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_DELIVERY_STATUS);

        changeStatus(paid, OrderStatus.PREPARING);
        changeStatus(paid, OrderStatus.SHIPPING);
        changeStatus(paid, OrderStatus.DELIVERED);
        assertThat(orderStatusOf(paid.orderId())).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("배송 준비(PREPARING) 주문을 전액 취소하면 결제·주문이 함께 취소되고 재고가 복구되며 PG 취소는 한 번 호출된다")
    void cancelsPreparingOrderConsistently() {
        // given
        PaidOrder paid = placePaidOrder();
        changeStatus(paid, OrderStatus.PREPARING);

        // when
        paymentService.cancelPayment(paid.paymentId(), paid.buyerId(), UserRole.USER, REASON, null, null);

        // then
        assertThat(paymentStatusOf(paid.paymentId())).isEqualTo(PaymentStatus.CANCELED);
        assertThat(orderStatusOf(paid.orderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(paid.optionId())).isEqualTo(INITIAL_STOCK);
        then(paymentClient).should(times(1)).cancelPayment(paid.paymentKey(), REASON, null, null);
    }

    @Test
    @DisplayName("배송 완료(DELIVERED) 후 7일 이내 전액 환불도 결제·주문이 함께 취소된다")
    void refundsDeliveredOrderConsistently() {
        // given
        PaidOrder paid = placePaidOrder();
        changeStatus(paid, OrderStatus.PREPARING);
        changeStatus(paid, OrderStatus.SHIPPING);
        changeStatus(paid, OrderStatus.DELIVERED);

        // when
        paymentService.cancelPayment(paid.paymentId(), paid.buyerId(), UserRole.USER, REASON, null, null);

        // then
        assertThat(paymentStatusOf(paid.paymentId())).isEqualTo(PaymentStatus.CANCELED);
        assertThat(orderStatusOf(paid.orderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(paid.optionId())).isEqualTo(INITIAL_STOCK);
    }

    @Test
    @DisplayName("배송 중(SHIPPING) 주문은 취소를 거절하고 PG 를 호출하지 않는다")
    void rejectsCancelWhileShipping() {
        // given
        PaidOrder paid = placePaidOrder();
        changeStatus(paid, OrderStatus.PREPARING);
        changeStatus(paid, OrderStatus.SHIPPING);

        // when & then
        assertThatThrownBy(() -> paymentService.cancelPayment(paid.paymentId(), paid.buyerId(), UserRole.USER, REASON, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
        then(paymentClient).should(never()).cancelPayment(any(), any(), any(), any());
        assertThat(paymentStatusOf(paid.paymentId())).isEqualTo(PaymentStatus.DONE);
        assertThat(orderStatusOf(paid.orderId())).isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    @DisplayName("PG 취소가 실패하면 결제·주문·재고 반영이 모두 롤백된다")
    void rollsBackWhenPgCancelFails() {
        // given
        PaidOrder paid = placePaidOrder();
        changeStatus(paid, OrderStatus.PREPARING);
        willThrow(new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED))
                .given(paymentClient).cancelPayment(any(), any(), any(), any());

        // when & then
        assertThatThrownBy(() -> paymentService.cancelPayment(paid.paymentId(), paid.buyerId(), UserRole.USER, REASON, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_CANCEL_FAILED);
        assertThat(paymentStatusOf(paid.paymentId())).isEqualTo(PaymentStatus.DONE);
        assertThat(orderStatusOf(paid.orderId())).isEqualTo(OrderStatus.PREPARING);
        assertThat(stockOf(paid.optionId())).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    @DisplayName("같은 결제에 잔액을 합쳐 넘는 부분 취소 두 건이 동시에 들어오면 한 건만 성공하고 PG 취소도 한 번만 호출된다")
    void serializesConcurrentPartialCancels() throws InterruptedException {
        // given — 결제 금액 10,000원에 6,000원 부분 취소 2건
        PaidOrder paid = placePaidOrder();
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> failures = new ConcurrentHashMap<>();

        // when
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    paymentService.cancelPayment(paid.paymentId(), paid.buyerId(), UserRole.USER, REASON, 6000, null);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    failures.computeIfAbsent(e.getErrorCode(), code -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    failures.computeIfAbsent(ErrorCode.INTERNAL_SERVER_ERROR, code -> new AtomicInteger()).incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // then
        assertThat(success.get()).isEqualTo(1);
        assertThat(failures.get(ErrorCode.INVALID_CANCEL_AMOUNT)).hasValue(1);
        then(paymentClient).should(times(1)).cancelPayment(any(), any(), any(), any());
        Payment payment = paymentRepository.findById(paid.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        assertThat(payment.getBalanceAmount()).isEqualTo(4000);
    }

    @Test
    @DisplayName("여러 스토어 상품이 섞인 주문은 한 스토어가 주문 전체의 배송 상태를 바꿀 수 없다")
    void rejectsDeliveryChangeForMultiStoreOrder() {
        // given
        OrderFixtureFactory.Fixture buyer = fixtureFactory.create(INITIAL_STOCK);
        OrderFixtureFactory.StoreProduct storeA = fixtureFactory.createStoreProduct(INITIAL_STOCK);
        OrderFixtureFactory.StoreProduct storeB = fixtureFactory.createStoreProduct(INITIAL_STOCK);
        OrderResponse placed = orderService.placeOrder(buyer.userId(), new OrderCreateRequest(
                buyer.addressId(),
                List.of(new OrderDetailRequest(storeA.optionId(), 1), new OrderDetailRequest(storeB.optionId(), 1)),
                null
        ));
        paymentService.confirmPayment("pk-" + placed.pgOrderId(), placed.pgOrderId(), placed.finalAmount());

        // when & then
        assertThatThrownBy(() -> orderService.updateDeliveryStatus(new UpdateDeliveryStatusRequest(
                storeA.storeId(), storeA.storeId(), placed.orderId(), OrderStatus.PREPARING)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED_DELIVERY_ACCESS);
        assertThat(orderStatusOf(placed.orderId())).isEqualTo(OrderStatus.PAID);
    }

    private record PaidOrder(Long buyerId, Long storeId, Long optionId, Long orderId, Long paymentId, String paymentKey) {
    }

    private PaidOrder placePaidOrder() {
        OrderFixtureFactory.Fixture buyer = fixtureFactory.create(INITIAL_STOCK);
        OrderFixtureFactory.StoreProduct store = fixtureFactory.createStoreProduct(INITIAL_STOCK);
        OrderResponse placed = orderService.placeOrder(buyer.userId(), new OrderCreateRequest(
                buyer.addressId(), List.of(new OrderDetailRequest(store.optionId(), 1)), null
        ));
        String paymentKey = "pk-" + placed.pgOrderId();
        Long paymentId = paymentService.confirmPayment(paymentKey, placed.pgOrderId(), placed.finalAmount()).paymentId();
        return new PaidOrder(buyer.userId(), store.storeId(), store.optionId(), placed.orderId(), paymentId, paymentKey);
    }

    private void changeStatus(PaidOrder paid, OrderStatus next) {
        orderService.updateDeliveryStatus(new UpdateDeliveryStatusRequest(paid.storeId(), paid.storeId(), paid.orderId(), next));
    }

    private OrderStatus orderStatusOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getOrderStatus();
    }

    private PaymentStatus paymentStatusOf(Long paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow().getStatus();
    }

    private int stockOf(Long optionId) {
        return productOptionRepository.findById(optionId).orElseThrow().getStockQuantity();
    }
}
