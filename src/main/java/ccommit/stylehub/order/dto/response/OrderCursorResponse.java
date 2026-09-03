package ccommit.stylehub.order.dto.response;

import lombok.Builder;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * 주문 내역 커서 기반 페이징 응답 DTO이다.
 * </p>
 */
@Builder
public record OrderCursorResponse(
        List<OrderListResponse> orders,
        Long nextCursor,
        boolean hasNext
) {
    public static OrderCursorResponse of(List<OrderListResponse> orders, int size) {
        boolean hasNext = orders.size() > size;

        List<OrderListResponse> content = hasNext
                ? orders.subList(0, size)
                : orders;

        Long nextCursor = hasNext
                ? content.get(content.size() - 1).orderId()
                : null;

        return OrderCursorResponse.builder()
                .orders(content)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }
}
