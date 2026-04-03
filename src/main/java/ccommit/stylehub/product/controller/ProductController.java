package ccommit.stylehub.product.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.product.dto.request.ProductCreateRequest;
import ccommit.stylehub.product.dto.request.StockUpdateRequest;
import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductOptionResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.product.service.ProductService;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 * @modified 2026/03/27 by WonJin - feat: 커서 기반 전체 상품 목록 조회 API 추가
 * @modified 2026/04/01 by WonJin - refactor: ProductViewController를 ProductController로 통합
 * @modified 2026/04/03 by WonJin - feat: 상품 찜하기/취소 API 추가
 *
 * <p>
 * 상품 관련 API를 제공한다.
 * 공개 API(목록/단건 조회)는 인증 불필요, 스토어 API(등록/재고 관리)는 STORE 역할이 필요하다.
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    //공개 API (비인증)
    @GetMapping("/api/v1/products")
    public ResponseEntity<CursorResponse<ProductListResponse>> getProducts(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) MainCategory mainCategory,
            @RequestParam(required = false) SubCategory subCategory,
            @RequestParam(required = false) Integer pageSize) {
        return ResponseEntity.ok(productService.getProducts(cursor, storeId, mainCategory, subCategory, pageSize));
    }

    @GetMapping("/api/v1/products/{productId}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.getProduct(productId));
    }

    //  스토어 API (STORE 권한 필요)
    @GetMapping("/api/v1/stores/{storeId}/products")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<CursorResponse<ProductListResponse>> getMyStoreProducts(
            @PathVariable Long storeId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(productService.getMyStoreProducts(userId, storeId, cursor, pageSize));
    }

    @PostMapping("/api/v1/stores/{storeId}/products")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<ProductResponse> registerProduct(
            @PathVariable Long storeId,
            @Valid @RequestBody ProductCreateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.registerProduct(userId, storeId, request));
    }

    @PatchMapping("/api/v1/stores/{storeId}/products/{productId}/options/{optionId}/stock")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<ProductOptionResponse> updateStock(
            @PathVariable Long storeId,
            @PathVariable Long productId,
            @PathVariable Long optionId,
            @Valid @RequestBody StockUpdateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(productService.updateStock(userId, storeId, productId, optionId, request.stockQuantity()));
    }

    // USER API (상품 찜하기)
    @PostMapping("/api/v1/products/{productId}/likes")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<Void> likeProduct(
            @PathVariable Long productId,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        productService.likeProduct(userId, productId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<Void> unlikeProduct(
            @PathVariable Long productId,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        productService.unlikeProduct(userId, productId);
        return ResponseEntity.ok().build();
    }
}
