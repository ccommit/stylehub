package ccommit.stylehub.coupon.dto;

import ccommit.stylehub.coupon.entity.UserCoupon;

/**
 * @author WonJin Bae
 * @created 2026/05/08
 *
 * <p>
 * 쿠폰 사용 결과 — UserCoupon 엔티티 + 계산된 할인 금액.
 * Order 가 OrderDetail 에 userCoupon 을 연결하고 discountAmount 를 적용하는 데 사용.
 * </p>
 */
public record CouponUsageResult(UserCoupon userCoupon, int discountAmount) {
}
