package ccommit.stylehub.user.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.user.dto.response.MyPointResponse;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.service.PointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 * @modified 2026/09/24 by WonJin - docs: Swagger 태그·API 요약(@Tag, @Operation) 추가
 *
 * <p>
 * 로그인한 구매자(USER)의 보유 포인트·포인트 이력 조회 API를 제공한다.
 * 경로에 userId 를 받지 않고 세션의 userId 만 사용해, 요청 값으로 다른 사용자의 포인트를 조회할 수 없게 한다.
 * </p>
 */
@RestController
@Tag(name = "포인트", description = "내 포인트 잔액과 이력")
@RequestMapping("/users/me/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @Operation(summary = "내 포인트 잔액·이력 조회")
    @GetMapping
    @RequiredRole(UserRole.USER)
    public ResponseEntity<MyPointResponse> getMyPoints(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(pointService.getMyPoints(userId, cursor, size));
    }
}
