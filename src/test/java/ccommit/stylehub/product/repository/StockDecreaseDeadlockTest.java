package ccommit.stylehub.product.repository;

import ccommit.stylehub.support.InnoDbLocks;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.enums.StoreStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 재고 차감 UPDATE 가 InnoDB 에서 옵션 순서에 따라 데드락을 내는지 검증한다.
 * 주문 생성이 옵션을 optionId 오름차순으로 차감하는 이유(OrderService.mergeAndSort)를 실제 MySQL 로 보여준다.
 * </p>
 */
@SpringBootTest
class StockDecreaseDeadlockTest {

    private static final int MYSQL_DEADLOCK_ERROR = 1213;
    private static final int INITIAL_STOCK = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private TransactionTemplate transactionTemplate;
    private InnoDbLocks locks;
    private ExecutorService executor;

    private Long storeId;
    private final List<Long> productIds = new ArrayList<>();
    private Long optionA;
    private Long optionB;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        locks = new InnoDbLocks(dataSource);
        executor = Executors.newFixedThreadPool(2);

        OrderFixtureFactory.StoreProduct first = fixtureFactory.createStoreProduct(INITIAL_STOCK);
        OrderFixtureFactory.StoreProduct second = fixtureFactory.addProduct(first.storeId(), INITIAL_STOCK);
        storeId = first.storeId();
        productIds.add(first.productId());
        productIds.add(second.productId());
        optionA = first.optionId();
        optionB = second.optionId();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        fixtureFactory.deleteByIds(List.of(), productIds, List.of(storeId));
    }

    @Test
    @DisplayName("두 트랜잭션이 옵션 두 개를 서로 반대 순서로 차감하면 InnoDB 가 데드락(1213)으로 한쪽을 롤백한다")
    void reverseOrderDeadlocks() throws InterruptedException {
        // given
        CyclicBarrier bothHoldFirstRow = new CyclicBarrier(2);

        // when
        Future<?> forward = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            decrease(optionA);
            await(bothHoldFirstRow);
            decrease(optionB);
        }));
        Future<?> backward = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            decrease(optionB);
            await(bothHoldFirstRow);
            decrease(optionA);
        }));
        List<Throwable> failures = failuresOf(forward, backward);

        // then
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(mysqlErrorCode(failures.get(0))).isEqualTo(MYSQL_DEADLOCK_ERROR);

        // 살아남은 쪽만 두 옵션을 1개씩 차감했고, 롤백된 쪽의 차감은 남지 않는다
        assertThat(stockOf(optionA)).isEqualTo(INITIAL_STOCK - 1);
        assertThat(stockOf(optionB)).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    @DisplayName("같은 순서로 차감하면 나중 트랜잭션이 첫 행에서 기다렸다가 둘 다 커밋된다")
    void sameOrderWaitsAndCommits() throws InterruptedException {
        // given
        CountDownLatch firstHoldsA = new CountDownLatch(1);
        boolean[] secondWaited = new boolean[1];

        // when
        Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            decrease(optionA);
            firstHoldsA.countDown();
            secondWaited[0] = locks.awaitWaitingLock("products_options", TIMEOUT);
            decrease(optionB);
        }));
        Future<?> second = executor.submit(() -> {
            await(firstHoldsA);
            transactionTemplate.executeWithoutResult(status -> {
                decrease(optionA);
                decrease(optionB);
            });
        });
        List<Throwable> failures = failuresOf(first, second);

        // then
        assertThat(failures).isEmpty();
        assertThat(secondWaited[0]).isTrue();
        assertThat(stockOf(optionA)).isEqualTo(INITIAL_STOCK - 2);
        assertThat(stockOf(optionB)).isEqualTo(INITIAL_STOCK - 2);
    }

    private void decrease(Long optionId) {
        int updated = productOptionRepository.decreaseStockAtomic(optionId, 1, StoreStatus.APPROVED);
        assertThat(updated).isEqualTo(1);
    }

    private int stockOf(Long optionId) {
        return productOptionRepository.findOptionStock(optionId).stockQuantity();
    }

    private List<Throwable> failuresOf(Future<?>... futures) throws InterruptedException {
        List<Throwable> failures = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            } catch (TimeoutException e) {
                failures.add(e);
            }
        }
        return failures;
    }

    private static Integer mysqlErrorCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getErrorCode();
            }
        }
        return null;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
