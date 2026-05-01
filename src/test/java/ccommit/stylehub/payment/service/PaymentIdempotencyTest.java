package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.payment.client.PaymentClient;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.enums.PaymentStatus;
import ccommit.stylehub.payment.repository.PaymentRepository;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

/**
 * @author WonJin Bae
 * @created 2026/04/29
 * @modified 2026/05/01 by WonJin - test: concurrentIdempotency 의 alreadyProcessed/otherFail 검증을 분리해 예상 못한 예외도 노출되도록 강화
 *
 * <p>
 * 결제 콜백 멱등성 통합 테스트
 * 토스가 같은 paymentKey로 콜백을 여러 번 보내도 결제 승인·재고·포인트가 정확히 한 번만 반영되는지 검증한다.
 * 순차 호출은 PaymentValidator.validateApprovable로 막혀야 하고,
 * 동시 호출 race condition에서도 정확히 1건만 성공해야 한다.
 *
 *
 * <b>@SpringBootTest 사용 이유</b>
 * 동시 호출 race condition은 mock으로 재현이 불가능하기 때문
 * Mockito 단위 테스트는 stub된 응답을 즉시 반환하므로 두 스레드가 동시에 같은 Payment를 READ→PROCESS 하는
 * 타이밍이 발생하지 않고 멱등성 보장 여부를 검증할 수 없다.
 *
 * 실제 JPA persistence context, @Transactional commit 타이밍, DB row 가시성이 모두 살아있어야
 * race condition을 재현하고 멱등 보장 여부를 노출할 수 있다.
 * 단 PG(토스) HTTP 호출은 외부 경계이므로 PaymentClientFactory만 @MockitoBean으로 차단하고
 * 내부 컴포넌트(JPA, 트랜잭션, 이벤트)는 모두 실제 빈을 사용한다 — 외부 경계 Mock 패턴.
 * </p>
 */
@SpringBootTest
class PaymentIdempotencyTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @MockitoBean
    private PaymentClientFactory paymentClientFactory;

    private PaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        paymentClient = mock(PaymentClient.class);
        given(paymentClientFactory.getClient("TOSS")).willReturn(paymentClient);
    }

    @Test
    @DisplayName("동일 paymentKey로 순차 5회 호출 시 1회만 승인되고 나머지는 PAYMENT_ALREADY_PROCESSED로 거절된다")
    void sequentialIdempotency() {
        // given
        ReadyPaymentContext ctx = setupReadyPayment();
        String paymentKey = "test-pk-seq";

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger alreadyProcessedCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);

        // when — 같은 paymentKey로 5회 반복 호출
        for (int i = 0; i < 5; i++) {
            try {
                paymentService.confirmPayment(paymentKey, ctx.pgOrderId, ctx.amount);
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.PAYMENT_ALREADY_PROCESSED) {
                    alreadyProcessedCount.incrementAndGet();
                } else {
                    otherFailCount.incrementAndGet();
                }
            }
        }

        // then
        System.out.println("=== 순차 멱등성 테스트 결과 ===");
        System.out.println("성공: " + successCount.get());
        System.out.println("PAYMENT_ALREADY_PROCESSED: " + alreadyProcessedCount.get());
        System.out.println("기타 실패: " + otherFailCount.get());

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(alreadyProcessedCount.get()).isEqualTo(4);
        assertThat(otherFailCount.get()).isZero();

        // PG는 정확히 1번만 호출 — 거절 경로는 PG 호출 없이 빠른 실패
        then(paymentClient).should(times(1)).confirmPayment(any(), any(), any());

        // approved_at이 한 번만 찍힘, 상태는 DONE
        Payment saved = paymentRepository.findByOrderPgOrderId(ctx.pgOrderId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(saved.getApprovedAt()).isNotNull();
        assertThat(saved.getPaymentKey()).isEqualTo(paymentKey);
    }

    @Test
    @DisplayName("동일 paymentKey로 동시 10회 호출 시 정확히 1회만 승인되고 PG도 1번만 호출된다 (race condition)")
    void concurrentIdempotency() throws InterruptedException {
        // given
        ReadyPaymentContext ctx = setupReadyPayment();
        String paymentKey = "test-pk-concurrent";

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger alreadyProcessedCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);

        // when — 모든 스레드가 동시에 시작해 같은 paymentKey로 confirmPayment 호출
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    paymentService.confirmPayment(paymentKey, ctx.pgOrderId, ctx.amount);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.PAYMENT_ALREADY_PROCESSED) {
                        alreadyProcessedCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherFailCount.incrementAndGet();
                } finally {
                    finish.countDown();
                }
            });
        }
        start.countDown();
        finish.await();
        executor.shutdown();

        // then
        System.out.println("=== 동시 멱등성 테스트 결과 ===");
        System.out.println("성공: " + successCount.get());
        System.out.println("PAYMENT_ALREADY_PROCESSED: " + alreadyProcessedCount.get());
        System.out.println("기타 실패: " + otherFailCount.get());

        // 정확히 1건만 성공, 나머지는 모두 PAYMENT_ALREADY_PROCESSED 로만 거절되어야 한다.
        // OptimisticLockException·DataIntegrityViolationException 같은 예상 못한 예외가
        // 섞여 들어와도 합격하지 않도록 alreadyProcessed 와 otherFail 을 분리해 검증한다.
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(alreadyProcessedCount.get()).isEqualTo(threadCount - 1);
        assertThat(otherFailCount.get()).isZero();

        // PG도 정확히 1번만 호출 — race로 떨어지면 N번 호출됨
        then(paymentClient).should(times(1)).confirmPayment(any(), any(), any());

        Payment saved = paymentRepository.findByOrderPgOrderId(ctx.pgOrderId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(saved.getApprovedAt()).isNotNull();
    }

    private record ReadyPaymentContext(String pgOrderId, Integer amount) {
    }

    private ReadyPaymentContext setupReadyPayment() {
        Long userId = 1L;
        Long addressId = 1L;
        Long optionId = 1L;

        ProductOption option = productOptionRepository.findById(optionId).orElseThrow();
        option.updateStockQuantity(100);
        productOptionRepository.save(option);

        OrderResponse placed = orderService.placeOrder(userId, new OrderCreateRequest(
                addressId, List.of(new OrderDetailRequest(optionId, 1))
        ));
        return new ReadyPaymentContext(placed.pgOrderId(), placed.finalAmount());
    }
}
