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
import ccommit.stylehub.user.enums.StoreStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
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
 * @modified 2026/05/01 by WonJin - refactor: @Cacheable 키 null 자리를 '*' sentinel 로 치환 (SpEL String concatenation 의 null → "null" 문자열 변환 방지
 * @modified 2026/05/03 by WonJin - perf: decreaseStockWithLock 을 SELECT FOR UPDATE 비관적 락에서 단일 atomic UPDATE 로 전환 (쿼리 2번 → 1번, 락을 쥐고 지나는 구간 단축)
 * @modified 2026/09/17 by WonJin - fix: 재고 복구를 원자 UPDATE 로 전환 — 취소 트랜잭션이 먼저 읽어 둔 재고 값으로 동시 차감을 덮어쓰던 문제 해결
 * @modified 2026/09/17 by WonJin - fix: updateStock 옵션 소속(상품·스토어) 검증으로 IDOR 차단, 정지·미승인 스토어 상품의 상세 노출·재고 차감 차단
 
 
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

    @Transactional(readOnly = true)
    public CursorResponse<ProductListResponse> getMyStoreProducts(Long storeId, Long cursor, Integer pageSize) {
        int resolvedSize = resolvePageSize(pageSize);

        List<ProductListResponse> productList = productQueryRepository.findProductsWithCursor(
                cursor, storeId, resolvedSize + 1
        );

        return CursorResponse.of(productList, resolvedSize, ProductListResponse::productId);
    }

    // 소속이 다르면 403 대신 없는 옵션과 같은 404로 응답한다. 403은 순번 optionId로 다른 스토어 옵션의 존재를 알려 준다.
    @Transactional
    public ProductOptionResponse updateStock(Long storeId, Long productId, Long optionId, Integer stockQuantity) {
        ProductOption target = productOptionRepository
                .findByIdWithLock(optionId)
                .filter(option -> option.isOwnedBy(storeId, productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND));

        target.updateStockQuantity(stockQuantity);
        return ProductOptionResponse.from(target);
    }

    // 명시적 락은 없지만 차감 UPDATE의 행 배타 락이 호출자 트랜잭션 커밋까지 유지되므로 동일 행 경합 한계는 비관적 락과 같다.
    // TODO: 더 큰 트래픽(100k+ TPS) 대응 시 Redis DECR 원자 연산으로 전환 검토
    @Override
    public ProductOption decreaseStockWithLock(Long optionId, int quantity) {
        int updated = productOptionRepository.decreaseStockAtomic(optionId, quantity, StoreStatus.APPROVED);
        if (updated == 0) {
            throw new BusinessException(resolveDecreaseFailure(optionId));
        }
        // OrderDetail FK + getProductPrice() 호출을 위해 1회 조회 (단순 SELECT)
        return productOptionRepository.findById(optionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND));
    }

    // 판매 중지면 재고와 무관하게 주문할 수 없으므로 재고 부족보다 먼저 알린다.
    // UPDATE와 별개 조회라 그 사이 상태가 바뀌면 원인이 달리 보고될 수 있지만, 차감은 이미 거절돼 정합성엔 영향이 없다.
    private ErrorCode resolveDecreaseFailure(Long optionId) {
        return productOptionRepository.findByIdWithProductAndStore(optionId)
                .map(option -> option.getProduct().isOnSale()
                        ? ErrorCode.INSUFFICIENT_STOCK
                        : ErrorCode.PRODUCT_NOT_ON_SALE)
                .orElse(ErrorCode.PRODUCT_OPTION_NOT_FOUND);
    }

    // 취소 트랜잭션이 먼저 올려 둔 옵션 엔티티 값에 더하면 그 사이 커밋된 차감을 덮어쓰므로, DB 현재 값에 더하는 UPDATE로 복구한다.
    @Override
    public void increaseStock(Long optionId, int quantity) {
        if (productOptionRepository.increaseStockAtomic(optionId, quantity) == 0) {
            throw new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND);
        }
    }

    // 다음 페이지는 요청이 분산돼 효율이 낮아 첫 페이지만 필터 조합별로 캐시한다. TTL 60초는 신상품 반영 지연 허용 범위다.
    // sync = true로 동시 miss 때 한 스레드만 DB로 간다. 키의 null은 SpEL이 "null"로 바꾸므로 '*'로 치환한다.
    @Cacheable(
            value = "products:firstPage",
            key = "'size=' + (#pageSize ?: 20) " +
                  "+ '|store=' + (#storeId ?: '*') " +
                  "+ '|main=' + (#mainCategory ?: '*') " +
                  "+ '|sub=' + (#subCategory ?: '*')",
            condition = "#cursor == null",
            sync = true
    )
    @Transactional(readOnly = true)
    public CursorResponse<ProductListResponse> getProducts(Long cursor, Long storeId,
                                                           MainCategory mainCategory,
                                                           SubCategory subCategory, Integer pageSize) {
        int resolvedSize = resolvePageSize(pageSize);

        List<ProductListResponse> productList = productQueryRepository.findProductsWithCursor(
                cursor, storeId, mainCategory, subCategory, resolvedSize + 1
        );

        return CursorResponse.of(productList, resolvedSize, ProductListResponse::productId);
    }

    // sync = true로 동시 miss 때 한 스레드만 DB로 가고 나머지는 기다린다.
    @Cacheable(
            value = "products:detail",
            key = "#productId",
            sync = true
    )
    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long productId) {
        Product product = productRepository.findByIdWithUserAndOptions(productId, StoreStatus.APPROVED)
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
