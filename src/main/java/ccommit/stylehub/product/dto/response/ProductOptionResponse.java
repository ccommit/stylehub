package ccommit.stylehub.product.dto.response;

import ccommit.stylehub.product.entity.ProductOption;
import lombok.Builder;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * 상품 옵션 정보를 클라이언트에 반환하는 응답 DTO이다.
 * </p>
 */
@Builder
public record ProductOptionResponse(
        Long productOptionId,
        String color,
        String size,
        Integer stockQuantity,
        Integer maxPointAmount
) {
    public static ProductOptionResponse from(ProductOption option) {
        return ProductOptionResponse.builder()
                .productOptionId(option.getProductOptionId())
                .color(option.getColor())
                .size(option.getSize())
                .stockQuantity(option.getStockQuantity())
                .maxPointAmount(option.getMaxPointAmount())
                .build();
    }
}
