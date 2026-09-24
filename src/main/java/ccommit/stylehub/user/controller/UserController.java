package ccommit.stylehub.user.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.OAuthStateUtils;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.user.dto.request.StoreSignUpRequest;
import ccommit.stylehub.user.dto.request.StoreStatusUpdateRequest;
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
 * @modified 2026/09/17 by WonJin - fix: OAuth 인가 요청에 state 발급, 콜백에서 세션 state 를 검증한 뒤에만 로그인 세션 생성(로그인 CSRF 방지)
 * @modified 2026/09/24 by WonJin - refactor: 내 스토어 조회를 /stores/me 로 변경, 승인·거절·정지를 PATCH /admin/stores/{storeId}/status 하나로 통합
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
            @PathVariable OAuthProvider provider,
            HttpServletRequest httpRequest) {
        String state = OAuthStateUtils.issue(httpRequest);
        String url = oAuthService.getAuthorizationUrl(provider, state);
        return ResponseEntity.ok(Map.of("authorizationUrl", url));
    }

    @GetMapping("/users/oauth/{provider}/callback")
    public ResponseEntity<OAuthLoginResponse> callback(
            @PathVariable OAuthProvider provider,
            @RequestParam String code,
            // 누락도 불일치와 같은 INVALID_OAUTH_STATE 로 응답하기 위해 필수 파라미터로 두지 않는다.
            @RequestParam(required = false) String state,
            HttpServletRequest httpRequest) {
        // createSession 이 기존 세션(state 포함)을 무효화하므로 검증은 그 전에 끝낸다.
        // 위조된 콜백이 제공자 호출까지 이어지지 않도록 인가 코드 교환보다도 먼저 한다.
        OAuthStateUtils.verifyAndConsume(httpRequest, state);
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
    @GetMapping("/stores/me")
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

    @PatchMapping("/admin/stores/{storeId}/status")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<StoreResponse> updateStoreStatus(
            @PathVariable Long storeId,
            @Valid @RequestBody StoreStatusUpdateRequest request) {
        return ResponseEntity.ok(userService.updateStoreStatus(storeId, request.status()));
    }
}
