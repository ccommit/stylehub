package ccommit.stylehub.product.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * 옵션별 재고 수량 변경 요청 DTO이다.
 * </p>
 */
public record StockUpdateRequest(

        @NotNull(message = "재고 수량은 필수입니다")
        @PositiveOrZero(message = "재고 수량은 0 이상이어야 합니다")
        Integer stockQuantity
) {}
