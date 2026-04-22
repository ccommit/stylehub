package ccommit.stylehub.product.service;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.product.dto.request.ProductCreateRequest;
import ccommit.stylehub.product.dto.request.ProductOptionRequest;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductOptionResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.product.port.ProductPort;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.product.repository.ProductQueryRepository;
import ccommit.stylehub.product.repository.ProductRepository;
import ccommit.stylehub.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 * @modified 2026/03/27 by WonJin - feat: 내 스토어 상품 목록 조회 추가
 * @modified 2026/03/27 by WonJin - feat: 비관적 락 재고 차감/복구 메서드 추가
 * @modified 2026/04/01 by WonJin - refactor: ProductViewService를 ProductService로 통합
 * @modified 2026/04/22 by WonJin - refactor: UserPort 의존 제거, 권한 검증은 ProductApplicationService로 이관 (도메인 서비스는 자기 도메인만 알도록 분리)
 *
 * <p>
 * 상품 등록, 재고 관리, 조회를 담당하는 순수 도메인 서비스이다.
 * 스토어 소유권 검증 같은 Application 관심사는 ProductApplicationService에서 처리한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ProductService implements ProductPort {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductQueryRepository productQueryRepository;

    // 카테고리 조합 검증 후 상품과 옵션을 등록한다. 권한 검증은 상위 계층에서 수행된 상태라고 가정한다.
    @Transactional
    public ProductResponse registerProduct(User owner, ProductCreateRequest request) {
        validateCategoryCombination(request.mainCategory(), request.subCategory());

        Product savedProduct = saveProduct(owner, request.name(), request.mainCategory(),
                request.subCategory(), request.description(), request.price(), request.imageUrl());
        List<ProductOption> savedOptions = saveOptions(savedProduct, request.options());

        return ProductResponse.from(savedProduct, savedOptions);
    }

    /**
     * 내 스토어 상품 목록을 커서 기반으로 조회한다.
     */
    @Transactional(readOnly = true)
    public CursorResponse<ProductListResponse> getMyStoreProducts(Long storeId, Long cursor, Integer pageSize) {
        int resolvedSize = resolvePageSize(pageSize);

        List<Product> products = productQueryRepository.findProductsWithCursor(
                cursor, storeId, resolvedSize + 1
        );

        List<ProductListResponse> productList = products.stream()
                .map(ProductListResponse::from)
                .toList();

        return CursorResponse.of(productList, resolvedSize, ProductListResponse::productId);
    }

    // 지정 옵션의 재고 수량을 변경한다.
    @Transactional
    public ProductOptionResponse updateStock(Long optionId, Integer stockQuantity) {
        ProductOption target = productOptionRepository
                .findByIdWithLock(optionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND));

        target.updateStockQuantity(stockQuantity);
        return ProductOptionResponse.from(target);
    }

    // 비관적 락으로 재고를 차감한다. 호출자의 트랜잭션에 참여한다.
    // TODO: 대용량 트래픽 대응 시 Redis DECR 원자적 연산으로 전환 예정
    @Override
    public ProductOption decreaseStockWithLock(Long optionId, int quantity) {
        ProductOption option = productOptionRepository.findByIdWithLock(optionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND));
        option.decreaseStock(quantity);
        return option;
    }

    // 비관적 락으로 재고를 복구한다. 주문 취소 시 사용.
    @Override
    public void increaseStock(Long optionId, int quantity) {
        ProductOption option = productOptionRepository.findByIdWithLock(optionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND));
        option.increaseStock(quantity);
    }

    /**
     * 커서 기반 상품 목록을 조회한다. 스토어, 카테고리 필터링 지원. (비인증 공개 API)
     */
    @Transactional(readOnly = true)
    public CursorResponse<ProductListResponse> getProducts(Long cursor, Long storeId,
                                                           MainCategory mainCategory,
                                                           SubCategory subCategory, Integer pageSize) {
        int resolvedSize = resolvePageSize(pageSize);

        List<Product> products = productQueryRepository.findProductsWithCursor(
                cursor, storeId, mainCategory, subCategory, resolvedSize + 1
        );

        List<ProductListResponse> productList = products.stream()
                .map(ProductListResponse::from)
                .toList();

        return CursorResponse.of(productList, resolvedSize, ProductListResponse::productId);
    }

    /**
     * 상품 상세 정보와 옵션 목록을 조회한다. (비인증 공개 API)
     * Store와 Options를 JOIN FETCH로 한번에 조회한다.
     */
    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long productId) {
        Product product = productRepository.findByIdWithUserAndOptions(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        return ProductResponse.from(product, product.getOptions());
    }

    private int resolvePageSize(Integer size) {
        return (size != null && size > 0) ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
    }

    private Product saveProduct(User user, String name, MainCategory mainCategory,
                                SubCategory subCategory, String description, Integer price, String imageUrl) {
        Product product = Product.create(user, name, mainCategory, subCategory, description, price, imageUrl);
        return productRepository.save(product);
    }

    private List<ProductOption> saveOptions(Product product, List<ProductOptionRequest> optionRequests) {
        List<ProductOption> options = new ArrayList<>(optionRequests.size());
        for (ProductOptionRequest request : optionRequests) {
            options.add(ProductOption.create(
                    product,
                    request.color(),
                    request.size(),
                    request.stockQuantity(),
                    request.maxPointAmount()
            ));
        }
        return productOptionRepository.saveAll(options);
    }

    private void validateCategoryCombination(MainCategory mainCategory, SubCategory subCategory) {
        if (!subCategory.belongsTo(mainCategory)) {
            throw new BusinessException(ErrorCode.INVALID_CATEGORY_COMBINATION);
        }
    }
}
