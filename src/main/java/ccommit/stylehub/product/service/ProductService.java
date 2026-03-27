package ccommit.stylehub.product.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.product.dto.request.ProductCreateRequest;
import ccommit.stylehub.product.dto.request.ProductOptionRequest;
import ccommit.stylehub.product.dto.response.ProductCursorResponse;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductOptionResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.product.repository.ProductQueryRepository;
import ccommit.stylehub.product.repository.ProductRepository;
import ccommit.stylehub.store.entity.Store;
import ccommit.stylehub.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 * @modified 2026/03/27 by WonJin - refactor: 조회 로직을 ProductViewService로 분리
 * @modified 2026/03/27 by WonJin - feat: 내 스토어 상품 목록 조회 추가
 * @modified 2026/03/27 by WonJin - feat: 비관적 락 재고 차감/복구 메서드 추가
 *
 * <p>
 * STORE 역할 사용자의 상품 등록, 내 스토어 상품 조회, 재고 관리 비즈니스 로직을 처리한다.
 * 스토어 검증은 StoreService를 통해 처리하여 도메인 간 결합을 방지한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductQueryRepository productQueryRepository;
    private final StoreService storeService;
    private final TransactionTemplate transactionTemplate;

    // 스토어 소유권, 승인 상태, 카테고리 조합을 검증한 뒤 상품과 옵션을 등록한다.
    public ProductResponse registerProduct(Long userId, Long storeId, ProductCreateRequest request) {
        validateCategoryCombination(request.mainCategory(), request.subCategory());

        record RegisterResult(Product product, List<ProductOption> options) {}

        RegisterResult result = Objects.requireNonNull(
                transactionTemplate.execute(status -> {
                    Store store = storeService.findApprovedStoreByOwner(userId, storeId);
                    Product savedProduct = saveProduct(store, request.name(), request.mainCategory(),
                            request.subCategory(), request.description(), request.price(), request.imageUrl());
                    List<ProductOption> savedOptions = saveOptions(savedProduct, request.options());
                    return new RegisterResult(savedProduct, savedOptions);
                })
        );

        return ProductResponse.from(result.product(), result.options());
    }

    // 본인 스토어의 상품 목록을 커서 기반으로 조회한다.
    public ProductCursorResponse getMyStoreProducts(Long userId, Long storeId, Long cursor, Integer size) {
        storeService.findApprovedStoreByOwner(userId, storeId);

        int pageSize = (size != null && size > 0) ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        List<Product> products = productQueryRepository.findProductsWithCursor(
                cursor, storeId, null, null, pageSize + 1
        );

        List<ProductListResponse> productList = products.stream()
                .map(ProductListResponse::from)
                .toList();

        return ProductCursorResponse.of(productList, pageSize);
    }

    // 스토어 소유권, 승인 상태를 검증하고 해당 상품의 옵션 재고를 변경한다.
    public ProductOptionResponse updateStock(Long userId, Long storeId, Long productId, Long optionId, Integer stockQuantity) {
        ProductOption option = Objects.requireNonNull(
                transactionTemplate.execute(status -> {
                    storeService.findApprovedStoreByOwner(userId, storeId);

                    ProductOption target = productOptionRepository
                            .findByProductOptionIdAndProductProductId(optionId, productId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND));

                    target.updateStockQuantity(stockQuantity);
                    return target;
                })
        );

        return ProductOptionResponse.from(option);
    }

    // 비관적 락으로 재고를 차감한다. 호출자의 트랜잭션에 참여한다.
    // TODO: 대용량 트래픽 대응 시 Redis DECR 원자적 연산으로 전환 예정
    public ProductOption decreaseStockWithLock(Long optionId, int quantity) {
        ProductOption option = productOptionRepository.findByIdWithLock(optionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND));
        option.decreaseStock(quantity);
        return option;
    }

    // 재고를 복구한다. 주문 취소 시 사용.
    public void increaseStock(Long optionId, int quantity) {
        ProductOption option = productOptionRepository.findById(optionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND));
        option.increaseStock(quantity);
    }

    private Product saveProduct(Store store, String name, MainCategory mainCategory,
                                SubCategory subCategory, String description, Integer price, String imageUrl) {
        Product product = Product.create(store, name, mainCategory, subCategory, description, price, imageUrl);
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
