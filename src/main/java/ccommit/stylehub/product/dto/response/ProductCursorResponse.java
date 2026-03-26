package ccommit.stylehub.product.dto.response;

import lombok.Builder;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * 커서 기반 페이징 응답 DTO이다.
 * offset 페이징 대비 대용량 데이터에서 일정한 성능을 보장한다.
 * </p>
 */
@Builder
public record ProductCursorResponse(
        List<ProductListResponse> products,
        Long nextCursor,
        boolean hasNext
) {
    public static ProductCursorResponse of(List<ProductListResponse> products, int size) {
        boolean hasNext = products.size() > size;

        List<ProductListResponse> content = hasNext
                ? products.subList(0, size)
                : products;

        Long nextCursor = hasNext
                ? content.get(content.size() - 1).productId()
                : null;

        return ProductCursorResponse.builder()
                .products(content)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }
}
