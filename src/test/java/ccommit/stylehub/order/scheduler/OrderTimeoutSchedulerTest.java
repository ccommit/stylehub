package ccommit.stylehub.order.scheduler;

import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.payment.repository.PaymentRepository;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/04/29
 * @modified 2026/05/01 by WonJin - test: @AfterEach 에 DB 정리 로직 추가 (Order/OrderDetail/Payment) — 테스트 간 누적되던 행을 매 테스트 종료 시 제거해 isolation 강화
 *
 * <p>
 * 결제 타임아웃 스케줄러 통합 테스트.
 * 대량 만료 주문이 한 번에 들어와도 재고 복구가 누락되지 않는지,
 * 다중 인스턴스 동시 실행 시 Lua 스크립트 원자성으로 중복 처리가 발생하지 않는지를 검증한다.
 *
 * <b>@SpringBootTest 사용 이유</b>
 * 검증 대상이 mock으로 재현 불가능한 인프라 동작이기 때문이다.
 * - Redis Lua 스크립트의 ZRANGEBYSCORE+ZREM 원자성은 실제 Redis가 있어야 검증 가능
 * - @TransactionalEventListener(AFTER_COMMIT)으로 등록되는 ZSET 타임아웃은 트랜잭션 라이프사이클이 실제로 돌아야 실행됨
 * - 비관적 락(SELECT FOR UPDATE)을 통한 재고 복구 정합성은 실제 DB 트랜잭션과 동시성 제어가 필요
 * 단위 검증 대신 통합 검증을 택했으며, OrderConcurrencyTest와 동일한 컨벤션을 따른다.
 * 테스트 격리는 @Transactional 자동 롤백 대신 @AfterEach 명시적 삭제로 처리한다 —
 * 테스트 메서드를 트랜잭션으로 감싸면 placeOrder 가 commit 되지 않아 AFTER_COMMIT 이벤트가 발생하지 않기 때문이다.
 * </p>
 */
@SpringBootTest
class OrderTimeoutSchedulerTest {

    @Autowired
    private OrderTimeoutScheduler scheduler;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderDetailRepository orderDetailRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 테스트 간 isolation 보장:
     * - Redis ZSET 타임아웃 키 정리
     * - DB 의 Payment / OrderDetail / Order 행을 외래키 의존 순서대로 삭제
     *   (Payment.order_id → OrderDetail.order_id → Order)
     * 재고(ProductOption.stockQuantity) 는 각 테스트 첫 줄의 setStock() 이 명시적으로 덮어쓰므로 별도 정리 불필요.
     */
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY);
        paymentRepository.deleteAll();
        orderDetailRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("ZSET에 만료된 주문이 있으면 cancelExpiredOrders가 CANCELLED로 전이하고 재고를 복구한다")
    void cancelsExpiredOrderAndRestoresStock() {
        // given
        Long userId = 1L;
        Long addressId = 1L;
        Long optionId = 1L;
        int initialStock = 10;
        int orderQuantity = 3;

        setStock(optionId, initialStock);

        OrderResponse placed = orderService.placeOrder(userId, new OrderCreateRequest(
                addressId, List.of(new OrderDetailRequest(optionId, orderQuantity))
        ));
        Long orderId = placed.orderId();

        // 주문 시점에 등록된 타임아웃 score를 과거로 덮어써 만료 상태로 만든다
        moveTimeoutToPast(orderId);

        // when
        scheduler.cancelExpiredOrders();

        // then
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);

        ProductOption restored = productOptionRepository.findById(optionId).orElseThrow();
        assertThat(restored.getStockQuantity()).isEqualTo(initialStock);

        Long zsetSize = redisTemplate.opsForZSet().size(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY);
        assertThat(zsetSize).isZero();
    }

    @Test
    @DisplayName("100건 만료 주문이 한 번에 들어와도 모두 CANCELLED로 처리되고 재고는 정확히 초기값으로 복구된다")
    void handlesBulkExpiredOrders() {
        // given
        Long userId = 1L;
        Long addressId = 1L;
        Long optionId = 1L;
        int orderCount = 100;
        int qtyPerOrder = 1;
        int initialStock = orderCount + 50; // 여유 재고

        setStock(optionId, initialStock);

        List<Long> orderIds = placeOrdersAndGetIds(userId, addressId, optionId, orderCount, qtyPerOrder);
        orderIds.forEach(this::moveTimeoutToPast);

        // when — BATCH_SIZE=100이므로 한 번 호출로 전부 처리되어야 한다
        scheduler.cancelExpiredOrders();

        // then
        long cancelled = countByStatus(orderIds, OrderStatus.CANCELLED);

        ProductOption restored = productOptionRepository.findById(optionId).orElseThrow();

        System.out.println("=== 대량 만료 주문 복구 결과 ===");
        System.out.println("CANCELLED 전이된 주문: " + cancelled);
        System.out.println("최종 재고: " + restored.getStockQuantity());

        assertThat(cancelled).isEqualTo(orderCount);
        assertThat(restored.getStockQuantity()).isEqualTo(initialStock);
    }

    @Test
    @DisplayName("두 스레드가 동시에 cancelExpiredOrders를 호출해도 같은 주문이 두 번 취소되지 않는다 (Lua 원자성)")
    void luaScriptPreventsDuplicateProcessingUnderConcurrency() throws InterruptedException {
        // given
        Long userId = 1L;
        Long addressId = 1L;
        Long optionId = 1L;
        int orderCount = 30;
        int initialStock = orderCount + 50;

        setStock(optionId, initialStock);

        List<Long> orderIds = placeOrdersAndGetIds(userId, addressId, optionId, orderCount, 1);
        orderIds.forEach(this::moveTimeoutToPast);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        // when — 두 스레드가 동시에 만료 주문을 꺼내려 시도
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    scheduler.cancelExpiredOrders();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    finish.countDown();
                }
            });
        }
        start.countDown();
        finish.await();
        executor.shutdown();

        // then — 모든 주문이 CANCELLED, 재고가 정확히 초기값으로 복구
        long cancelled = countByStatus(orderIds, OrderStatus.CANCELLED);
        ProductOption restored = productOptionRepository.findById(optionId).orElseThrow();

        System.out.println("=== 동시 스케줄러 실행 결과 ===");
        System.out.println("CANCELLED 전이: " + cancelled);
        System.out.println("스케줄러 예외: " + errorCount.get());
        System.out.println("최종 재고: " + restored.getStockQuantity());

        assertThat(cancelled).isEqualTo(orderCount);
        assertThat(restored.getStockQuantity()).isEqualTo(initialStock);
        assertThat(errorCount.get()).isZero();
    }

    @Test
    @DisplayName("아직 만료되지 않은 주문(score가 미래)은 ZSET에 남고 처리되지 않는다")
    void doesNotProcessFutureOrders() {
        // given
        Long userId = 1L;
        Long addressId = 1L;
        Long optionId = 1L;

        setStock(optionId, 10);

        OrderResponse placed = orderService.placeOrder(userId, new OrderCreateRequest(
                addressId, List.of(new OrderDetailRequest(optionId, 1))
        ));
        Long orderId = placed.orderId();

        // when — 미래 score 그대로 두고 스케줄러 실행
        scheduler.cancelExpiredOrders();

        // then — 주문은 PENDING 그대로, ZSET에도 남아 있어야 한다
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);

        Double score = redisTemplate.opsForZSet().score(
                OrderTimeoutScheduler.ORDER_TIMEOUT_KEY, String.valueOf(orderId)
        );
        assertThat(score).isNotNull();
        assertThat(score).isGreaterThan(System.currentTimeMillis());
    }

    private void setStock(Long optionId, int stock) {
        ProductOption option = productOptionRepository.findById(optionId).orElseThrow();
        option.updateStockQuantity(stock);
        productOptionRepository.save(option);
    }

    private List<Long> placeOrdersAndGetIds(Long userId, Long addressId, Long optionId, int count, int qtyPerOrder) {
        List<Long> orderIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            OrderResponse placed = orderService.placeOrder(userId, new OrderCreateRequest(
                    addressId, List.of(new OrderDetailRequest(optionId, qtyPerOrder))
            ));
            orderIds.add(placed.orderId());
        }
        return orderIds;
    }

    private void moveTimeoutToPast(Long orderId) {
        redisTemplate.opsForZSet().add(
                OrderTimeoutScheduler.ORDER_TIMEOUT_KEY,
                String.valueOf(orderId),
                System.currentTimeMillis() - 1000
        );
    }

    private long countByStatus(List<Long> orderIds, OrderStatus status) {
        return orderIds.stream()
                .map(id -> orderRepository.findById(id).orElseThrow())
                .filter(o -> o.getOrderStatus() == status)
                .count();
    }
}
