package ccommit.stylehub.coupon.port;

import ccommit.stylehub.coupon.dto.CouponUsageResult;

/**
 * @author WonJin Bae
 * @created 2026/05/08
 *
 * <p>
 * Coupon 도메인이 외부 (Order 등) 에 제공하는 포트 인터페이스이다.
 * UserCoupon 사용 처리 + 결제 실패 시 보상 (복구) 메서드를 제공한다.
 * </p>
 */
public interface CouponPort {

    /**
     * UserCoupon 을 USED 상태로 전이하고 할인 금액을 계산해 반환한다.
     * 주문 생성 시점에 호출.
     *
     * <p>검증:
     * <br>- UserCoupon 존재 여부
     * <br>- 본인 소유 여부
     * <br>- UNUSED 상태 (재사용 차단)
     * <br>- 쿠폰 이벤트 활성/시작/만료
     * <br>- 최소 주문 금액 충족
     */
    CouponUsageResult useUserCoupon(Long userId, Long userCouponId, int totalAmount);

    /**
     * UserCoupon 을 UNUSED 상태로 복구한다. 결제 실패 시 보상 트랜잭션에서 호출.
     * 멱등 — 이미 UNUSED 면 그대로.
     */
    void restoreUserCoupon(Long userCouponId);
}
