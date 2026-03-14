package bwj.stylehub.coupon.entity;

import bwj.stylehub.common.entity.BaseEntity;
import bwj.stylehub.coupon.enums.DiscountType;
import bwj.stylehub.store.entity.Store;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Entity
@Table(name = "coupon_events")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coupon_event_id")
    private Long couponEventId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = true)
    private Store store;

    @Column(nullable = false, length = 20)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private Integer discountValue;

    @Column(name = "min_order_amount", nullable = false)
    @Builder.Default
    private Integer minOrderAmount = 0;

    @Column(name = "issue_count", nullable = false)
    private Integer issueCount;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "expired_at", nullable = false)
    private LocalDateTime expiredAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    public static CouponEvent createAdminCoupon(String name, DiscountType discountType,
                                                Integer discountValue, Integer minOrderAmount,
                                                Integer issueCount, LocalDateTime startedAt,
                                                LocalDateTime expiredAt) {
        return buildCoupon(null, name, discountType, discountValue,
                minOrderAmount, issueCount, startedAt, expiredAt);
    }

    public static CouponEvent createStoreCoupon(Store store, String name, DiscountType discountType,
                                                Integer discountValue, Integer minOrderAmount,
                                                Integer issueCount, LocalDateTime startedAt,
                                                LocalDateTime expiredAt) {
        return buildCoupon(store, name, discountType, discountValue,
                minOrderAmount, issueCount, startedAt, expiredAt);
    }

    private static CouponEvent buildCoupon(Store store, String name, DiscountType discountType,
                                           Integer discountValue, Integer minOrderAmount,
                                           Integer issueCount, LocalDateTime startedAt,
                                           LocalDateTime expiredAt) {
        return CouponEvent.builder()
                .store(store)
                .name(name)
                .discountType(discountType)
                .discountValue(discountValue)
                .minOrderAmount(minOrderAmount)
                .issueCount(issueCount)
                .startedAt(startedAt)
                .expiredAt(expiredAt)
                .build();
    }
}