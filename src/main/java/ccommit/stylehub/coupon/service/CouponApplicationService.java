package ccommit.stylehub.coupon.service;

import ccommit.stylehub.common.aop.DistributedLock;
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
 * @modified 2026/05/06 by WonJin - perf: 선착순 쿠폰 발급 동시성 메커니즘 진화 측정 — @Transactional → @DistributedLock 시도 (측정 결과 비관적 락보다 나쁨) → 비관적 락 복원 → CouponService 의 Redis DECR + Lua atomic + 비동기 저장 채택에 따라 단순 위임자로 정리 (선착순쿠폰-동시성측정.md 참조)
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

    /**
     * 선착순 쿠폰 발급. 동시성 제어는 도메인 서비스의 Redis DECR + Lua atomic 이 담당한다.
     *
     * <p>설계 진화: 비관적 락 → @DistributedLock (SETNX 폴링) → Redis DECR + Lua atomic + 비동기 저장.
     * 모든 측정은 선착순쿠폰-동시성측정.md 참조.
     *
     * <p>이 메서드는 *DB 트랜잭션을 열지 않는다*. CouponService 가 Redis 호출만 하므로
     * @Transactional 불필요. UserCoupon DB INSERT 는 비동기 listener 가 별도 트랜잭션에서 처리.
     */
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
