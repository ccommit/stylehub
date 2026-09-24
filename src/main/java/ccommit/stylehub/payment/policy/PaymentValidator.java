package ccommit.stylehub.payment.policy;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.enums.PaymentStatus;
import ccommit.stylehub.user.enums.UserRole;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 * @modified 2026/04/08 by WonJin - refactor: validateCancel()로 취소 검증 일원화, CancelPolicy 로직 통합
 * @modified 2026/09/17 by WonJin - fix: validateCancelAuthority 추가 — 주문자 본인/관리자만 결제 취소 허용
 * @modified 2026/09/17 by WonJin - fix: 승인 시작은 READY 결제·결제 대기 주문에서만 허용 (만료·취소된 주문 승인 차단)
 * @modified 2026/09/17 by WonJin - fix: 결제 취소 허용 주문 상태를 Order.isCancelableAfterPayment 와 일치시킴 (PG 환불 후 주문 취소 거절로 롤백되던 문제)
 *
 * <p>
 * 결제 승인/취소 전 검증 로직을 담당한다.
 * 상태 검증, 금액 위변조 검증, 취소 요청자 권한, 배송 상태별 취소 가능 여부, 부분 취소 잔액 검증을 수행한다.
 * </p>
 */
@Component
public class PaymentValidator {

    private static final int REFUND_DAYS = 7;

    // IN_PROGRESS는 다른 요청이 이미 PG에 승인을 요청한 상태라 중복 승인으로 본다.
    // 만료·실패된 결제는 주문도 취소됐으므로 이미 처리됨이 아니라 결제할 수 없는 주문으로 응답한다.
    public void validateApprovable(Payment payment) {
        PaymentStatus status = payment.getStatus();
        if (status == PaymentStatus.READY) {
            return;
        }
        if (status == PaymentStatus.EXPIRED || status == PaymentStatus.ABORTED) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }
        throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
    }

    // 주문이 아직 결제 대기(PENDING)인지 검증한다. 만료 처리로 취소된 주문에 PG 승인을 요청하지 않기 위함이다.
    public void validateOrderPayable(Order order) {
        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }
    }

    // 금액 위변조 검증 — DB 저장 금액과 토스 전달 금액 비교
    public void validateAmount(Payment payment, Integer amount) {
        if (!payment.getRequestedAmount().equals(amount)) {
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }

    // 주문자 본인 또는 관리자만 결제를 취소할 수 있다
    public void validateCancelAuthority(Payment payment, Long requesterId, UserRole requesterRole) {
        if (requesterRole == UserRole.ADMIN) {
            return;
        }
        if (!payment.getOrder().getUser().getUserId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_PAYMENT_ACCESS);
        }
    }

    // 결제 취소 검증 — 결제 상태, 주문(배송) 상태, 취소 금액 순서로 검증한다. 승인되지 않은 결제는 주문 상태와 무관하게 취소 대상이 아니다.
    public void validateCancel(Payment payment, Integer cancelAmount) {
        validateCancelable(payment);
        validateDeliveryStatus(payment.getOrder());
        validateCancelAmount(payment, cancelAmount);
    }

    // 배송 전 취소 가능, 배송 중 취소 불가, 배송 완료 후 7일 이내만 환불 가능
    // 허용 상태는 Order.isCancelableAfterPayment 한 곳에만 둬, 여기서 통과한 결제가 주문 취소에서 거절돼 PG·DB 가 어긋나지 않게 한다.
    private void validateDeliveryStatus(Order order) {
        OrderStatus orderStatus = order.getOrderStatus();

        if (orderStatus == OrderStatus.SHIPPING) {
            throw new BusinessException(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
        }
        if (!order.isCancelableAfterPayment()) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        if (orderStatus == OrderStatus.DELIVERED) {
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
