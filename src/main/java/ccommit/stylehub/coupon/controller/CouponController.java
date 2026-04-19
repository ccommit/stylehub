package ccommit.stylehub.coupon.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.dto.response.CouponEventResponse;
import ccommit.stylehub.coupon.dto.response.UserCouponResponse;
import ccommit.stylehub.coupon.service.CouponService;
import ccommit.stylehub.user.enums.UserRole;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/04/09
 *
 * <p>
 * 쿠폰 이벤트 생성(STORE/ADMIN) 및 선착순 발급(USER) API를 제공한다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    // STORE: 스토어 쿠폰 이벤트 생성
    @PostMapping("/stores/{storeId}/coupon-events")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<CouponEventResponse> createStoreCouponEvent(
            @PathVariable Long storeId,
            @Valid @RequestBody CouponEventCreateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(couponService.createStoreCouponEvent(userId, storeId, request));
    }

    // ADMIN: 플랫폼 쿠폰 이벤트 생성
    @PostMapping("/admin/coupon-events")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<CouponEventResponse> createPlatformCouponEvent(
            @Valid @RequestBody CouponEventCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(couponService.createPlatformCouponEvent(request));
    }

    // ADMIN: 쿠폰 이벤트 수정
    @PatchMapping("/admin/coupon-events/{couponEventId}")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<CouponEventResponse> updateCouponEvent(
            @PathVariable Long couponEventId,
            @Valid @RequestBody CouponEventUpdateRequest request) {
        return ResponseEntity.ok(couponService.updateCouponEvent(couponEventId, request));
    }

    // ADMIN: 쿠폰 이벤트 비활성화
    @PatchMapping("/admin/coupon-events/{couponEventId}/deactivate")
    @RequiredRole(UserRole.ADMIN)
    public ResponseEntity<Void> deactivateCouponEvent(@PathVariable Long couponEventId) {
        couponService.deactivateCouponEvent(couponEventId);
        return ResponseEntity.ok().build();
    }

    // USER: 내 쿠폰 목록 조회
    @GetMapping("/coupon-events/my")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<List<UserCouponResponse>> getMyCoupons(HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(couponService.getMyCoupons(userId));
    }

    // USER: 선착순 쿠폰 발급
    @PostMapping("/coupon-events/{couponEventId}/issue")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<Void> issueCoupon(
            @PathVariable Long couponEventId,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        couponService.issueCoupon(userId, couponEventId);
        return ResponseEntity.ok().build();
    }

    // 활성 쿠폰 이벤트 목록 조회 (공개)
    @GetMapping("/coupon-events")
    public ResponseEntity<List<CouponEventResponse>> getActiveCouponEvents() {
        return ResponseEntity.ok(couponService.getActiveCouponEvents());
    }
}
