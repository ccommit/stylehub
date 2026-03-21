package ccommit.stylehub.coupon.entity;

import ccommit.stylehub.coupon.enums.CouponStatus;
import ccommit.stylehub.user.entity.User;
import jakarta.persistence.*;
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
 * 사용자에게 발급된 개별 쿠폰 인스턴스를 관리한다.
 * CouponEvent와 User 간의 다대다 관계를 중간 테이블로 풀었다.
 * </p>
 */

@Entity
@Table(name = "user_coupons")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_coupon_id")
    private Long userCouponId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_event_id", nullable = false)
    private CouponEvent couponEvent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CouponStatus status = CouponStatus.UNUSED;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public static UserCoupon create(User user, CouponEvent couponEvent) {
        return UserCoupon.builder()
                .user(user)
                .couponEvent(couponEvent)
                .build();
    }
}
