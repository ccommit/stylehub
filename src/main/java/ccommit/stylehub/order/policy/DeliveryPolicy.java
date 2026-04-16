package ccommit.stylehub.order.policy;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import org.springframework.stereotype.Component;

/**
 * @author WonJin Bae
 * @created 2026/04/02
 * @modified 2026/04/16 by WonJin - refactor: DeliveryStatus를 OrderStatus로 통합
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
     * - PREPARING → SHIPPING → DELIVERED 순서만 허용
     */
    public void validateUpdateDeliveryStatus(Order order, OrderStatus newStatus) {
        OrderStatus current = order.getOrderStatus();

        if (!isValidTransition(current, newStatus)) {
            throw new BusinessException(ErrorCode.INVALID_DELIVERY_STATUS);
        }
    }

    private boolean isValidTransition(OrderStatus current, OrderStatus next) {
        if (current == OrderStatus.PREPARING) {
            return next == OrderStatus.SHIPPING;
        }
        if (current == OrderStatus.SHIPPING) {
            return next == OrderStatus.DELIVERED;
        }
        return false;
    }
}
