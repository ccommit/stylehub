package ccommit.stylehub.coupon.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.dto.response.CouponEventResponse;
import ccommit.stylehub.coupon.dto.response.UserCouponResponse;
import ccommit.stylehub.coupon.service.CouponApplicationService;
import ccommit.stylehub.user.enums.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/04/09
 * @modified 2026/09/17 by WonJin - feat: 선착순 발급 카운터 재동기화 관리자 API 추가 (Redis 자리 누수 복구 수단)
 * @modified 2026/09/24 by WonJin - refactor: 스토어 쿠폰 이벤트 생성을 /stores/me 로 변경, 활성 쿠폰 이벤트 목록을 공개 API 로 전환
 * @modified 2026/09/24 by WonJin - docs: Swagger 태그·API 요약(@Tag, @Operation) 추가
 *
 * <p>
 * 쿠폰 이벤트 생성(STORE/ADMIN) 및 선착순 발급(USER) API를 제공한다.
 * </p>
 */
@RestController
@Tag(name = "쿠폰", description = "쿠폰 이벤트 관리와 선착순 발급")
@RequiredArgsConstructor
public class CouponController {

    private final CouponApplicationService couponApplicationService;

    // STORE: 스토어 쿠폰 이벤트 생성
    @Operation(summary = "스토어 쿠폰 이벤트 생성")
    @PostMapping("/stores/me/coupon-events")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<CouponEventResponse> createStoreCouponEvent(
            @Valid @RequestBody CouponEventCreateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(couponApplicationService.createStoreCouponEvent(userId, request));
    }

    // ADMIN: 플랫폼 쿠폰 이벤트 생성
    @Operation(summary = "플랫폼 쿠폰 이벤트 생성")
    @PostMapping("/admin/coupon-events")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<CouponEventResponse> createPlatformCouponEvent(
            @Valid @RequestBody CouponEventCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(couponApplicationService.createPlatformCouponEvent(request));
    }

    // ADMIN: 쿠폰 이벤트 수정
    @Operation(summary = "쿠폰 이벤트 수정")
    @PatchMapping("/admin/coupon-events/{couponEventId}")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<CouponEventResponse> updateCouponEvent(
            @PathVariable Long couponEventId,
            @Valid @RequestBody CouponEventUpdateRequest request) {
        return ResponseEntity.ok(couponApplicationService.updateCouponEvent(couponEventId, request));
    }

    // ADMIN: 쿠폰 이벤트 비활성화
    @Operation(summary = "쿠폰 이벤트 비활성화")
    @PatchMapping("/admin/coupon-events/{couponEventId}/deactivate")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<Void> deactivateCouponEvent(@PathVariable Long couponEventId) {
        couponApplicationService.deactivateCouponEvent(couponEventId);
        return ResponseEntity.ok().build();
    }

    // 커밋 전 서버 종료나 Redis 보상 실패로 새어 나간 자리와 사용자 기록을 지워, 다음 발급 요청이 DB 기준으로 카운터를 다시 만들게 한다.
    @Operation(summary = "선착순 발급 카운터 재동기화(Redis 를 DB 기준으로 복구)")
    @PostMapping("/admin/coupon-events/{couponEventId}/issue-counter/resync")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<Void> resyncIssueCounter(@PathVariable Long couponEventId) {
        couponApplicationService.resyncIssueCounter(couponEventId);
        return ResponseEntity.ok().build();
    }

    // USER: 내 쿠폰 목록 조회
    @Operation(summary = "내 쿠폰 목록 조회")
    @GetMapping("/coupon-events/my")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<List<UserCouponResponse>> getMyCoupons(HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(couponApplicationService.getMyCoupons(userId));
    }

    // USER: 선착순 쿠폰 발급
    @Operation(summary = "선착순 쿠폰 발급")
    @PostMapping("/coupon-events/{couponEventId}/issue")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<Void> issueCoupon(
            @PathVariable Long couponEventId,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        couponApplicationService.issueCoupon(userId, couponEventId);
        return ResponseEntity.ok().build();
    }

    // 활성 쿠폰 이벤트 목록 조회 (공개)
    @Operation(summary = "진행 중인 쿠폰 이벤트 목록 조회")
    @GetMapping("/coupon-events")
    public ResponseEntity<List<CouponEventResponse>> getActiveCouponEvents() {
        return ResponseEntity.ok(couponApplicationService.getActiveCouponEvents());
    }
}
