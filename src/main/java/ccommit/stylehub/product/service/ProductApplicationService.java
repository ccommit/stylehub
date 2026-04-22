package ccommit.stylehub.product.service;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.product.dto.request.ProductCreateRequest;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductOptionResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.port.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author WonJin Bae
 * @created 2026/04/22
 *
 * <p>
 * Product 유스케이스를 오케스트레이션하는 Application 계층 서비스이다.
 * 스토어 소유권 검증(UserPort)과 상품 도메인 로직(ProductService)을 조합해 Controller에 단일 진입점을 제공한다.
 * 권한 검증은 Application 관심사이므로 Domain 계층(ProductService)에서 분리해 여기서 처리한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ProductApplicationService {

    private final UserPort userPort;
    private final ProductService productService;

    @Transactional
    public ProductResponse registerProduct(Long userId, Long storeId, ProductCreateRequest request) {
        User owner = userPort.findApprovedStoreByOwner(userId, storeId);
        return productService.registerProduct(owner, request);
    }

    @Transactional(readOnly = true)
    public CursorResponse<ProductListResponse> getMyStoreProducts(Long userId, Long storeId,
                                                                  Long cursor, Integer pageSize) {
        userPort.validateApprovedStoreOwner(userId, storeId);
        return productService.getMyStoreProducts(storeId, cursor, pageSize);
    }

    @Transactional
    public ProductOptionResponse updateStock(Long userId, Long storeId,
                                             Long optionId, Integer stockQuantity) {
        userPort.validateApprovedStoreOwner(userId, storeId);
        return productService.updateStock(optionId, stockQuantity);
    }

    @Transactional(readOnly = true)
    public CursorResponse<ProductListResponse> getProducts(Long cursor, Long storeId,
                                                           MainCategory mainCategory,
                                                           SubCategory subCategory, Integer pageSize) {
        return productService.getProducts(cursor, storeId, mainCategory, subCategory, pageSize);
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long productId) {
        return productService.getProduct(productId);
    }
}
