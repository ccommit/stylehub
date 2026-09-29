package ccommit.stylehub.payment.repository;

import ccommit.stylehub.payment.entity.PaymentRefundFailure;
import ccommit.stylehub.payment.enums.RefundFailureStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 자동 환불 실패 기록을 조회·저장한다.
 * </p>
 */
public interface PaymentRefundFailureRepository extends JpaRepository<PaymentRefundFailure, Long> {

    Optional<PaymentRefundFailure> findByPaymentKey(String paymentKey);

    List<PaymentRefundFailure> findByStatusOrderByRefundFailureIdAsc(RefundFailureStatus status, Pageable pageable);
}
