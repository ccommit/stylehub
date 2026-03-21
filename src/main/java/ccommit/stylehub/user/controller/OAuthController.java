package ccommit.stylehub.user.controller;

import ccommit.stylehub.user.dto.response.OAuthLoginResponse;
import ccommit.stylehub.user.enums.Provider;
import ccommit.stylehub.user.service.OAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * OAuth 소셜 로그인 API 엔드포인트를 제공한다.
 * 인가 URL 반환과 콜백 처리를 담당한다.
 * </p>
 */

@RestController
@RequestMapping("/api/v1/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oAuthService;

    @GetMapping("/{provider}")
    public ResponseEntity<Map<String, String>> authorizationUrl(
            @PathVariable Provider provider) {
        String url = oAuthService.getAuthorizationUrl(provider);
        return ResponseEntity.ok(Map.of("authorizationUrl", url));
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<OAuthLoginResponse> callback(
            @PathVariable Provider provider,
            @RequestParam String code) {
        return ResponseEntity.ok(oAuthService.login(provider, code));
    }
}
