package ccommit.stylehub.product.service;

import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.payment.repository.PaymentRepository;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 주문 취소의 재고 복구가 그 사이 커밋된 다른 주문의 차감을 덮어쓰지 않는지 검증하는 통합 테스트이다.
 * 취소 트랜잭션이 옵션을 먼저 읽은 뒤 다른 주문이 차감을 커밋하는 순서를 트랜잭션 경계로 직접 만들어 재현한다.
 * </p>
 */
@SpringBootTest
class StockRestoreLostUpdateTest {

    private static final int INITIAL_STOCK = 10;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private OrderDetailRepository orderDetailRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY);
        paymentRepository.deleteAll();
        orderDetailRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("복구 트랜잭션이 옵션을 먼저 읽은 뒤 다른 주문이 재고 차감을 커밋해도, 복구가 그 차감을 덮어쓰지 않는다")
    void restoreDoesNotOverwriteConcurrentDecrease() throws Exception {
        // given — 2개 주문으로 재고 10 → 8
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(INITIAL_STOCK);
        OrderResponse cancelled = orderService.placeOrder(fx.userId(), new OrderCreateRequest(
                fx.addressId(), List.of(new OrderDetailRequest(fx.optionId(), 2)), null));

        // when — 취소 트랜잭션: 주문 상세 조회로 옵션(재고 8)을 영속성 컨텍스트에 올린 뒤,
        //        다른 트랜잭션이 1개 차감을 커밋하고(재고 7), 그다음 2개를 복구한다.
        transactionTemplate.executeWithoutResult(status -> {
            orderDetailRepository.findByOrderIdWithDetails(cancelled.orderId());

            CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(inner ->
                    productService.decreaseStockWithLock(fx.optionId(), 1))).join();

            productService.increaseStock(fx.optionId(), 2);
        });

        // then — 8 - 1 + 2 = 9. 복구가 읽어 둔 8 을 기준으로 쓰면 10 이 되어 차감 1개가 사라진다.
        int stock = productOptionRepository.findById(fx.optionId()).orElseThrow().getStockQuantity();
        assertThat(stock).isEqualTo(INITIAL_STOCK - 2 - 1 + 2);
    }
}
