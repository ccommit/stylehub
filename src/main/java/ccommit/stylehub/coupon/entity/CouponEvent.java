package ccommit.stylehub.coupon.entity;

import ccommit.stylehub.common.entity.BaseEntity;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.store.entity.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 19:00 by WonJin - refactor: 모든 엔티티 클래스의 JPA 와일드카드 import를 명시적 import로 교체
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 관리자 또는 스토어가 발행하는 쿠폰 이벤트를 관리한다.
 * store 필드의 null 여부로 관리자/스토어 쿠폰을 구분한다.
 * </p>
 */

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
    @Builder.Default  // 빌더로 객체 생성 시 이 필드를 명시하지 않으면 선언된 기본값을 사용한다

    private Boolean active = true;

    // store가 null이면 플랫폼 쿠폰, 값이 있으면 스토어 쿠폰
    public static CouponEvent create(Store store, String name, DiscountType discountType,
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