package bwj.stylehub.user.controller;

import bwj.stylehub.user.dto.response.OAuthLoginResponse;
import bwj.stylehub.user.enums.Provider;
import bwj.stylehub.user.service.OAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
