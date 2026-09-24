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
 * @modified 2026/05/06 by WonJin - perf: 선착순 쿠폰 발급 동시성 메커니즘 진화 측정 — @Transactional → @DistributedLock 시도 (측정 결과 비관적 락보다 나쁨) → 비관적 락 복원 → CouponService 의 Redis DECR + Lua atomic + 비동기 저장 채택에 따라 단순 위임자로 정리 (선착순쿠폰-동시성측정.md 참조)
 * @modified 2026/09/15 by WonJin - perf: 발급 시 사용자 조회를 참조 획득으로 교체 (매진·중복 거절 경로에서 DB 조회 제거)
 * @modified 2026/09/17 by WonJin - feat: 발급 카운터 재동기화 유스케이스 추가, 커넥션 점유 설명에 OSIV 전제 명시
 * @modified 2026/09/24 by WonJin - refactor: 스토어 쿠폰 이벤트 생성이 storeId 를 받지 않고 세션 스토어만 다룸
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
    public CouponEventResponse createStoreCouponEvent(Long userId, CouponEventCreateRequest request) {
        User storeOwner = userPort.findApprovedStore(userId);
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

    // Redis 호출 동안 DB 커넥션을 잡지 않도록 트랜잭션을 열지 않는다(OSIV가 꺼져 있어야 성립한다).
    // 매진·중복으로 거절될 요청이 사용자 조회 쿼리를 내지 않도록, 세션으로 존재가 보장된 사용자는 참조만 얻는다.
    public void issueCoupon(Long userId, Long couponEventId) {
        User user = userPort.getUserReference(userId);
        couponService.issueCoupon(user, couponEventId);
    }

    // 이벤트 존재 확인과 Redis 키 삭제뿐이라 트랜잭션을 열지 않는다.
    public void resyncIssueCounter(Long couponEventId) {
        couponService.resyncIssueCounter(couponEventId);
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
