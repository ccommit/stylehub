package ccommit.stylehub.payment.policy;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.enums.PaymentStatus;
import org.springframework.stereotype.Component;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * 결제 승인/취소 전 검증 로직을 담당한다.
 * 상태 검증, 금액 위변조 검증, 부분 취소 잔액 검증을 수행한다.
 * </p>
 */
@Component
public class PaymentValidator {

    // 결제 승인 가능 여부를 검증한다. (READY 또는 IN_PROGRESS만 승인 가능)
    public void validateApprovable(Payment payment) {
        if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
    }

    // 금액 위변조 검증 — DB 저장 금액과 토스 전달 금액 비교
    public void validateAmount(Payment payment, Integer amount) {
        if (!payment.getRequestedAmount().equals(amount)) {
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }

    // 결제 취소 가능 여부를 검증한다. (DONE 또는 PARTIAL_CANCELED만 취소 가능)
    public void validateCancelable(Payment payment) {
        if (payment.getStatus() != PaymentStatus.DONE && payment.getStatus() != PaymentStatus.PARTIAL_CANCELED) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
    }

    // 부분 취소 시 잔액 초과 검증
    public void validateCancelAmount(Payment payment, Integer cancelAmount) {
        if (cancelAmount != null && cancelAmount > payment.getBalanceAmount()) {
            throw new BusinessException(ErrorCode.INVALID_CANCEL_AMOUNT);
        }
    }
}
