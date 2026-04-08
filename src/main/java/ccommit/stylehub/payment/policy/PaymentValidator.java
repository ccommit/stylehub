package ccommit.stylehub.payment.policy;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.DeliveryStatus;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.enums.PaymentStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 * @modified 2026/04/08 by WonJin - refactor: validateCancel()로 취소 검증 일원화, CancelPolicy 로직 통합
 *
 * <p>
 * 결제 승인/취소 전 검증 로직을 담당한다.
 * 상태 검증, 금액 위변조 검증, 배송 상태별 취소 가능 여부, 부분 취소 잔액 검증을 수행한다.
 * </p>
 */
@Component
public class PaymentValidator {

    private static final int REFUND_DAYS = 7;

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

    // 결제 취소 검증 — 배송 상태, 결제 상태, 취소 금액을 일괄 검증한다.
    public void validateCancel(Payment payment, Integer cancelAmount) {
        validateDeliveryStatus(payment.getOrder());
        validateCancelable(payment);
        validateCancelAmount(payment, cancelAmount);
    }

    /**
     * 배송 상태별 취소/환불 가능 여부를 검증한다.
     * - 배송 전(PREPARING, null): 취소 가능
     * - 배송 중(SHIPPING): 취소 불가
     * - 배송 완료(DELIVERED): 7일 이내만 환불 가능
     */
    private void validateDeliveryStatus(Order order) {
        DeliveryStatus deliveryStatus = order.getDeliveryStatus();

        if (deliveryStatus == DeliveryStatus.SHIPPING) {
            throw new BusinessException(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
        }

        if (deliveryStatus == DeliveryStatus.DELIVERED) {
            LocalDateTime refundDeadline = order.getUpdatedAt().plusDays(REFUND_DAYS);
            if (LocalDateTime.now().isAfter(refundDeadline)) {
                throw new BusinessException(ErrorCode.REFUND_PERIOD_EXPIRED);
            }
        }
    }

    // 결제 취소 가능 여부를 검증한다. (DONE 또는 PARTIAL_CANCELED만 취소 가능)
    private void validateCancelable(Payment payment) {
        if (payment.getStatus() != PaymentStatus.DONE && payment.getStatus() != PaymentStatus.PARTIAL_CANCELED) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
    }

    // 부분 취소 시 잔액 초과 검증
    private void validateCancelAmount(Payment payment, Integer cancelAmount) {
        if (cancelAmount != null && cancelAmount > payment.getBalanceAmount()) {
            throw new BusinessException(ErrorCode.INVALID_CANCEL_AMOUNT);
        }
    }
}
