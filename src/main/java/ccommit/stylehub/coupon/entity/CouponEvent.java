package ccommit.stylehub.coupon.entity;

import ccommit.stylehub.common.entity.BaseEntity;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
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
 * @modified 2026/04/09 by WonJin - feat: issuedCount 필드 추가, 선착순 발급 검증 메서드 추가
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
    @JoinColumn(name = "store_id")
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

    @Column(name = "issued_count", nullable = false)
    @Builder.Default
    private Integer issuedCount = 0;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "expired_at", nullable = false)
    private LocalDateTime expiredAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    // 스토어 쿠폰 생성
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

    // 플랫폼(관리자) 쿠폰 생성 — store = null
    public static CouponEvent createPlatform(String name, DiscountType discountType,
                                             Integer discountValue, Integer minOrderAmount,
                                             Integer issueCount, LocalDateTime startedAt,
                                             LocalDateTime expiredAt) {
        return CouponEvent.builder()
                .name(name)
                .discountType(discountType)
                .discountValue(discountValue)
                .minOrderAmount(minOrderAmount)
                .issueCount(issueCount)
                .startedAt(startedAt)
                .expiredAt(expiredAt)
                .build();
    }

    public void increaseIssuedCount() {
        if (this.issuedCount >= this.issueCount) {
            throw new BusinessException(ErrorCode.COUPON_SOLD_OUT);
        }
        this.issuedCount++;
    }

    public void update(Integer issueCount, Integer minOrderAmount,
                       LocalDateTime startedAt, LocalDateTime expiredAt) {
        this.issueCount = issueCount;
        this.minOrderAmount = minOrderAmount;
        this.startedAt = startedAt;
        this.expiredAt = expiredAt;
    }

    public int calculateDiscount(int orderAmount) {
        return discountType.calculate(orderAmount, discountValue);
    }

    public void deactivate() {
        this.active = false;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiredAt);
    }

    public boolean isNotStarted() {
        return LocalDateTime.now().isBefore(this.startedAt);
    }
}
