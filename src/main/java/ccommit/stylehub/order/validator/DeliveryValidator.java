package ccommit.stylehub.order.validator;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.UpdateDeliveryStatusRequest;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderItem;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderItemRepository;
import ccommit.stylehub.user.port.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/04/02
 * @modified 2026/04/16 by WonJin - refactor: DeliveryStatus를 OrderStatus로 통합
 * @modified 2026/04/16 by WonJin - refactor: DeliveryPolicy를 DeliveryValidator로 변경 (검증 역할만 수행하므로)
 * @modified 2026/04/16 by WonJin - refactor: 배송 상태 변경 관련 검증을 모두 validator로 통합 (스토어 소유권, 주문-스토어 매칭, 상태 전이)
 *
 * <p>
 * 배송 상태 변경에 필요한 모든 검증을 담당한다.
 * 1. 스토어 소유권 검증
 * 2. 주문 내 스토어 상품 포함 여부
 * 3. 상태 전이 규칙 (PREPARING → SHIPPING → DELIVERED)
 * </p>
 */
@Component
@RequiredArgsConstructor
public class DeliveryValidator {

    private final UserPort userPort;
    private final OrderItemRepository orderItemRepository;

    /**
     * 배송 상태 변경 요청을 검증한다.
     * 검증 순서: 스토어 소유권 → 주문-스토어 매칭 → 상태 전이 규칙
     */
    public void validate(UpdateDeliveryStatusRequest request, Order order) {
        userPort.validateApprovedStoreOwner(request.userId(), request.storeId());
        validateStoreOrder(request.storeId(), request.orderId());
        validateTransition(order.getOrderStatus(), request.newStatus());
    }

    private void validateStoreOrder(Long storeId, Long orderId) {
        List<OrderItem> items = orderItemRepository.findByOrderIdWithDetails(orderId);
        boolean isStoreOrder = items.stream()
                .anyMatch(item -> item.getProductOption().getProduct().getUser().getUserId().equals(storeId));

        if (!isStoreOrder) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_DELIVERY_ACCESS);
        }
    }

    private void validateTransition(OrderStatus current, OrderStatus next) {
        if (!isValidTransition(current, next)) {
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
