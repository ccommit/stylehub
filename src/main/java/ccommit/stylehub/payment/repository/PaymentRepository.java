package ccommit.stylehub.payment.repository;

import ccommit.stylehub.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * Payment 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderPgOrderId(String pgOrderId);
}
