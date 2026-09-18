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
 * @modified 2026/09/17 by WonJin - feat: findByOrderOrderIdWithLock 추가 — 만료 직전 PG 대조 결과를 반영할 때 승인 경로와 직렬화
 *
 * <p>
 * Payment 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderPgOrderId(String pgOrderId);

    // 주문 만료 처리에서 결제 상태를 대조할 때 사용한다.
    Optional<Payment> findByOrderOrderId(Long orderId);

    // 같은 paymentKey의 콜백이 동시에 와도 1건만 승인되도록 잠근다. 뒤 요청은 앞 요청 커밋 후 상태를 보고 validateApprovable에서 거절된다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.order.pgOrderId = :pgOrderId")
    Optional<Payment> findByOrderPgOrderIdWithLock(@Param("pgOrderId") String pgOrderId);

    // 만료 직전 PG 대조 결과를 반영할 때, 같은 결제에 대한 승인·실패 콜백과 순서를 맞추기 위해 락을 잡는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.order.orderId = :orderId")
    Optional<Payment> findByOrderOrderIdWithLock(@Param("orderId") Long orderId);
}
