package ccommit.stylehub.product.dto.request;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * 상품 옵션(색상, 사이즈, 재고, 포인트)을 전달하는 요청 DTO이다.
 * </p>
 */
public record ProductOptionRequest(

        @Size(max = 20, message = "색상은 20자 이내여야 합니다")
        String color,

        @Size(max = 10, message = "사이즈는 10자 이내여야 합니다")
        String size,

        @PositiveOrZero(message = "재고 수량은 0 이상이어야 합니다")
        Integer stockQuantity,

        @PositiveOrZero(message = "최대 포인트 금액은 0 이상이어야 합니다")
        Integer maxPointAmount
) {}
