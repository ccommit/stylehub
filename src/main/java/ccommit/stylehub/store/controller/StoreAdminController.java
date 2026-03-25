package ccommit.stylehub.store.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.store.dto.response.StoreResponse;
import ccommit.stylehub.store.enums.StoreStatus;
import ccommit.stylehub.store.service.StoreAdminService;
import ccommit.stylehub.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * ADMIN 역할의 입점 신청 관리(목록 조회, 승인, 거절, 정지) API를 제공한다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin/stores")
@RequiredArgsConstructor
@RequiredRole(UserRole.ADMIN)
public class StoreAdminController {

    private final StoreAdminService storeAdminService;

    @GetMapping
    public ResponseEntity<List<StoreResponse>> getStores(
            @RequestParam(required = false) StoreStatus status) {
        return ResponseEntity.ok(storeAdminService.getStoresByStatus(status));
    }

    @GetMapping("/{storeId}")
    public ResponseEntity<StoreResponse> getStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(storeAdminService.getStore(storeId));
    }

    @PatchMapping("/{storeId}/approve")
    public ResponseEntity<StoreResponse> approve(@PathVariable Long storeId) {
        return ResponseEntity.ok(storeAdminService.approve(storeId));
    }

    @PatchMapping("/{storeId}/reject")
    public ResponseEntity<StoreResponse> reject(@PathVariable Long storeId) {
        return ResponseEntity.ok(storeAdminService.reject(storeId));
    }

    @PatchMapping("/{storeId}/suspend")
    public ResponseEntity<StoreResponse> suspend(@PathVariable Long storeId) {
        return ResponseEntity.ok(storeAdminService.suspend(storeId));
    }
}
