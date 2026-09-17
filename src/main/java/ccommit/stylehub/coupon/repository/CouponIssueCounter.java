package ccommit.stylehub.coupon.repository;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * @author WonJin Bae
 * @created 2026/09/15
 * @modified 2026/09/17 by WonJin - fix: 해제를 멱등으로 바꾸고 DB 한도 도달 시 0 덮어쓰기 대신 무효화, 키 TTL 과 Redis 장애 503 변환 추가
 *
 * <p>
 * 선착순 쿠폰의 남은 수량과 발급자를 Redis에 두고 DB 앞에서 요청을 거르는 카운터이다. 최종 한도는 DB가 지키므로 어긋나면 지우고 DB 기준으로 다시 만든다.
 * Redis 장애 시 DB로 바로 보내면 발급 트래픽이 이벤트 행 락에 몰려 커넥션 풀과 톰캣 스레드가 고갈되므로 503으로 발급을 멈춘다(fail-closed).
 * </p>
 */
@Component
@RequiredArgsConstructor
public class CouponIssueCounter {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueCounter.class);

    private static final long SOLD_OUT = -1L;
    private static final long ALREADY_ISSUED = -2L;
    private static final long NOT_INITIALIZED = -3L;

    // 만료된 이벤트는 CouponValidator가 거절하므로 키는 만료 후 하루만 둔다. 하루 여유는 만료 경계 요청과 서버 간 시계 차이를 흡수한다.
    // CouponEvent.isExpired()가 JVM 기본 시간대로 만료를 판단하므로 TTL 변환도 ZoneId.systemDefault()를 쓴다.
    private static final long KEY_RETENTION_DAYS_AFTER_EXPIRY = 1L;

    // 거절만 판단하고 아무것도 바꾸지 않는다. 카운터가 없으면 판단을 DB 경로로 넘긴다.
    private static final RedisScript<Long> PRECHECK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
                return -2
            end
            local remaining = redis.call('GET', KEYS[1])
            if remaining and tonumber(remaining) <= 0 then
                return -1
            end
            return 0
            """, Long.class);

    // ARGV[1] = userId, ARGV[2] = 키 만료 시각(epoch seconds). 발급자 Set 은 여기서 처음 생기므로 TTL 도 여기서 건다.
    private static final RedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
                return -2
            end
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return -3
            end
            local remaining = redis.call('DECR', KEYS[1])
            if remaining < 0 then
                redis.call('INCR', KEYS[1])
                return -1
            end
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('EXPIREAT', KEYS[1], ARGV[2])
            redis.call('EXPIREAT', KEYS[2], ARGV[2])
            return remaining
            """, Long.class);

    // ARGV[1] = 남은 수량, ARGV[2] = 키 만료 시각. 이미 있으면 덮지 않는다(동시 초기화 중 하나만 남음).
    private static final RedisScript<Long> INITIALIZE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SET', KEYS[1], ARGV[1], 'NX') then
                redis.call('EXPIREAT', KEYS[1], ARGV[2])
            end
            redis.call('EXPIREAT', KEYS[2], ARGV[2])
            return 0
            """, Long.class);

    // 예약 여부를 모르는 타임아웃 뒤에도 호출되므로, 발급자 Set에서 실제로 지운 경우에만 자리를 돌려준다(멱등).
    // 카운터가 없을 때 INCR 하면 1짜리 키가 새로 생겨 수량이 틀어지므로 있을 때만 되돌린다.
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SREM', KEYS[2], ARGV[1]) == 1 and redis.call('EXISTS', KEYS[1]) == 1 then
                redis.call('INCR', KEYS[1])
            end
            return 0
            """, Long.class);

    // 이 요청이 예약에 성공한 뒤에만 호출되므로 자리는 되돌리고, 사용자는 발급자로 남긴다.
    private static final RedisScript<Long> RELEASE_KEEPING_ISSUED_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                redis.call('INCR', KEYS[1])
            end
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('EXPIREAT', KEYS[2], ARGV[2])
            return 0
            """, Long.class);

    // 0으로 덮으면 수정 전 이벤트로 DB에 도달한 오래된 요청이 새 수량으로 다시 만든 카운터를 0으로 만들어 과소 발급된다.
    // 지우면 다음 요청이 행 락 안에서 DB 기준으로 다시 만들므로, 틀려도 잠시 클 뿐이고 초과분은 DB가 막는다.
    private static final RedisScript<Long> INVALIDATE_ON_LIMIT_REACHED_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SREM', KEYS[2], ARGV[1])
            redis.call('DEL', KEYS[1])
            return 0
            """, Long.class);

    // ARGV[1] = 발행 수량, ARGV[2] = 키 만료 시각
    private static final RedisScript<Long> RESET_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('EXPIREAT', KEYS[1], ARGV[2])
            redis.call('DEL', KEYS[2])
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public static String counterKey(Long couponEventId) {
        return "coupon:counter:" + couponEventId;
    }

    public static String issuedUsersKey(Long couponEventId) {
        return "coupon:issued_users:" + couponEventId;
    }

    public Result precheck(Long couponEventId, Long userId) {
        return toResult(execute(couponEventId, PRECHECK_SCRIPT, userId.toString()));
    }

    public Result reserve(Long couponEventId, Long userId, LocalDateTime eventExpiredAt) {
        return toResult(execute(couponEventId, RESERVE_SCRIPT, userId.toString(), expireAtEpochSecond(eventExpiredAt)));
    }

    // 호출자는 이벤트 행의 비관적 락을 쥔 상태에서 계산한 남은 수량을 넘겨야 한다(CouponService 참고).
    public void initializeIfAbsent(Long couponEventId, int remainingCount, LocalDateTime eventExpiredAt) {
        execute(couponEventId, INITIALIZE_SCRIPT, String.valueOf(remainingCount), expireAtEpochSecond(eventExpiredAt));
    }

    // 확보한 자리를 돌려주고 발급 기록도 지운다. DB 저장이 실패했거나 예약 결과를 모를 때 사용한다.
    public void release(Long couponEventId, Long userId) {
        execute(couponEventId, RELEASE_SCRIPT, userId.toString());
    }

    // 자리는 돌려주되 발급받은 사용자로는 남긴다. DB 에 이미 발급 이력이 있을 때 사용한다.
    public void releaseKeepingIssued(Long couponEventId, Long userId, LocalDateTime eventExpiredAt) {
        execute(couponEventId, RELEASE_KEEPING_ISSUED_SCRIPT, userId.toString(), expireAtEpochSecond(eventExpiredAt));
    }

    // DB 조건부 UPDATE 가 한도 도달로 0건이면, 사용자 기록을 지우고 카운터를 무효화한다.
    public void invalidateOnLimitReached(Long couponEventId, Long userId) {
        execute(couponEventId, INVALIDATE_ON_LIMIT_REACHED_SCRIPT, userId.toString());
    }

    // 새 이벤트는 같은 ID 로 남아 있던 이전 키를 덮어써 수량을 처음부터 시작한다.
    public void reset(Long couponEventId, int issueCount, LocalDateTime eventExpiredAt) {
        execute(couponEventId, RESET_SCRIPT, String.valueOf(issueCount), expireAtEpochSecond(eventExpiredAt));
    }

    // 카운터만 비워 다음 발급 요청이 DB 기준 남은 수량으로 다시 만들게 한다. 발급자 기록은 유지한다.
    public void invalidate(Long couponEventId) {
        callRedis(couponEventId, () -> redisTemplate.delete(counterKey(couponEventId)));
    }

    // 두 키를 DEL 한 번에 넘겨 하나만 지워진 상태가 관찰되지 않게 한다.
    public void clear(Long couponEventId) {
        callRedis(couponEventId, () -> redisTemplate.delete(List.of(counterKey(couponEventId), issuedUsersKey(couponEventId))));
    }

    private Long execute(Long couponEventId, RedisScript<Long> script, String... args) {
        return callRedis(couponEventId, () -> redisTemplate.execute(
                script,
                List.of(counterKey(couponEventId), issuedUsersKey(couponEventId)),
                (Object[]) args
        ));
    }

    // 재시도하면 성공할 수 있는 연결 실패·명령 타임아웃만 503으로 바꾸고, 스크립트 오류 같은 코드 결함은 500으로 드러낸다.
    // 장애 중에는 요청마다 로그가 남으므로 스택 트레이스 없이 원인 한 줄만 남기고 원인 예외는 cause로 보존한다.
    private <T> T callRedis(Long couponEventId, Supplier<T> call) {
        try {
            return call.get();
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            log.warn("Redis 접근 실패로 선착순 쿠폰 요청을 거절한다 — couponEventId={}, cause={}", couponEventId, e.toString());
            throw new BusinessException(ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE, e);
        }
    }

    private static String expireAtEpochSecond(LocalDateTime eventExpiredAt) {
        return String.valueOf(eventExpiredAt
                .plusDays(KEY_RETENTION_DAYS_AFTER_EXPIRY)
                .atZone(ZoneId.systemDefault())
                .toEpochSecond());
    }

    private Result toResult(Long code) {
        if (code == null) {
            throw new IllegalStateException("Redis 스크립트가 결과를 반환하지 않았습니다");
        }
        if (code == SOLD_OUT) {
            return Result.SOLD_OUT;
        }
        if (code == ALREADY_ISSUED) {
            return Result.ALREADY_ISSUED;
        }
        if (code == NOT_INITIALIZED) {
            return Result.NOT_INITIALIZED;
        }
        return Result.AVAILABLE;
    }

    public enum Result {
        AVAILABLE,
        SOLD_OUT,
        ALREADY_ISSUED,
        NOT_INITIALIZED
    }
}
