package ccommit.stylehub.coupon.repository;

import ccommit.stylehub.coupon.entity.UserCoupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/04/15
 * @modified 2026/05/08 by WonJin - feat: findByIdWithLock 추가 (쿠폰 사용 주문 시 동시 사용 차단을 위한 비관적 락)
 *
 * <p>
 * 사용자 쿠폰 조회 레포지토리이다.
 * 마이페이지 조회는 CouponEvent/Store를 fetch join하여 N+1을 방지하고,
 * userCouponId 커서로 ID 역순 페이징한다(대용량 환경의 안정적 페이징).
 * </p>
 */
public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {

    boolean existsByUserUserIdAndCouponEventCouponEventId(Long userId, Long couponEventId);

    /**
     * 비관적 락으로 UserCoupon 을 조회 — 동시 사용 차단 (같은 UserCoupon 으로 두 주문이 동시 시도 시 1 건만 성공).
     * couponEvent 와 user 도 fetch join 해 LAZY 로딩 N+1 방지.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT uc
            FROM UserCoupon uc
            JOIN FETCH uc.couponEvent
            JOIN FETCH uc.user
            WHERE uc.userCouponId = :userCouponId
            """)
    Optional<UserCoupon> findByIdWithLock(@Param("userCouponId") Long userCouponId);

    @Query("""
            SELECT uc
            FROM UserCoupon uc
            JOIN FETCH uc.couponEvent ce
            LEFT JOIN FETCH ce.storeUser
            WHERE uc.user.userId = :userId
            ORDER BY uc.userCouponId DESC
            """)
    List<UserCoupon> findByUserIdWithCouponEvent(@Param("userId") Long userId);
}
