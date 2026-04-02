package ccommit.stylehub.product.dto.response;

import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import lombok.Builder;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * 상품 목록 조회용 경량 응답 DTO이다.
 * 옵션을 포함하지 않아 N+1 문제를 원천 차단한다.
 * </p>
 */
@Builder
public record ProductListResponse(
        Long productId,
        Long storeId,
        String storeName,
        String name,
        MainCategory mainCategory,
        SubCategory subCategory,
        Integer price,
        String imageUrl,
        Integer likeCount
) {
    public static ProductListResponse from(Product product) {
        return ProductListResponse.builder()
                .productId(product.getProductId())
                .storeId(product.getStore().getStoreId())
                .storeName(product.getStore().getName())
                .name(product.getName())
                .mainCategory(product.getMainCategory())
                .subCategory(product.getSubCategory())
                .price(product.getPrice())
                .imageUrl(product.getImageUrl())
                .likeCount(product.getLikeCount())
                .build();
    }
}
