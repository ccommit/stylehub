package ccommit.stylehub.product.controller;

import ccommit.stylehub.product.dto.response.ProductCursorResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.product.service.ProductViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author WonJin Bae
 * @created 2026/03/26
 * @modified 2026/03/27 by WonJin - feat: 커서 기반 전체 상품 목록 조회 API 추가
 *
 * <p>
 * 일반 사용자의 상품 조회 API를 제공한다. 인증 불필요.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductViewController {

    private final ProductViewService productViewService;

    @GetMapping
    public ResponseEntity<ProductCursorResponse> getProducts(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) MainCategory mainCategory,
            @RequestParam(required = false) SubCategory subCategory,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(productViewService.getProducts(cursor, storeId, mainCategory, subCategory, size));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(productViewService.getProduct(productId));
    }
}
