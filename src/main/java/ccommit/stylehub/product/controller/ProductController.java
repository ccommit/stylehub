package ccommit.stylehub.product.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.product.dto.request.ProductCreateRequest;
import ccommit.stylehub.product.dto.request.StockUpdateRequest;
import ccommit.stylehub.product.dto.response.ProductOptionResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.service.ProductService;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * STORE 역할 사용자의 상품 등록 및 재고 관리 API를 제공한다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/products")
@RequiredArgsConstructor
@RequiredRole(UserRole.STORE)
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<ProductResponse> registerProduct(
            @PathVariable Long storeId,
            @Valid @RequestBody ProductCreateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.registerProduct(userId, storeId, request));
    }

    @PatchMapping("/{productId}/options/{optionId}/stock")
    public ResponseEntity<ProductOptionResponse> updateStock(
            @PathVariable Long storeId,
            @PathVariable Long productId,
            @PathVariable Long optionId,
            @Valid @RequestBody StockUpdateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(productService.updateStock(userId, storeId, productId, optionId, request.stockQuantity()));
    }
}
