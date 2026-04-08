package ccommit.stylehub.order.dto.response;

import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/08 by WonJin - refactor: OrderItemResponse → OrderDetailResponse 변경
 *
 * <p>
 * 주문 정보와 주문 항목 목록을 클라이언트에 반환하는 응답 DTO이다.
 * </p>
 */
@Builder
public record OrderResponse(
        Long orderId,
        String pgOrderId,
        OrderStatus orderStatus,
        List<OrderDetailResponse> details,
        Integer totalAmount,
        Integer discountAmount,
        Integer usedPoint,
        Integer earnedPoint,
        Integer finalAmount,
        LocalDateTime createdAt
) {
    public static OrderResponse from(Order order, List<OrderDetailResponse> detailResponses,
                                      int totalAmount, int finalAmount) {
        return OrderResponse.builder()
                .orderId(order.getOrderId())
                .pgOrderId(order.getPgOrderId())
                .orderStatus(order.getOrderStatus())
                .details(detailResponses)
                .totalAmount(totalAmount)
                .discountAmount(order.getDiscountAmount())
                .usedPoint(order.getUsedPoint())
                .earnedPoint(order.getEarnedPoint())
                .finalAmount(finalAmount)
                .createdAt(order.getCreatedAt())
                .build();
    }
}
