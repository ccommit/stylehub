package ccommit.stylehub.order.dto.request;

import ccommit.stylehub.order.enums.OrderStatus;

/**
 * @author WonJin Bae
 * @created 2026/04/16
 *
 * <p>
 * 배송 상태 변경 요청의 서비스 레이어 파라미터를 묶는 DTO이다.
 * 컨트롤러에서 사용자/스토어/주문 식별자와 변경할 상태를 합쳐 서비스로 전달한다.
 * </p>
 */
public record UpdateDeliveryStatusRequest(
        Long userId,
        Long storeId,
        Long orderId,
        OrderStatus newStatus
) {
}
