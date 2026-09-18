package ccommit.stylehub.coupon.entity;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.enums.CouponStatus;
import ccommit.stylehub.user.entity.User;
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
import jakarta.persistence.UniqueConstraint;
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
 * @modified 2026/04/09 by WonJin - feat: 와일드카드 import 수정, UniqueConstraint 추가
 * @modified 2026/04/16 by WonJin - docs: (user_id, coupon_event_id) UNIQUE 제약 사용 이유 주석 추가
 * @modified 2026/05/08 by WonJin - feat: markUsed() / markUnused() 상태 전이 메서드 추가 — 쿠폰 사용 주문 + 결제 실패 시 보상 트랜잭션 지원
 * @modified 2026/09/17 by WonJin - docs: 삭제된 checkDuplicateIssue 참조를 Redis 사전 확인 + DB UNIQUE 최종 방어 설명으로 정정
 *
 * <p>
 * 사용자에게 발급된 개별 쿠폰 인스턴스를 관리한다.
 * CouponEvent와 User 간의 다대다 관계를 중간 테이블로 풀었다.
 * </p>
 */

@Entity
// 한 사용자는 이벤트당 1장만 가진다. Redis 발급자 기록은 유실·재동기화·만료로 사라질 수 있어 DB 유니크 제약을 최종 방어선으로 둔다.
@Table(name = "user_coupons", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "coupon_event_id"})
})
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

    // 이미 USED면 예외를 던져 동시 사용·재사용을 막는 마지막 방어선이 된다.
    public void markUsed() {
        if (this.status != CouponStatus.UNUSED) {
            throw new BusinessException(ErrorCode.COUPON_NOT_AVAILABLE);
        }
        this.status = CouponStatus.USED;
        this.usedAt = LocalDateTime.now();
    }

    // 보상 호출이 중복돼도 안전하도록 이미 UNUSED여도 예외 없이 복구한다(멱등).
    public void markUnused() {
        this.status = CouponStatus.UNUSED;
        this.usedAt = null;
    }
}
