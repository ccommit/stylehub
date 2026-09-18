package ccommit.stylehub.coupon.repository;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * CouponIssueCounter 의 Redis 장애 변환 규칙을 검증하는 단위 테스트이다.
 * 선착순 발급은 fail-closed 라서, 재시도하면 성공할 수 있는 연결 실패·명령 타임아웃만 503 으로 바꾸고 코드 결함은 500 으로 드러나야 한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class CouponIssueCounterTest {

    private static final Long COUPON_EVENT_ID = 100L;
    private static final Long USER_ID = 1L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private CouponIssueCounter couponIssueCounter;

    @Test
    @DisplayName("Redis 연결 실패는 원인을 보존한 COUPON_ISSUE_TEMPORARILY_UNAVAILABLE(503)로 바꾼다")
    void translatesConnectionFailure() {
        RedisConnectionFailureException cause = new RedisConnectionFailureException("connection refused");
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willThrow(cause);

        assertThatThrownBy(() -> couponIssueCounter.precheck(COUPON_EVENT_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE)
                .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(cause));
        assertThat(ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE.getStatus().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("Redis 명령 타임아웃도 503 으로 바꾼다 — 스크립트 실행 여부를 모르는 상태라 호출자가 보상을 판단한다")
    void translatesCommandTimeout() {
        QueryTimeoutException cause = new QueryTimeoutException("Redis command timed out");
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willThrow(cause);

        assertThatThrownBy(() -> couponIssueCounter.reserve(COUPON_EVENT_ID, USER_ID, LocalDateTime.now().plusDays(1)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE)
                .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(cause));
    }

    @Test
    @DisplayName("키 삭제(재동기화) 중 연결 실패도 503 으로 바꾼다")
    void translatesConnectionFailureOnClear() {
        given(redisTemplate.delete(anyCollection())).willThrow(new RedisConnectionFailureException("connection refused"));

        assertThatThrownBy(() -> couponIssueCounter.clear(COUPON_EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE);
    }

    @Test
    @DisplayName("스크립트 오류 같은 그 밖의 Redis 예외는 코드 결함이므로 바꾸지 않고 그대로 던진다")
    void doesNotTranslateScriptError() {
        RedisSystemException scriptError = new RedisSystemException("ERR user_script", new IllegalStateException());
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willThrow(scriptError);

        assertThatThrownBy(() -> couponIssueCounter.release(COUPON_EVENT_ID, USER_ID))
                .isSameAs(scriptError);
    }
}
