package ccommit.stylehub.store.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.store.dto.response.StoreResponse;
import ccommit.stylehub.store.enums.StoreStatus;
import ccommit.stylehub.store.service.StoreService;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 * @modified 2026/04/02 by WonJin - refactor: StoreAdminController를 StoreController로 통합
 *
 * <p>
 * 스토어 관련 API를 제공한다.
 * STORE API(내 스토어 조회)와 ADMIN API(입점 관리)를 메서드별 역할로 구분한다.
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    // STORE API
    @GetMapping("/api/v1/stores/my")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<StoreResponse> getMyStore(HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(storeService.getMyStore(userId));
    }

    // ADMIN API
    @GetMapping("/api/v1/admin/stores")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<List<StoreResponse>> getStores(
            @RequestParam(required = false) StoreStatus status) {
        return ResponseEntity.ok(storeService.getStoresByStatus(status));
    }

    @GetMapping("/api/v1/admin/stores/{storeId}")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<StoreResponse> getStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(storeService.getStore(storeId));
    }

    @PatchMapping("/api/v1/admin/stores/{storeId}/approve")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<StoreResponse> approve(@PathVariable Long storeId) {
        return ResponseEntity.ok(storeService.approve(storeId));
    }

    @PatchMapping("/api/v1/admin/stores/{storeId}/reject")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<StoreResponse> reject(@PathVariable Long storeId) {
        return ResponseEntity.ok(storeService.reject(storeId));
    }

    @PatchMapping("/api/v1/admin/stores/{storeId}/suspend")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<StoreResponse> suspend(@PathVariable Long storeId) {
        return ResponseEntity.ok(storeService.suspend(storeId));
    }
}
