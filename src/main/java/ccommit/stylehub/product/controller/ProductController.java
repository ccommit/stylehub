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
import ccommit.stylehub.product.service.ProductApplicationService;
import ccommit.stylehub.user.enums.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * @modified 2026/09/17 by WonJin - fix: 재고 변경 API 가 경로의 productId 를 바인딩해 옵션 소속 검증에 사용 (IDOR 차단)
 * @modified 2026/09/24 by WonJin - refactor: 스토어 API 를 세션 스토어 기준 /stores/me 로 변경 (경로의 storeId 제거)
 * @modified 2026/09/24 by WonJin - docs: Swagger 태그·API 요약(@Tag, @Operation) 추가
 *
 * <p>
 * 상품 관련 API를 제공한다.
 * 공개 API(목록/단건 조회)는 인증 불필요, 스토어 API(등록/재고 관리)는 STORE 역할이 필요하다.
 * </p>
 */
@RestController
@Tag(name = "상품", description = "상품 조회(공개)와 스토어 상품 관리")
@RequiredArgsConstructor
public class ProductController {

    private final ProductApplicationService productApplicationService;

    //공개 API (비인증)
    @Operation(summary = "상품 목록 조회(커서 페이징, 스토어·카테고리 필터)")
    @GetMapping("/products")
    public ResponseEntity<CursorResponse<ProductListResponse>> getProducts(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) MainCategory mainCategory,
            @RequestParam(required = false) SubCategory subCategory,
            @RequestParam(required = false) Integer pageSize) {
        return ResponseEntity.ok(productApplicationService.getProducts(cursor, storeId, mainCategory, subCategory, pageSize));
    }

    @Operation(summary = "상품 상세 조회(옵션 포함)")
    @GetMapping("/products/{productId}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(productApplicationService.getProduct(productId));
    }

    //  스토어 API (STORE 권한 필요)
    @Operation(summary = "내 스토어 상품 목록 조회")
    @GetMapping("/stores/me/products")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<CursorResponse<ProductListResponse>> getMyStoreProducts(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(productApplicationService.getMyStoreProducts(userId, cursor, pageSize));
    }

    @Operation(summary = "상품 등록(옵션 포함)")
    @PostMapping("/stores/me/products")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<ProductResponse> registerProduct(
            @Valid @RequestBody ProductCreateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productApplicationService.registerProduct(userId, request));
    }

    @Operation(summary = "옵션 재고 수정")
    @PatchMapping("/stores/me/products/{productId}/options/{optionId}/stock")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<ProductOptionResponse> updateStock(
            @PathVariable Long productId,
            @PathVariable Long optionId,
            @Valid @RequestBody StockUpdateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(productApplicationService.updateStock(
                userId, productId, optionId, request.stockQuantity()));
    }
}
