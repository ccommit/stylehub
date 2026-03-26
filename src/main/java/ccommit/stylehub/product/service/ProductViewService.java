package ccommit.stylehub.product.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.product.dto.response.ProductCursorResponse;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.product.repository.ProductQueryRepository;
import ccommit.stylehub.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/26
 * @modified 2026/03/27 by WonJin - feat: 커서 기반 전체 상품 목록 조회 추가
 *
 * <p>
 * 일반 사용자의 상품 조회 비즈니스 로직을 처리한다.
 * 모든 메서드에 읽기 전용 트랜잭션을 적용하여 조회 성능을 최적화한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductViewService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductQueryRepository productQueryRepository;

    /**
     * 커서 기반 상품 목록을 조회한다. 스토어, 카테고리 필터링 지원.
     * QueryDSL 동적 쿼리로 null 파라미터를 정확히 처리한다.
     * offset 페이징 대비 100만건 이상에서도 일정한 성능을 보장한다.
     */
    public ProductCursorResponse getProducts(Long cursor, Long storeId,
                                              MainCategory mainCategory,
                                              SubCategory subCategory, Integer size) {
        int pageSize = (size != null && size > 0) ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        // hasNext 판단을 위해 1건 더 조회
        List<Product> products = productQueryRepository.findProductsWithCursor(
                cursor, storeId, mainCategory, subCategory, pageSize + 1
        );

        List<ProductListResponse> productList = products.stream()
                .map(ProductListResponse::from)
                .toList();

        return ProductCursorResponse.of(productList, pageSize);
    }

    // 상품 상세 정보와 옵션 목록을 조회한다. (비인증 공개 API)
    public ProductResponse getProduct(Long productId) {
        Product product = productRepository.findByIdWithStore(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        List<ProductOption> options = productOptionRepository.findByProductProductId(productId);

        return ProductResponse.from(product, options);
    }
}
