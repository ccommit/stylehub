package ccommit.stylehub.order.dto.response;

import ccommit.stylehub.order.entity.OrderDetail;
import lombok.Builder;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/08 by WonJin - refactor: OrderItemResponse → OrderDetailResponse 변경
 *
 * <p>
 * 주문 항목 응답 DTO이다.
 * </p>
 */
@Builder
public record OrderDetailResponse(
        Long orderDetailId,
        Long storeId,
        String storeName,
        Long productOptionId,
        String productName,
        String color,
        String size,
        Integer quantity,
        Integer unitPrice,
        Integer totalPrice
) {
    public static OrderDetailResponse from(OrderDetail detail) {
        return OrderDetailResponse.builder()
                .orderDetailId(detail.getOrderDetailId())
                .storeId(detail.getProductOption().getStoreId())
                .storeName(detail.getProductOption().getStoreName())
                .productOptionId(detail.getProductOption().getProductOptionId())
                .productName(detail.getProductOption().getProductName())
                .color(detail.getProductOption().getColor())
                .size(detail.getProductOption().getSize())
                .quantity(detail.getQuantity())
                .unitPrice(detail.getUnitPrice())
                .totalPrice(detail.getQuantity() * detail.getUnitPrice())
                .build();
    }
}
