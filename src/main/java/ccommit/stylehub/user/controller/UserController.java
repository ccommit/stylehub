package ccommit.stylehub.user.controller;

import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.store.dto.request.StoreSignUpRequest;
import ccommit.stylehub.store.dto.response.StoreSignUpResponse;
import ccommit.stylehub.store.entity.Store;
import ccommit.stylehub.store.service.StoreService;
import ccommit.stylehub.user.dto.request.UserLoginRequest;
import ccommit.stylehub.user.dto.request.UserSignUpRequest;
import ccommit.stylehub.user.dto.response.OAuthLoginResponse;
import ccommit.stylehub.user.dto.response.UserLoginResponse;
import ccommit.stylehub.user.dto.response.UserSignUpResponse;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.OAuthProvider;
import ccommit.stylehub.user.enums.UserRole;

import ccommit.stylehub.user.service.OAuthService;
import ccommit.stylehub.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/03/23 by WonJin - feat: HTTP 세션 기반 인증 적용 (로그인/OAuth 세션 생성, 로그아웃)
 * @modified 2026/03/25 by WonJin - feat: 스토어 회원가입 + 입점 신청 API 추가
 *
 * <p>
 * 회원가입, 로그인, OAuth 소셜 로그인, 로그아웃 API 엔드포인트를 제공한다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final OAuthService oAuthService;
    private final StoreService storeService;

    @PostMapping("/sign-up")
    public ResponseEntity<UserSignUpResponse> signUp(@Valid @RequestBody UserSignUpRequest request) {
        User user = userService.signUp(request.name(), request.email(), request.password(), request.birthDate(), UserRole.USER);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserSignUpResponse.from(user));
    }

    @PostMapping("/sign-up/store")
    public ResponseEntity<StoreSignUpResponse> signUpWithStore(@Valid @RequestBody StoreSignUpRequest request) {
        User user = userService.signUp(request.name(), request.email(), request.password(), null, UserRole.STORE);
        Store store = storeService.saveStore(user, request.storeName(), request.storeDescription());
        return ResponseEntity.status(HttpStatus.CREATED).body(StoreSignUpResponse.from(user, store));
    }

    @PostMapping("/login")
    public ResponseEntity<UserLoginResponse> login(
            @Valid @RequestBody UserLoginRequest request,
            HttpServletRequest httpRequest) {
        UserLoginResponse response = userService.login(request);
        SessionUtils.createSession(httpRequest, response.userId(), response.role());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/oauth/{provider}")
    public ResponseEntity<Map<String, String>> authorizationUrl(
            @PathVariable OAuthProvider provider) {
        String url = oAuthService.getAuthorizationUrl(provider);
        return ResponseEntity.ok(Map.of("authorizationUrl", url));
    }

    @GetMapping("/oauth/{provider}/callback")
    public ResponseEntity<OAuthLoginResponse> callback(
            @PathVariable OAuthProvider provider,
            @RequestParam String code,
            HttpServletRequest httpRequest) {
        OAuthLoginResponse response = oAuthService.login(provider, code);
        SessionUtils.createSession(httpRequest, response.userId(), response.role());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        SessionUtils.invalidateSession(httpRequest);
        return ResponseEntity.ok().build();
    }
}
