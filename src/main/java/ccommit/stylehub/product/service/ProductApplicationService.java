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
 * @modified 2026/09/17 by WonJin - fix: updateStock 이 storeId·productId 를 도메인 서비스로 넘겨 옵션 소속까지 검증 (IDOR 차단)
 * @modified 2026/09/17 by WonJin - fix: 캐시 조회 유스케이스의 바깥 트랜잭션 제거(캐시 적중 시 커넥션 미사용), 페이지 크기를 먼저 정규화해 캐시 키 통일
 * @modified 2026/09/24 by WonJin - refactor: 스토어 유스케이스가 storeId 를 받지 않고 세션 스토어(userId)만 다룸
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
    public ProductResponse registerProduct(Long userId, ProductCreateRequest request) {
        User owner = userPort.findApprovedStore(userId);
        return productService.registerProduct(owner, request);
    }

    @Transactional(readOnly = true)
    public CursorResponse<ProductListResponse> getMyStoreProducts(Long userId, Long cursor, Integer pageSize) {
        userPort.validateApprovedStore(userId);
        return productService.getMyStoreProducts(userId, cursor, ProductPageSizePolicy.resolve(pageSize));
    }

    // 경로의 optionId 는 다른 스토어 것일 수 있어, 옵션 소속은 ProductService 가 세션 스토어 기준으로 다시 확인한다.
    @Transactional
    public ProductOptionResponse updateStock(Long userId, Long productId, Long optionId, Integer stockQuantity) {
        userPort.validateApprovedStore(userId);
        return productService.updateStock(userId, productId, optionId, stockQuantity);
    }

    // 여기서 트랜잭션을 열면 캐시 적중 요청도 커넥션을 먼저 가져오므로, 트랜잭션은 캐시 안쪽인 ProductService에만 둔다.
    // 원시 페이지 크기가 그대로 캐시 키에 들어가 같은 결과가 다른 키로 나뉘지 않게 여기서 먼저 정규화한다.
    public CursorResponse<ProductListResponse> getProducts(Long cursor, Long storeId,
                                                           MainCategory mainCategory,
                                                           SubCategory subCategory, Integer pageSize) {
        return productService.getProducts(cursor, storeId, mainCategory, subCategory,
                ProductPageSizePolicy.resolve(pageSize));
    }

    // 공개 상품 상세 조회. getProducts 와 같은 이유로 트랜잭션을 걸지 않는다.
    public ProductResponse getProduct(Long productId) {
        return productService.getProduct(productId);
    }
}
