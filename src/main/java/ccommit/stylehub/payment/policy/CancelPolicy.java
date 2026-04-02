package ccommit.stylehub.payment.policy;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.DeliveryStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * 결제 취소/환불 가능 여부를 판단하는 정책 클래스이다.
 * 배송 상태와 환불 기간에 따라 취소 가능 여부를 결정한다.
 * 정책 변경 시 이 클래스만 수정하면 된다.
 * </p>
 */
@Component
public class CancelPolicy {

    private static final int REFUND_DAYS = 7;

    /**
     * 취소/환불 가능 여부를 검증한다.
     * - 배송 전(PREPARING, null): 취소 가능
     * - 배송 중(SHIPPING): 취소 불가
     * - 배송 완료(DELIVERED): 7일 이내만 환불 가능
     */
    public void validate(Order order) {
        DeliveryStatus deliveryStatus = order.getDeliveryStatus();

        if (deliveryStatus == DeliveryStatus.SHIPPING) {
            throw new BusinessException(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
        }

        if (deliveryStatus == DeliveryStatus.DELIVERED) {
            validateRefundPeriod(order);
        }
    }

    private void validateRefundPeriod(Order order) {
        LocalDateTime refundDeadline = order.getUpdatedAt().plusDays(REFUND_DAYS);
        if (LocalDateTime.now().isAfter(refundDeadline)) {
            throw new BusinessException(ErrorCode.REFUND_PERIOD_EXPIRED);
        }
    }
}
