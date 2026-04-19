package ccommit.stylehub.coupon.entity;

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
 *
 * <p>
 * 사용자에게 발급된 개별 쿠폰 인스턴스를 관리한다.
 * CouponEvent와 User 간의 다대다 관계를 중간 테이블로 풀었다.
 * </p>
 */

@Entity
/*
 * (user_id, coupon_event_id) 복합 UNIQUE 제약을 두는 이유:
 *
 * 1) 중복 발급의 최종 방어선
 *    - 서비스 레이어(CouponService.checkDuplicateIssue)에서 1차 체크를 수행하지만,
 *      동시성 경합 상황에서 두 트랜잭션이 체크 단계를 동시에 통과한 뒤
 *      각자 save를 시도할 가능성이 있다.
 *    - DB UNIQUE 제약으로 최종 방어하여 같은 사용자가 동일 쿠폰 이벤트를
 *      두 번 발급받는 것을 원천 차단한다.
 *
 * 2) 비즈니스 규칙을 스키마로 표현
 *    - "한 사용자는 한 쿠폰 이벤트당 최대 1장만 소유" 이라는 도메인 규칙을
 *      애플리케이션 코드가 아닌 스키마 수준에서 명시하여 일관성을 보장한다.
 *
 * 3) 조회 성능 보너스
 *    - UNIQUE 인덱스가 자동 생성되어
 *      existsByUserUserIdAndCouponEventCouponEventId 같은 조회 쿼리가
 *      인덱스 스캔으로 빠르게 동작한다.
 *
 * PK는 user_coupon_id(대리 키)로 유지하여 조인 및 연관관계 처리 편의성을 확보하고,
 * 비즈니스 제약은 UNIQUE 제약으로 분리 표현하는 구조이다.
 */
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
}
