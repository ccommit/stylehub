package ccommit.stylehub.product.dto.response;

import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * 상품 정보와 옵션 목록을 클라이언트에 반환하는 응답 DTO이다.
 * </p>
 */
@Builder
public record ProductResponse(
        Long productId,
        String name,
        MainCategory mainCategory,
        SubCategory subCategory,
        String description,
        Integer price,
        String imageUrl,
        LocalDateTime createdAt,
        List<ProductOptionResponse> options
) {
    public static ProductResponse from(Product product, List<ProductOption> options) {
        return ProductResponse.builder()
                .productId(product.getProductId())
                .name(product.getName())
                .mainCategory(product.getMainCategory())
                .subCategory(product.getSubCategory())
                .description(product.getDescription())
                .price(product.getPrice())
                .imageUrl(product.getImageUrl())
                .createdAt(product.getCreatedAt())
                .options(options.stream()
                        .map(ProductOptionResponse::from)
                        .toList())
                .build();
    }
}
