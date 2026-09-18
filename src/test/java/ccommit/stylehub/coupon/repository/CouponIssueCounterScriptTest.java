package ccommit.stylehub.coupon.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * CouponIssueCounter의 Lua 스크립트를 실제 Redis로 실행해 해제 멱등성, 한도 도달 시 무효화, 키 TTL을 검증한다.
 * DB를 쓰지 않으므로 H2 식별자와 겹치지 않는 큰 이벤트 ID를 쓰고 매번 키를 지운다.
 * </p>
 */
@SpringBootTest
class CouponIssueCounterScriptTest {

    private static final Long USER_ID = 7L;

    @Autowired
    private CouponIssueCounter couponIssueCounter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long couponEventId;
    private LocalDateTime expiredAt;

    @BeforeEach
    void setUp() {
        couponEventId = 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        expiredAt = LocalDateTime.now().plusDays(2).withNano(0);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(List.of(CouponIssueCounter.counterKey(couponEventId), CouponIssueCounter.issuedUsersKey(couponEventId)));
    }

    @Test
    @DisplayName("해제를 두 번 호출해도 자리는 한 번만 돌아온다")
    void releaseIsIdempotent() {
        couponIssueCounter.reset(couponEventId, 5, expiredAt);
        assertThat(couponIssueCounter.reserve(couponEventId, USER_ID, expiredAt)).isEqualTo(CouponIssueCounter.Result.AVAILABLE);
        assertThat(counter()).isEqualTo("4");

        couponIssueCounter.release(couponEventId, USER_ID);
        couponIssueCounter.release(couponEventId, USER_ID);

        assertThat(counter()).isEqualTo("5");
        assertThat(isIssuedUser(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("예약되지 않은 사용자를 해제하면(타임아웃으로 예약 여부를 모를 때) 카운터가 늘지 않는다")
    void releaseWithoutReservationDoesNotIncreaseCounter() {
        couponIssueCounter.reset(couponEventId, 5, expiredAt);

        couponIssueCounter.release(couponEventId, USER_ID);

        assertThat(counter()).isEqualTo("5");
    }

    @Test
    @DisplayName("매진 결과로 끝난 예약은 사용자 기록을 남기지 않으므로, 이후 해제해도 카운터가 늘지 않는다")
    void releaseAfterSoldOutReservationDoesNotIncreaseCounter() {
        couponIssueCounter.reset(couponEventId, 0, expiredAt);
        assertThat(couponIssueCounter.reserve(couponEventId, USER_ID, expiredAt)).isEqualTo(CouponIssueCounter.Result.SOLD_OUT);

        couponIssueCounter.release(couponEventId, USER_ID);

        assertThat(counter()).isEqualTo("0");
    }

    @Test
    @DisplayName("DB 한도 도달을 알리면 카운터를 0 으로 덮지 않고 지우고, 사용자 기록도 지운다")
    void invalidateOnLimitReachedDeletesCounter() {
        couponIssueCounter.reset(couponEventId, 5, expiredAt);
        couponIssueCounter.reserve(couponEventId, USER_ID, expiredAt);

        couponIssueCounter.invalidateOnLimitReached(couponEventId, USER_ID);

        assertThat(counter()).isNull();
        assertThat(isIssuedUser(USER_ID)).isFalse();
        assertThat(couponIssueCounter.reserve(couponEventId, USER_ID, expiredAt)).isEqualTo(CouponIssueCounter.Result.NOT_INITIALIZED);
    }

    @Test
    @DisplayName("이미 카운터가 있으면 초기화가 덮어쓰지 않는다")
    void initializeIfAbsentDoesNotOverwrite() {
        couponIssueCounter.initializeIfAbsent(couponEventId, 3, expiredAt);
        couponIssueCounter.initializeIfAbsent(couponEventId, 9, expiredAt);

        assertThat(counter()).isEqualTo("3");
    }

    @Test
    @DisplayName("예약 후 카운터와 발급자 키 모두 이벤트 만료 + 1일 시각에 만료되도록 TTL 이 걸린다")
    void reserveSetsExpiryOnBothKeys() {
        couponIssueCounter.initializeIfAbsent(couponEventId, 3, expiredAt);
        couponIssueCounter.reserve(couponEventId, USER_ID, expiredAt);

        long expectedTtlSeconds = expiredAt.plusDays(1).atZone(ZoneId.systemDefault()).toEpochSecond()
                - System.currentTimeMillis() / 1000;
        assertThat(redisTemplate.getExpire(CouponIssueCounter.counterKey(couponEventId)))
                .isCloseTo(expectedTtlSeconds, within(5L));
        assertThat(redisTemplate.getExpire(CouponIssueCounter.issuedUsersKey(couponEventId)))
                .isCloseTo(expectedTtlSeconds, within(5L));
    }

    @Test
    @DisplayName("재동기화는 카운터와 발급자 기록을 함께 지운다")
    void clearDeletesBothKeys() {
        couponIssueCounter.reset(couponEventId, 5, expiredAt);
        couponIssueCounter.reserve(couponEventId, USER_ID, expiredAt);

        couponIssueCounter.clear(couponEventId);

        assertThat(counter()).isNull();
        assertThat(redisTemplate.hasKey(CouponIssueCounter.issuedUsersKey(couponEventId))).isFalse();
    }

    private String counter() {
        return redisTemplate.opsForValue().get(CouponIssueCounter.counterKey(couponEventId));
    }

    private boolean isIssuedUser(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(CouponIssueCounter.issuedUsersKey(couponEventId), userId.toString()));
    }
}
