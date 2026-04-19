package ccommit.stylehub.order.service;

import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.repository.ProductOptionRepository;
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

    @Test
    @DisplayName("재고 10개인 상품에 동시에 10명이 1개씩 주문하면 재고가 정확히 0이 된다")
    void concurrentOrderDecreasesStockCorrectly() throws InterruptedException {
        // given
        // 테스트 전 DB에 다음 데이터가 있어야 합니다:
        // - userId에 해당하는 User (bwj3@test.com)
        // - 해당 User의 Address (addressId)
        // - stockQuantity = 10인 ProductOption (optionId)
        // 아래 값을 실제 DB 데이터에 맞게 수정하세요
        Long userId = 1L;       // 테스트 유저 ID
        Long addressId = 1L;    // 테스트 배송지 ID
        Long optionId = 1L;     // 테스트 옵션 ID (재고를 10으로 미리 설정)

        // 재고를 10으로 설정
        ProductOption option = productOptionRepository.findById(optionId).orElseThrow();
        option.updateStockQuantity(10);
        productOptionRepository.save(option);

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
                            List.of(new OrderDetailRequest(optionId, 1))
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
        Long userId = 1L;
        Long addressId = 1L;
        Long optionId = 1L;

        // 재고를 5로 설정
        ProductOption option = productOptionRepository.findById(optionId).orElseThrow();
        option.updateStockQuantity(5);
        productOptionRepository.save(option);

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
                            List.of(new OrderDetailRequest(optionId, 1))
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
