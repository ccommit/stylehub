package ccommit.stylehub.coupon.port;

import ccommit.stylehub.coupon.dto.CouponUsageResult;

import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/05/08
 * @modified 2026/09/17 by WonJin - fix: 할인 기준 금액을 스토어별 주문 금액으로 받도록 변경 (스토어 쿠폰이 다른 스토어 상품까지 할인하던 문제)
 *
 * <p>
 * Coupon 도메인이 외부 (Order 등) 에 제공하는 포트 인터페이스이다.
 * UserCoupon 사용 처리 + 결제 실패 시 보상 (복구) 메서드를 제공한다.
 * </p>
 */
public interface CouponPort {

    // 주문 도메인이 쿠폰 유형을 몰라도 되도록 스토어별 주문 금액만 받고, 할인 기준 금액은 쿠폰 도메인이 고른다.
    CouponUsageResult useUserCoupon(Long userId, Long userCouponId, Map<Long, Integer> amountByStore);

    // 결제 실패 시 보상 트랜잭션에서 호출한다. 이미 UNUSED면 그대로 둔다(멱등).
    void restoreUserCoupon(Long userCouponId);
}
