package ccommit.stylehub.user.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.common.util.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 이메일·IP 별 로그인 실패 횟수를 Redis 에 세어, 한도를 넘으면 비밀번호 검증(BCrypt) 전에 거절한다.
 * 없는 이메일에도 더미 BCrypt 를 돌리므로, 제한이 없으면 비로그인 요청만으로 CPU 를 묶을 수 있다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptLimiter {

    static final int MAX_FAILURES_PER_EMAIL = 10;
    static final Duration EMAIL_WINDOW = Duration.ofMinutes(5);
    static final int MAX_FAILURES_PER_IP = 30;
    static final Duration IP_WINDOW = Duration.ofMinutes(1);

    // 첫 실패에만 만료를 걸어, 창이 끝나면 횟수가 저절로 사라진다. INCR 과 EXPIRE 를 한 번에 실행해 만료 없는 키가 남지 않게 한다.
    private static final RedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public void checkAllowed(String email, String clientIp) {
        if (count(emailKey(email)) >= MAX_FAILURES_PER_EMAIL || count(ipKey(clientIp)) >= MAX_FAILURES_PER_IP) {
            throw new BusinessException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
        }
    }

    public void recordFailure(String email, String clientIp) {
        increment(emailKey(email), EMAIL_WINDOW);
        increment(ipKey(clientIp), IP_WINDOW);
    }

    // 성공하면 그 이메일의 실패만 지운다. IP 는 여러 계정을 번갈아 시도하는 경우를 막으려 창이 끝날 때까지 둔다.
    public void reset(String email) {
        redisTemplate.delete(emailKey(email));
    }

    static String emailKey(String email) {
        return "login:fail:email:" + HashUtils.sha256Hex(email.trim().toLowerCase(Locale.ROOT));
    }

    static String ipKey(String clientIp) {
        return "login:fail:ip:" + clientIp;
    }

    private long count(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private void increment(String key, Duration window) {
        redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), String.valueOf(window.toSeconds()));
    }
}
