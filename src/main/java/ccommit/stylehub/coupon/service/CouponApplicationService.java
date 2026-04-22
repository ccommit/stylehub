package ccommit.stylehub.coupon.service;

import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.dto.response.CouponEventResponse;
import ccommit.stylehub.coupon.dto.response.UserCouponResponse;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.port.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/04/22
 *
 * <p>
 * Coupon 유스케이스를 오케스트레이션하는 Application 계층 서비스이다.
 * 스토어 소유권 검증과 User 조회(UserPort)를 담당하고, 쿠폰 도메인 로직은 CouponService에 위임한다.
 * 권한 검증은 Application 관심사이므로 Domain 계층(CouponService)에서 분리해 여기서 처리한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class CouponApplicationService {

    private final UserPort userPort;
    private final CouponService couponService;

    @Transactional
    public CouponEventResponse createStoreCouponEvent(Long userId, Long storeId,
                                                      CouponEventCreateRequest request) {
        User storeOwner = userPort.findApprovedStoreByOwner(userId, storeId);
        return couponService.createStoreCouponEvent(storeOwner, request);
    }

    @Transactional
    public CouponEventResponse createPlatformCouponEvent(CouponEventCreateRequest request) {
        return couponService.createPlatformCouponEvent(request);
    }

    @Transactional
    public CouponEventResponse updateCouponEvent(Long couponEventId, CouponEventUpdateRequest request) {
        return couponService.updateCouponEvent(couponEventId, request);
    }

    @Transactional
    public void deactivateCouponEvent(Long couponEventId) {
        couponService.deactivateCouponEvent(couponEventId);
    }

    @Transactional
    public void issueCoupon(Long userId, Long couponEventId) {
        User user = userPort.findUserById(userId);
        couponService.issueCoupon(user, couponEventId);
    }

    @Transactional(readOnly = true)
    public List<UserCouponResponse> getMyCoupons(Long userId) {
        return couponService.getMyCoupons(userId);
    }

    @Transactional(readOnly = true)
    public List<CouponEventResponse> getActiveCouponEvents() {
        return couponService.getActiveCouponEvents();
    }
}
