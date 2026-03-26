package ccommit.stylehub.order.dto.response;

import ccommit.stylehub.order.entity.OrderItem;
import lombok.Builder;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * 주문 항목 응답 DTO이다.
 * </p>
 */
@Builder
public record OrderItemResponse(
        Long orderItemId,
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
    public static OrderItemResponse from(OrderItem item) {
        return OrderItemResponse.builder()
                .orderItemId(item.getOrderItemId())
                .storeId(item.getProductOption().getProduct().getStore().getStoreId())
                .storeName(item.getProductOption().getProduct().getStore().getName())
                .productOptionId(item.getProductOption().getProductOptionId())
                .productName(item.getProductOption().getProduct().getName())
                .color(item.getProductOption().getColor())
                .size(item.getProductOption().getSize())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .totalPrice(item.getQuantity() * item.getUnitPrice())
                .build();
    }
}
