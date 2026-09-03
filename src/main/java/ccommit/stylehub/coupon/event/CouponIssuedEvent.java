package ccommit.stylehub.coupon.event;

/**
 * @author WonJin Bae
 * @created 2026/05/06
 *
 * <p>
 * 선착순 쿠폰 발급이 *Redis 측에서 atomic 으로 확정* 된 직후 발행되는 이벤트.
 * 비동기 listener 가 UserCoupon 행을 DB 에 INSERT 한다 (eventual consistency).
 * Redis 가 single source of truth — DB 는 발급 이력 기록.
 * </p>
 */
public record CouponIssuedEvent(Long userId, Long couponEventId) {
}
