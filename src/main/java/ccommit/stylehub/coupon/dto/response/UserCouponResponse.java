package ccommit.stylehub.coupon.dto.response;

import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.enums.CouponStatus;
import ccommit.stylehub.coupon.enums.DiscountType;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/04/15
 *
 * <p>
 * 마이페이지 쿠폰 목록 응답 DTO이다.
 * 실제 만료 여부(expired)는 조회 시점 기준으로 계산하여 EXPIRED 필터 미적용 시에도 UI에서 구분 가능하게 한다.
 * </p>
 */
@Builder
public record UserCouponResponse(
        Long userCouponId,
        Long couponEventId,
        String couponName,
        String storeName,
        DiscountType discountType,
        Integer discountValue,
        Integer minOrderAmount,
        LocalDateTime startedAt,
        LocalDateTime expiredAt,
        CouponStatus status,
        boolean expired,
        LocalDateTime usedAt
) {
    public static UserCouponResponse from(UserCoupon userCoupon, LocalDateTime now) {
        var event = userCoupon.getCouponEvent();
        return UserCouponResponse.builder()
                .userCouponId(userCoupon.getUserCouponId())
                .couponEventId(event.getCouponEventId())
                .couponName(event.getName())
                .storeName(event.getStoreUser() != null ? event.getStoreUser().getStoreName() : "StyleHub")
                .discountType(event.getDiscountType())
                .discountValue(event.getDiscountValue())
                .minOrderAmount(event.getMinOrderAmount())
                .startedAt(event.getStartedAt())
                .expiredAt(event.getExpiredAt())
                .status(userCoupon.getStatus())
                .expired(now.isAfter(event.getExpiredAt()))
                .usedAt(userCoupon.getUsedAt())
                .build();
    }
}
