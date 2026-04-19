package ccommit.stylehub.order.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/08 by WonJin - refactor: OrderItemRequest → OrderDetailRequest 변경
 *
 * <p>
 * 주문 항목 요청 DTO이다.
 * 상품 옵션 ID와 수량을 전달한다.
 * </p>
 */
public record OrderDetailRequest(

        @NotNull(message = "상품 옵션 ID는 필수입니다")
        Long productOptionId,

        @NotNull(message = "수량은 필수입니다")
        @Positive(message = "수량은 1 이상이어야 합니다")
        Integer quantity
) {}
