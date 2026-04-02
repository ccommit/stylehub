package ccommit.stylehub.order.policy;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.DeliveryStatus;
import ccommit.stylehub.order.enums.OrderStatus;
import org.springframework.stereotype.Component;

/**
 * @author WonJin Bae
 * @created 2026/04/02
 *
 * <p>
 * 배송 상태 전이 규칙을 담당한다.
 * PREPARING → SHIPPING → DELIVERED 순서만 허용, 역방향 불가.
 * 정책 변경 시 이 클래스만 수정하면 된다.
 * </p>
 */
@Component
public class DeliveryPolicy {

    /**
     * 배송 상태 변경이 가능한지 검증한다.
     * - 결제 완료(PAID) 상태에서만 변경 가능
     * - PREPARING → SHIPPING → DELIVERED 순서만 허용
     */
    public void validateTransition(Order order, DeliveryStatus newStatus) {
        if (order.getOrderStatus() != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        DeliveryStatus current = order.getDeliveryStatus();

        if (!isValidTransition(current, newStatus)) {
            throw new BusinessException(ErrorCode.INVALID_DELIVERY_STATUS);
        }
    }

    private boolean isValidTransition(DeliveryStatus current, DeliveryStatus next) {
        if (current == null || current == DeliveryStatus.PREPARING) {
            return next == DeliveryStatus.SHIPPING;
        }
        if (current == DeliveryStatus.SHIPPING) {
            return next == DeliveryStatus.DELIVERED;
        }
        return false;
    }
}
