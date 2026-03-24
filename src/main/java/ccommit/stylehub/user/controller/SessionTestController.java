package ccommit.stylehub.user.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/03/23
 *
 * <p>
 * HTTP 세션 인증 테스트용 임시 컨트롤러이다.
 * 테스트 완료 후 삭제한다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
public class SessionTestController {

    // 로그인한 사용자만 접근 가능
    @GetMapping("/auth")
    public ResponseEntity<Map<String, Object>> authTest(HttpServletRequest request) {
        Long userId = SessionUtils.getUserId(request);
        UserRole role = SessionUtils.getUserRole(request);
        return ResponseEntity.ok(Map.of(
                "message", "인증 성공",
                "userId", userId,
                "role", role
        ));
    }

    // ADMIN만 접근 가능
    @GetMapping("/admin")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<Map<String, String>> adminTest() {
        return ResponseEntity.ok(Map.of("message", "ADMIN 권한 확인 성공"));
    }

    // STORE만 접근 가능
    @GetMapping("/store")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<Map<String, String>> storeTest() {
        return ResponseEntity.ok(Map.of("message", "STORE 권한 확인 성공"));
    }
}
