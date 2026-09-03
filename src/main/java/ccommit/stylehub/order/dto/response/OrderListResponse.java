package ccommit.stylehub.order.dto.response;

import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * 주문 내역 목록용 경량 응답 DTO이다.
 * 주문 항목을 포함하지 않아 N+1 문제를 방지한다.
 * </p>
 */
@Builder
public record OrderListResponse(
        Long orderId,
        String pgOrderId,
        OrderStatus orderStatus,
        Integer totalAmount,
        Integer discountAmount,
        Integer usedPoint,
        Integer earnedPoint,
        LocalDateTime createdAt
) {
    public static OrderListResponse from(Order order, Integer totalAmount) {
        return OrderListResponse.builder()
                .orderId(order.getOrderId())
                .pgOrderId(order.getPgOrderId())
                .orderStatus(order.getOrderStatus())
                .totalAmount(totalAmount)
                .discountAmount(order.getDiscountAmount())
                .usedPoint(order.getUsedPoint())
                .earnedPoint(order.getEarnedPoint())
                .createdAt(order.getCreatedAt())
                .build();
    }
}
