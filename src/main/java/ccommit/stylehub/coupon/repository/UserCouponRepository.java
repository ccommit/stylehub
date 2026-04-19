package ccommit.stylehub.coupon.repository;

import ccommit.stylehub.coupon.entity.UserCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/04/15
 *
 * <p>
 * 사용자 쿠폰 조회 레포지토리이다.
 * 마이페이지 조회는 CouponEvent/Store를 fetch join하여 N+1을 방지하고,
 * userCouponId 커서로 ID 역순 페이징한다(대용량 환경의 안정적 페이징).
 * </p>
 */
public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {

    boolean existsByUserUserIdAndCouponEventCouponEventId(Long userId, Long couponEventId);

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
