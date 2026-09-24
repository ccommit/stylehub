package ccommit.stylehub.coupon.repository;

import ccommit.stylehub.coupon.entity.CouponEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/04/09
 * @modified 2026/04/16 by WonJin - refactor: 활성 쿠폰 조회를 @Query로 전환, 파라미터 단일화 (now1/now2 → now)
 * @modified 2026/09/15 by WonJin - feat: 발급 수 조건부 증가 쿼리 추가 (DB 기준 초과 발급 차단)
 * @modified 2026/09/17 by WonJin - docs: 비관적 락 조회의 실제 용도(수정·비활성화·카운터 초기화 직렬화)와 사용 시 주의점 명시
 *
 * <p>
 * CouponEvent 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {

    // 수정·비활성화·카운터 초기화를 발급의 조건부 UPDATE와 같은 행 락으로 직렬화한다.
    // 이미 로드된 엔티티는 조회 결과로 갱신되지 않으므로 최신 값이 필요하면 락을 쥔 뒤 refresh 한다(CouponService 참고).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ce FROM CouponEvent ce WHERE ce.couponEventId = :couponEventId")
    Optional<CouponEvent> findByIdWithLock(@Param("couponEventId") Long couponEventId);

    // Redis 카운터와 무관하게 DB가 초과 발급을 막는 최종 방어선이다. 0이면 이미 한도에 도달한 것이다.
    // 조건과 증가가 한 문장이라 조회와 갱신 사이에 다른 요청이 끼어들 수 없다.
    @Modifying
    @Query("UPDATE CouponEvent ce SET ce.issuedCount = ce.issuedCount + 1 " +
            "WHERE ce.couponEventId = :couponEventId AND ce.issuedCount < ce.issueCount")
    int increaseIssuedCount(@Param("couponEventId") Long couponEventId);

    List<CouponEvent> findByStoreUserUserId(Long userId);

    // 현재 시점 기준으로 활성 상태이며 진행 중인 쿠폰 이벤트를 조회한다.
    @Query("SELECT ce FROM CouponEvent ce " +
            "WHERE ce.active = true " +
            "AND ce.startedAt < :now " +
            "AND ce.expiredAt > :now")
    List<CouponEvent> findActiveCouponEvents(@Param("now") LocalDateTime now);
}
