package ccommit.stylehub.order.validator;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.UpdateDeliveryStatusRequest;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderDetail;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderDetailRepository;
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
 * @modified 2026/09/17 by WonJin - fix: PAID → PREPARING 전이 추가, 주문의 모든 상품이 요청 스토어 소유일 때만 허용, 레거시 OrderItem 대신 OrderDetail 조회
 *
 * <p>
 * 배송 상태 변경 시 스토어 소유권, 주문 상품의 스토어 소속, 상태 전이 규칙(PAID → PREPARING → SHIPPING → DELIVERED)을 검증한다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class DeliveryValidator {

    private final UserPort userPort;
    private final OrderDetailRepository orderDetailRepository;

    public void validate(UpdateDeliveryStatusRequest request, Order order) {
        userPort.validateApprovedStoreOwner(request.userId(), request.storeId());
        validateStoreOrder(request.storeId(), request.orderId());
        validateTransition(order.getOrderStatus(), request.newStatus());
    }

    // 배송 상태는 주문 단위 필드라, 한 스토어가 바꾸면 다른 스토어 상품의 배송 상태와 취소·환불 가능 여부까지 바뀐다.
    // 스토어별 배송 단위가 생기기 전까지 여러 스토어 상품이 섞인 주문은 스토어가 배송 상태를 바꿀 수 없다.
    private void validateStoreOrder(Long storeId, Long orderId) {
        List<OrderDetail> details = orderDetailRepository.findByOrderIdWithDetails(orderId);
        boolean ownsAllItems = !details.isEmpty() && details.stream()
                .allMatch(detail -> detail.getProductOption().getStoreId().equals(storeId));

        if (!ownsAllItems) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_DELIVERY_ACCESS);
        }
    }

    private void validateTransition(OrderStatus current, OrderStatus next) {
        if (!isValidTransition(current, next)) {
            throw new BusinessException(ErrorCode.INVALID_DELIVERY_STATUS);
        }
    }

    // 결제 완료(PAID)된 주문은 스토어가 주문을 확인해 배송 준비(PREPARING)로 넘긴다.
    private boolean isValidTransition(OrderStatus current, OrderStatus next) {
        if (current == OrderStatus.PAID) {
            return next == OrderStatus.PREPARING;
        }
        if (current == OrderStatus.PREPARING) {
            return next == OrderStatus.SHIPPING;
        }
        if (current == OrderStatus.SHIPPING) {
            return next == OrderStatus.DELIVERED;
        }
        return false;
    }
}
