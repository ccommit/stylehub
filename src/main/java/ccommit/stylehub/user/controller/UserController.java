package ccommit.stylehub.user.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.user.dto.request.StoreSignUpRequest;
import ccommit.stylehub.user.dto.request.UserLoginRequest;
import ccommit.stylehub.user.dto.request.UserSignUpRequest;
import ccommit.stylehub.user.dto.response.OAuthLoginResponse;
import ccommit.stylehub.user.dto.response.StoreResponse;
import ccommit.stylehub.user.dto.response.StoreSignUpResponse;
import ccommit.stylehub.user.dto.response.UserLoginResponse;
import ccommit.stylehub.user.dto.response.UserSignUpResponse;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.OAuthProvider;
import ccommit.stylehub.user.enums.StoreStatus;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.service.OAuthService;
import ccommit.stylehub.user.service.UserService;
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

import java.util.List;
import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/04/19 by WonJin - refactor: StoreController, StoreAdminController를 UserController로 통합
 *
 * <p>
 * 회원, 스토어, 관리자 API를 제공한다.
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final OAuthService oAuthService;

    // ========================
    // 회원 API (인증 불필요)
    // ========================

    @PostMapping("/users/sign-up")
    public ResponseEntity<UserSignUpResponse> signUp(@Valid @RequestBody UserSignUpRequest request) {
        User user = userService.signUp(request.name(), request.email(), request.password(), request.birthDate(), UserRole.USER);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserSignUpResponse.from(user));
    }

    @PostMapping("/users/sign-up/store")
    public ResponseEntity<StoreSignUpResponse> signUpWithStore(@Valid @RequestBody StoreSignUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.signUpWithStore(request));
    }

    @PostMapping("/users/login")
    public ResponseEntity<UserLoginResponse> login(
            @Valid @RequestBody UserLoginRequest request,
            HttpServletRequest httpRequest) {
        UserLoginResponse loginResult = userService.login(request);
        SessionUtils.createSession(httpRequest, loginResult.userId(), loginResult.role());
        return ResponseEntity.ok(loginResult);
    }

    @GetMapping("/users/oauth/{provider}")
    public ResponseEntity<Map<String, String>> authorizationUrl(
            @PathVariable OAuthProvider provider) {
        String url = oAuthService.getAuthorizationUrl(provider);
        return ResponseEntity.ok(Map.of("authorizationUrl", url));
    }

    @GetMapping("/users/oauth/{provider}/callback")
    public ResponseEntity<OAuthLoginResponse> callback(
            @PathVariable OAuthProvider provider,
            @RequestParam String code,
            HttpServletRequest httpRequest) {
        OAuthLoginResponse loginResult = oAuthService.login(provider, code);
        SessionUtils.createSession(httpRequest, loginResult.userId(), loginResult.role());
        return ResponseEntity.ok(loginResult);
    }

    @PostMapping("/users/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        SessionUtils.invalidateSession(httpRequest);
        return ResponseEntity.ok().build();
    }

    // 스토어 API (STORE 역할)
    @GetMapping("/stores/my")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<StoreResponse> getMyStore(HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(userService.getMyStore(userId));
    }

    // 스토어 관리 API (ADMIN 역할)
    @GetMapping("/admin/stores")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<List<StoreResponse>> getStores(
            @RequestParam(required = false) StoreStatus status) {
        return ResponseEntity.ok(userService.getStoresByStatus(status));
    }

    @GetMapping("/admin/stores/{storeId}")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<StoreResponse> getStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(userService.getStoreByUserId(storeId));
    }

    @PatchMapping("/admin/stores/{storeId}/approve")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<StoreResponse> approve(@PathVariable Long storeId) {
        return ResponseEntity.ok(userService.approveStore(storeId));
    }

    @PatchMapping("/admin/stores/{storeId}/reject")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<StoreResponse> reject(@PathVariable Long storeId) {
        return ResponseEntity.ok(userService.rejectStore(storeId));
    }

    @PatchMapping("/admin/stores/{storeId}/suspend")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<StoreResponse> suspend(@PathVariable Long storeId) {
        return ResponseEntity.ok(userService.suspendStore(storeId));
    }
}
