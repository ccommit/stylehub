package ccommit.stylehub.product.repository;

import ccommit.stylehub.support.InnoDbLocks;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.enums.StoreStatus;
import ccommit.stylehub.user.repository.UserRepository;
import ccommit.stylehub.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 재고 차감 UPDATE 의 스토어 승인 조건(EXISTS 서브쿼리)이 스토어 행에 공유 락을 거는지 검증한다.
 * 이 락 때문에 진행 중인 주문이 커밋될 때까지 스토어 정지가 기다려, 정지 확인과 차감 사이에 틈이 생기지 않는다.
 * </p>
 */
@SpringBootTest
class StockDecreaseStoreLockTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WAIT_OBSERVATION = Duration.ofSeconds(3);

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private TransactionTemplate transactionTemplate;
    private InnoDbLocks locks;
    private ExecutorService executor;
    private OrderFixtureFactory.StoreProduct storeProduct;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        locks = new InnoDbLocks(dataSource);
        executor = Executors.newFixedThreadPool(2);
        storeProduct = fixtureFactory.createStoreProduct(10);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        fixtureFactory.deleteByIds(List.of(), List.of(storeProduct.productId()), List.of(storeProduct.storeId()));
    }

    @Test
    @DisplayName("재고 차감 트랜잭션이 커밋되기 전까지 스토어 정지는 스토어 행 공유 락에 막혀 기다린다")
    void storeSuspensionWaitsForInFlightStockDecrease() throws Exception {
        // given
        Long storeId = storeProduct.storeId();
        CountDownLatch decreased = new CountDownLatch(1);
        CountDownLatch releaseOrder = new CountDownLatch(1);

        Future<?> order = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            productOptionRepository.decreaseStockAtomic(storeProduct.optionId(), 1, StoreStatus.APPROVED);
            decreased.countDown();
            await(releaseOrder);
        }));

        List<String> storeRowLocks;
        boolean suspensionWaited;
        boolean suspendedBeforeOrderCommit;
        Future<?> suspension;
        try {
            await(decreased);
            storeRowLocks = locks.grantedRowLockModes("users", storeId);

            // when
            suspension = executor.submit(() -> userService.suspendStore(storeId));
            suspensionWaited = locks.awaitWaitingLock("users", WAIT_OBSERVATION);
            suspendedBeforeOrderCommit = suspension.isDone();
        } finally {
            releaseOrder.countDown();
        }
        order.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        suspension.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // then
        assertThat(storeRowLocks).contains("S,REC_NOT_GAP");
        assertThat(suspensionWaited).isTrue();
        assertThat(suspendedBeforeOrderCommit).isFalse();
        assertThat(userRepository.findById(storeId).orElseThrow().getStoreStatus()).isEqualTo(StoreStatus.SUSPENDED);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
