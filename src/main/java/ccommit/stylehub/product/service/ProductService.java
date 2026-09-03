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
 * @modified 2026/05/03 by WonJin - perf: decreaseStockWithLock 을 SELECT FOR UPDATE 비관적 락에서 단일 atomic UPDATE 로 전환 (락 점유 시간 0 → 동시 주문 처리량 향상)
 
 
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

        List<ProductListResponse> productList = productQueryRepository.findProductsWithCursor(
                cursor, storeId, resolvedSize + 1
        );

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

    /**
     * 단일 atomic UPDATE 로 재고를 차감한다. 호출자의 트랜잭션에 참여한다.
     *
     * <p>이전 구현: SELECT FOR UPDATE 비관적 락 → 차감 → flush 시 UPDATE (쿼리 2번 + 락 점유)
     * <br>현재 구현: UPDATE WHERE stock >= qty 단일 쿼리 (락 점유 시간 0)
     *
     * <p>DB 가 단일 UPDATE 를 atomic 으로 처리하므로 race condition 자체가 발생하지 않는다.
     * WHERE 절의 stock_quantity >= :qty 조건이 음수 재고 방지 역할을 겸한다.
     *
     * <p>차감 후 OrderDetail 생성을 위해 ProductOption 엔티티 1회 조회 (단순 SELECT, 락 없음).
     * 다음 단계 개선 영역: OrderDetail.create 시그니처를 productOptionId + price 로 단순화하면 이 SELECT 도 제거 가능.
     *
     * <p>TODO: 더 큰 트래픽 (100k+ TPS) 대응 시 Redis DECR 원자 연산으로 전환 검토
     */
    @Override
    public ProductOption decreaseStockWithLock(Long optionId, int quantity) {
        int updated = productOptionRepository.decreaseStockAtomic(optionId, quantity);
        if (updated == 0) {
            // 0건 = 옵션이 없거나 재고 부족 — 둘을 구분해서 정확한 에러 코드 반환
            if (!productOptionRepository.existsById(optionId)) {
                throw new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND);
            }
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }
        // OrderDetail FK + getProductPrice() 호출을 위해 1회 조회 (단순 SELECT)
        return productOptionRepository.findById(optionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND));
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
     *
     * 캐시 전략:
     *   커서가 없는 "첫 페이지" 요청을 필터 조합별로 캐시한다.
     *   - 필터 없음(첫 페이지): 전 사용자 동일 응답, 호출 빈도 1위
     *   - by category: 카테고리별 첫 페이지, 전 사용자 동일 응답
     *   - by store: 스토어별 첫 페이지, 해당 스토어 방문자 간 공유
     *   cursor 가 있는 "다음 페이지" 는 스크롤 분포가 분산돼 캐시 효율이 낮아 제외.
     *
     *   sync = true: TTL 만료 순간 cache miss 가 동시에 쏟아지는 thundering herd 를 차단한다.
     *   같은 키로 동시 miss 가 발생하면 한 스레드만 DB 로 가고 나머지는 결과를 기다린다.
     *   TTL 60 초 — 신상품 반영 지연 허용 범위. 1000 users 구간에서 miss 빈도를 절반으로 낮추기 위해 30 → 60 상향.
     *
     *   key 의 null 자리는 '*' sentinel 로 치환한다. SpEL 의 String concatenation 은 null 을 문자열 "null" 로
     *   변환하므로, "필터 없음" 의 의도가 키에서 모호해질 수 있고 디버깅·로그 가독성이 떨어진다.
     *   '*' 는 Long·Enum 어느 타입에도 등장하지 않는 sentinel 이라 충돌 가능성이 없다.
     */
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

    /**
     * 상품 상세 정보와 옵션 목록을 조회한다. (비인증 공개 API)
     * Store와 Options를 JOIN FETCH로 한번에 조회한다.
     *
     * 캐시 전략:
     *   productId 별로 캐시 (모든 사용자에게 동일 응답). TTL 60초.
     *   2,000 users 구간에서 이 경로가 전체 요청의 17 % 를 차지해 DB 부하의 주요 원인이라 캐시 대상으로 편입.
     *   sync = true: 동시 cache miss 에서 한 스레드만 DB 로 가고 나머지는 대기 (thundering herd 차단).
     */
    @Cacheable(
            value = "products:detail",
            key = "#productId",
            sync = true
    )
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
