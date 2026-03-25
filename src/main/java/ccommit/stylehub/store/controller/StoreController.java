package ccommit.stylehub.store.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.store.dto.response.StoreResponse;
import ccommit.stylehub.store.service.StoreService;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * STORE 역할 사용자의 스토어 관리 API를 제공한다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
@RequiredRole(UserRole.STORE)
public class StoreController {

    private final StoreService storeService;

    @GetMapping("/my")
    public ResponseEntity<StoreResponse> getMyStore(HttpServletRequest httpRequest) {
        // TODO:  Redis에 세션 + 사용자 상태를 캐싱예정
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(storeService.getMyStore(userId));
    }
}
