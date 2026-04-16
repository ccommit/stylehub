package ccommit.stylehub.coupon.dto.response;

import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.enums.CouponType;
import ccommit.stylehub.coupon.enums.DiscountType;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/04/09
 * @modified 2026/04/16 by WonJin - refactor: couponType 필드 추가
 *
 * <p>
 * 쿠폰 이벤트 응답 DTO이다.
 * </p>
 */
@Builder
public record CouponEventResponse(
        Long couponEventId,
        CouponType couponType,
        Long storeId,
        String storeName,
        String name,
        DiscountType discountType,
        Integer discountValue,
        Integer minOrderAmount,
        Integer issueCount,
        Integer issuedCount,
        LocalDateTime startedAt,
        LocalDateTime expiredAt,
        Boolean active,
        LocalDateTime createdAt
) {
    public static CouponEventResponse from(CouponEvent event) {
        return CouponEventResponse.builder()
                .couponEventId(event.getCouponEventId())
                .couponType(event.getCouponType())
                .storeId(event.getStore() != null ? event.getStore().getStoreId() : null)
                .storeName(event.getStore() != null ? event.getStore().getName() : "StyleHub")
                .name(event.getName())
                .discountType(event.getDiscountType())
                .discountValue(event.getDiscountValue())
                .minOrderAmount(event.getMinOrderAmount())
                .issueCount(event.getIssueCount())
                .issuedCount(event.getIssuedCount())
                .startedAt(event.getStartedAt())
                .expiredAt(event.getExpiredAt())
                .active(event.getActive())
                .createdAt(event.getCreatedAt())
                .build();
    }
}
