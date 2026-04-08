package ccommit.stylehub.coupon.repository;

import ccommit.stylehub.coupon.entity.CouponEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/04/09
 *
 * <p>
 * CouponEvent 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {

    // 비관적 락(SELECT FOR UPDATE)으로 쿠폰 이벤트를 조회한다. (선착순 발급 시 사용)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ce FROM CouponEvent ce WHERE ce.couponEventId = :couponEventId")
    Optional<CouponEvent> findByIdWithLock(@Param("couponEventId") Long couponEventId);

    List<CouponEvent> findByStoreStoreId(Long storeId);

    List<CouponEvent> findByActiveTrueAndStartedAtBeforeAndExpiredAtAfter(
            LocalDateTime now1, LocalDateTime now2);
}
