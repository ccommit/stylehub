package ccommit.stylehub.payment.repository;

import ccommit.stylehub.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 * @modified 2026/05/01 by WonJin - feat: findByOrderPgOrderIdWithLock 추가 — 결제 콜백 멱등성을 위한 비관적 락 조회
 *
 * <p>
 * Payment 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderPgOrderId(String pgOrderId);

    /**
     * 결제 승인 시 같은 paymentKey 의 콜백이 동시에 도착해도 1건만 승인되도록 비관적 락으로 조회한다.
     * 2번째 스레드부터는 1번째 스레드의 commit 후 status=DONE 을 보고 validateApprovable 에서 거절된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.order.pgOrderId = :pgOrderId")
    Optional<Payment> findByOrderPgOrderIdWithLock(@Param("pgOrderId") String pgOrderId);
}
