package ccommit.stylehub.order.service;

import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * 주문 시 재고 차감 동시성 테스트이다.
 * 비관적 락(SELECT FOR UPDATE)이 동시 주문에서 재고 정합성을 보장하는지 검증한다.
 * </p>
 */
@SpringBootTest
class OrderConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Test
    @DisplayName("재고 10개인 상품에 동시에 10명이 1개씩 주문하면 재고가 정확히 0이 된다")
    void concurrentOrderDecreasesStockCorrectly() throws InterruptedException {
        // given
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(10);
        Long userId = fx.userId();
        Long addressId = fx.addressId();
        Long optionId = fx.optionId();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when — 10명이 동시에 1개씩 주문
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    OrderCreateRequest request = new OrderCreateRequest(
                            addressId,
                            List.of(new OrderDetailRequest(optionId, 1)),
                            null
                    );
                    orderService.placeOrder(userId, request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // then
        ProductOption result = productOptionRepository.findById(optionId).orElseThrow();

        System.out.println("=== 동시성 테스트 결과 ===");
        System.out.println("성공: " + successCount.get());
        System.out.println("실패: " + failCount.get());
        System.out.println("최종 재고: " + result.getStockQuantity());

        assertThat(successCount.get()).isEqualTo(10);
        assertThat(result.getStockQuantity()).isEqualTo(0);
    }

    @Test
    @DisplayName("재고 5개인 상품에 동시에 10명이 1개씩 주문하면 5명만 성공한다")
    void concurrentOrderWithInsufficientStock() throws InterruptedException {
        // given
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(5);
        Long userId = fx.userId();
        Long addressId = fx.addressId();
        Long optionId = fx.optionId();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when — 10명이 동시에 1개씩 주문
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    OrderCreateRequest request = new OrderCreateRequest(
                            addressId,
                            List.of(new OrderDetailRequest(optionId, 1)),
                            null
                    );
                    orderService.placeOrder(userId, request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // then
        ProductOption result = productOptionRepository.findById(optionId).orElseThrow();

        System.out.println("=== 재고 부족 동시성 테스트 결과 ===");
        System.out.println("성공: " + successCount.get());
        System.out.println("실패 (INSUFFICIENT_STOCK): " + failCount.get());
        System.out.println("최종 재고: " + result.getStockQuantity());

        assertThat(successCount.get()).isEqualTo(5);
        assertThat(failCount.get()).isEqualTo(5);
        assertThat(result.getStockQuantity()).isEqualTo(0);
    }
}
